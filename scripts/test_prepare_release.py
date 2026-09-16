"""Offline release checks using synthetic extension ZIPs and metadata."""

import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from zipfile import ZipFile

from prepare_release import ReleaseError, prepare_release


class PrepareReleaseTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.artifacts = self.root / "artifacts"
        self.artifacts.mkdir()
        self.plugins = self.root / "plugins.json"
        self.repo = self.root / "repo.json"
        self.output = self.root / "public"
        self.repo.write_text(json.dumps({
            "name": "Example repository", "manifestVersion": 1,
            "pluginLists": ["https://example.test/old-plugins-v4.json"],
        }), encoding="utf-8")
        self.entries = [self.make_package("ExamplePlugin", 6)]
        self.write_entries()

    def make_package(self, name, version, manifest_version=None, include_code=True):
        package = self.artifacts / f"{name}.cs3"
        with ZipFile(package, "w") as archive:
            archive.writestr("manifest.json", json.dumps({
                "name": name,
                "version": version if manifest_version is None else manifest_version,
                "pluginClassName": "example.Plugin",
                "requiresResources": False,
            }))
            if include_code:
                archive.writestr("classes.dex", b"dex\n035\0synthetic fixture")
        data = package.read_bytes()
        return {
            "internalName": name, "name": name, "version": version,
            "url": f"https://example.test/{name}.cs3",
            "fileSize": len(data), "fileHash": "sha256-" + hashlib.sha256(data).hexdigest(),
            "description": "Synthetic plugin", "status": 1,
        }

    def write_entries(self):
        self.plugins.write_text(json.dumps(self.entries), encoding="utf-8")

    def prepare(self, repository="example-owner/example-repository"):
        prepare_release(self.plugins, self.artifacts, self.repo, self.output, repository)

    def assert_rejected(self, message, repository="example-owner/example-repository"):
        with self.assertRaisesRegex(ReleaseError, message):
            self.prepare(repository)
        self.assertFalse(self.output.exists())

    def test_stable_and_legacy_links_advance_to_verified_release(self):
        self.prepare()
        catalogue = json.loads((self.output / "plugins.json").read_text())
        repository = json.loads((self.output / "repo.json").read_text())
        base = "https://raw.githubusercontent.com/example-owner/example-repository/builds"
        self.assertEqual(catalogue[0]["url"], f"{base}/ExamplePlugin-v6.cs3")
        self.assertEqual(catalogue[0]["version"], 6)
        self.assertEqual(repository["pluginLists"], [f"{base}/plugins.json"])
        for version in range(1, 7):
            self.assertEqual(json.loads((self.output / f"plugins-v{version}.json").read_text()), catalogue)
            self.assertEqual(json.loads((self.output / f"repo-v{version}.json").read_text()), repository)
        expected = (self.artifacts / "ExamplePlugin.cs3").read_bytes()
        self.assertEqual((self.output / "ExamplePlugin-v6.cs3").read_bytes(), expected)
        self.assertEqual((self.output / "ExamplePlugin.cs3").read_bytes(), expected)
        self.assertEqual(catalogue[0]["fileHash"], "sha256-" + hashlib.sha256(expected).hexdigest())

    def test_multiple_packages_keep_distinct_urls_and_versions(self):
        self.entries.append(self.make_package("OtherPlugin", 3))
        self.write_entries()
        self.prepare()
        catalogue = json.loads((self.output / "plugins.json").read_text())
        self.assertTrue(catalogue[1]["url"].endswith("/OtherPlugin-v3.cs3"))
        self.assertEqual(catalogue[1]["version"], 3)
        self.assertNotEqual(catalogue[0]["url"], catalogue[1]["url"])

    def test_rejects_missing_package_before_writing_output(self):
        self.entries.append({"internalName": "MissingPlugin", "version": 2})
        self.write_entries()
        self.assert_rejected("missing package")

    def test_rejects_package_manifest_version_mismatch(self):
        self.entries = [self.make_package("ExamplePlugin", 6, manifest_version=5)]
        self.write_entries()
        self.assert_rejected("manifest version")

    def test_rejects_package_without_code(self):
        self.entries = [self.make_package("ExamplePlugin", 6, include_code=False)]
        self.write_entries()
        self.assert_rejected("valid extension ZIP")

    def test_rejects_non_zip_package(self):
        (self.artifacts / "ExamplePlugin.cs3").write_bytes(b"synthetic error response")
        self.assert_rejected("valid extension ZIP")

    def test_rejects_stale_size_metadata(self):
        self.entries[0]["fileSize"] += 1
        self.write_entries()
        self.assert_rejected("package size")

    def test_rejects_stale_hash_metadata(self):
        self.entries[0]["fileHash"] = "sha256-" + "0" * 64
        self.write_entries()
        self.assert_rejected("package hash")

    def test_rejects_invalid_versions(self):
        for version in (0, -1, True, "6", 6.5):
            with self.subTest(version=version):
                self.entries[0]["version"] = version
                self.write_entries()
                self.assert_rejected("positive integer")

    def test_rejects_filename_traversal(self):
        self.entries[0]["internalName"] = "../ExamplePlugin"
        self.write_entries()
        self.assert_rejected("filename-safe")

    def test_rejects_duplicate_package_outputs(self):
        self.entries.append(dict(self.entries[0]))
        self.write_entries()
        self.assert_rejected("duplicate package output")

    def test_rejects_invalid_repository_metadata(self):
        self.repo.write_text('{"manifestVersion": true}', encoding="utf-8")
        self.assert_rejected("manifestVersion 1")

    def test_rejects_invalid_repository_targets(self):
        for repository in ("", "owner", "owner/repo/extra", "owner/..", "https://example.test/repo", "owner/repo?ref=x"):
            with self.subTest(repository=repository):
                self.assert_rejected("repository", repository)

    def test_rejects_empty_catalogue(self):
        self.entries = []
        self.write_entries()
        self.assert_rejected("nonempty list")

    def test_refuses_preexisting_output(self):
        self.output.mkdir()
        sentinel = self.output / "existing.txt"
        sentinel.write_text("preserve this", encoding="utf-8")
        with self.assertRaisesRegex(ReleaseError, "must not already exist"):
            self.prepare()
        self.assertEqual(sentinel.read_text(), "preserve this")


if __name__ == "__main__":
    unittest.main()
