from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path
from zipfile import ZipFile

import catalogue_config
import health_check
import release_gate


class CatalogueConfigTests(unittest.TestCase):
    def test_every_row_feed_uses_a_known_source(self):
        bases, rows = catalogue_config.load_sources(), catalogue_config.load_rows()
        self.assertGreater(len(rows), 20)
        self.assertEqual({"MP", "GV", "GPT"}, set(bases))
        for row in rows:
            for prefix, path in row.feeds:
                self.assertIn(prefix, bases, row.key)
                self.assertTrue(path.startswith("/"), row.key)

    def test_each_source_has_its_own_domain(self):
        self.assertEqual(3, len(set(catalogue_config.load_sources().values())))

    def test_time_rows_are_flagged(self):
        rows = {row.key: row for row in catalogue_config.load_rows()}
        self.assertTrue(rows["ALL|new"].windowed)
        self.assertFalse(rows["MP|/"].windowed)
        self.assertEqual(6, rows["ALL|new"].min_items)

    def test_removed_sources_are_gone_from_the_source_tree(self):
        text = "".join(p.read_text(encoding="utf-8").lower() for p in catalogue_config.PACKAGE.rglob("*.kt"))
        for word in ("eporner", "gay0day", "pornhub", "boyfriendtv", "trendyporn", "pornone", "fxggxt", "myvidster"):
            self.assertNotIn(word, text)


class HealthCheckTests(unittest.TestCase):
    def test_titles_ignore_studio_prefix_resolution_and_punctuation(self):
        self.assertEqual("city tour", health_check.normalize_title("[Studio X] City Tour 1080p"))
        self.assertEqual("city tour", health_check.normalize_title("StudioX - City-Tour (4K)!"))

    def test_duplicate_ratio(self):
        self.assertEqual(0.0, health_check.duplicate_ratio(["One", "Two", "Three"]))
        self.assertAlmostEqual(0.5, health_check.duplicate_ratio(["Same 720p", "same", "Other", "other 1080p"]))
        self.assertEqual(0.0, health_check.duplicate_ratio([]))

    def test_declared_sources_win_over_the_regex_and_entities_are_decoded(self):
        page = '<video><source src="https://c.example/a.mp4?t=1&amp;e=2"></video><script>"https://o.example/z.mp4"</script>'
        self.assertEqual(["https://c.example/a.mp4?t=1&e=2"], health_check.extract_streams(page, "https://s.example/v/1/"))

    def test_structured_data_before_regex(self):
        page = '<script type="application/ld+json">{"contentUrl":"https:\\/\\/c.example\\/v.mp4"}</script> "https://o.example/z.mp4"'
        self.assertEqual(["https://c.example/v.mp4"], health_check.extract_streams(page, "https://s.example/"))

    def test_player_flashvars_are_found_and_the_pixel_is_ignored(self):
        page = "var f={video_url: 'https://c.example/get_file/1/a.mp4/?t=1', video_alt_url: 'function/0/https://c.example/get_file/2/b.mp4/'}; var p='https://c.example/pixel.mp4';"
        self.assertEqual(["https://c.example/get_file/1/a.mp4/?t=1", "https://c.example/get_file/2/b.mp4/"],
                         health_check.extract_streams(page, "https://s.example/"))

    def test_gayporntube_detail_links_come_from_anchors(self):
        page = b'<div data-video-id="1"><a class="image image-ar" href="https://g.example/video/1/x" title="t"><img data-src="https://s.example/7.jpg"></a></div>'
        self.assertEqual(["https://g.example/video/1/x"], health_check.detail_links("GPT", page, "https://g.example"))

    def test_regex_fallback_skips_thumbnails(self):
        page = 'var a="https://c.example/v.mp4"; var t="https://c.example/v.mp4.jpg";'
        self.assertEqual(["https://c.example/v.mp4"], health_check.extract_streams(page, "https://s.example/"))

    def test_empty_category_fails_the_check(self):
        rows = catalogue_config.load_rows()
        counts = {feed: 30 for row in rows for feed in row.feeds}
        self.assertEqual([], health_check.evaluate_rows(rows, counts))
        broken = next(row for row in rows if row.key == "MP|/categories/muscle/")
        counts[broken.feeds[0]] = 2
        failures = health_check.evaluate_rows(rows, counts)
        self.assertEqual(1, len(failures))
        self.assertIn("Muscle", failures[0])

    def test_card_links_are_deduplicated(self):
        page = b'<a href="/videos/1/a/">x</a><a href="/videos/1/a/">y</a><a href="/videos/2/b/">z</a>'
        self.assertEqual(2, len(health_check.card_links("MP", page, "https://manporn.xxx")))
        self.assertEqual(2, len(health_check.card_links("GPT", b'data-video-id="1" data-video-id="2" data-video-id="1"', "https://g.example")))


class ReleaseGateTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        (self.root / "build.gradle.kts").write_text("version = 7\n")

    def tearDown(self):
        self.tmp.cleanup()

    def package(self, version: int, listed: int):
        with ZipFile(self.root / "Plugin.cs3", "w") as z:
            z.writestr("manifest.json", json.dumps({"name": "Plugin", "version": version}))
        plugins = self.root / "plugins.json"
        plugins.write_text(json.dumps([{"internalName": "Plugin", "version": listed}]))
        return plugins

    def test_matching_versions_pass(self):
        plugins = self.package(7, 7)
        self.assertEqual([], release_gate.check_versions(plugins, self.root, self.root / "build.gradle.kts"))

    def test_embedded_version_mismatch_is_refused(self):
        plugins = self.package(6, 7)
        failures = release_gate.check_versions(plugins, self.root, self.root / "build.gradle.kts")
        self.assertTrue(any("embedded .cs3 version 6 differs" in f for f in failures))

    def test_source_version_mismatch_is_refused(self):
        plugins = self.package(8, 8)
        self.assertTrue(release_gate.check_versions(plugins, self.root, self.root / "build.gradle.kts"))

    def write_results(self, failing: str | None = None, omit: str | None = None):
        results = self.root / "results"
        results.mkdir(exist_ok=True)
        cases = []
        for name in release_gate.MUST_PASS:
            if name == omit:
                continue
            body = "<failure message='x'/>" if name == failing else ""
            cases.append(f"<testcase name='{name}' classname='ContentPolicyTest'>{body}</testcase>")
        (results / "a.xml").write_text(f"<testsuite>{''.join(cases)}</testsuite>")
        return results

    def test_passing_policy_tests(self):
        self.assertEqual([], release_gate.check_tests(self.write_results()))

    def test_failing_female_test_is_refused(self):
        failures = release_gate.check_tests(self.write_results(failing="every female case is rejected"))
        self.assertTrue(failures)

    def test_missing_female_test_is_refused(self):
        failures = release_gate.check_tests(self.write_results(omit="every female case is rejected"))
        self.assertTrue(any("did not pass or did not run" in f for f in failures))

    def test_no_results_is_refused(self):
        empty = self.root / "empty"
        empty.mkdir()
        self.assertTrue(release_gate.check_tests(empty))

    def links(self, config):
        path = self.root / "links.json"
        path.write_text(json.dumps(config))
        return path

    def test_tinyurl_pointing_elsewhere_is_refused(self):
        path = self.links({"tinyurl": {"url": "https://tinyurl.com/x", "expected": "https://right.example/repo.json"}})
        failures, _ = release_gate.check_links(path, resolve=lambda url: "https://wrong.example/")
        self.assertEqual(1, len(failures))

    def test_tinyurl_pointing_to_the_repo_passes(self):
        path = self.links({"tinyurl": {"url": "https://tinyurl.com/x", "expected": "https://right.example/repo.json"}})
        failures, _ = release_gate.check_links(path, resolve=lambda url: "https://right.example/repo.json")
        self.assertEqual([], failures)

    def test_unconfigured_tinyurl_is_reported_not_hidden(self):
        failures, notes = release_gate.check_links(self.links({"tinyurl": None}))
        self.assertEqual([], failures)
        self.assertTrue(any("not configured" in n for n in notes))


if __name__ == "__main__":
    unittest.main()
