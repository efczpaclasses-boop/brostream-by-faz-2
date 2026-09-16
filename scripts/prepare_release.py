#!/usr/bin/env python3
"""Validate local build packages and prepare stable and legacy update endpoints."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import sys
from pathlib import Path
from zipfile import BadZipFile, ZipFile


class ReleaseError(ValueError):
    """The generated metadata and release artifacts are not consistent."""


def validate_repository(repository: str) -> str:
    if not re.fullmatch(r"[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?/[A-Za-z0-9_.-]+", repository):
        raise ReleaseError("repository must be a GitHub owner/repository pair")
    if repository.split("/", 1)[1] in (".", ".."):
        raise ReleaseError("repository name cannot be a path component")
    return repository


def read_json(path: Path):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        raise ReleaseError(f"cannot read JSON metadata: {path}") from exc


def validate_artifact(entry: dict, artifacts: Path) -> tuple[Path, int, str]:
    name = entry.get("internalName")
    if not isinstance(name, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]*", name):
        raise ReleaseError("each plugin needs a filename-safe internalName")
    version = entry.get("version")
    if type(version) is not int or version < 1:
        raise ReleaseError(f"{name}: version must be a positive integer")

    artifact = artifacts / f"{name}.cs3"
    if not artifact.is_file():
        raise ReleaseError(f"missing package: {artifact}")
    try:
        with ZipFile(artifact) as package:
            if package.testzip() is not None:
                raise ReleaseError(f"{name}: package has a corrupt ZIP entry")
            manifest = json.loads(package.read("manifest.json"))
            if not isinstance(manifest, dict) or manifest.get("name") != name:
                raise ReleaseError(f"{name}: package manifest name does not match metadata")
            if type(manifest.get("version")) is not int or manifest["version"] != version:
                raise ReleaseError(f"{name}: package manifest version does not match metadata")
            if package.getinfo("classes.dex").file_size == 0:
                raise ReleaseError(f"{name}: package has an empty classes.dex")
    except (BadZipFile, KeyError, ValueError, OSError) as exc:
        if isinstance(exc, ReleaseError):
            raise
        raise ReleaseError(f"{name}: package is not a valid extension ZIP with manifest.json and classes.dex") from exc

    size = artifact.stat().st_size
    with artifact.open("rb") as stream:
        digest = hashlib.sha256()
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    file_hash = "sha256-" + digest.hexdigest()
    if "fileSize" in entry and (type(entry["fileSize"]) is not int or entry["fileSize"] != size):
        raise ReleaseError(f"{name}: package size does not match metadata")
    if "fileHash" in entry and entry["fileHash"] != file_hash:
        raise ReleaseError(f"{name}: package hash does not match metadata")
    return artifact, size, file_hash


def prepare_release(plugins: Path, artifacts: Path, repo: Path, output: Path, repository: str) -> None:
    repository = validate_repository(repository)
    entries = read_json(plugins)
    repo_metadata = read_json(repo)
    if not isinstance(entries, list) or not entries or not all(isinstance(entry, dict) for entry in entries):
        raise ReleaseError("plugins.json must contain a nonempty list of plugin objects")
    if not isinstance(repo_metadata, dict) or type(repo_metadata.get("manifestVersion")) is not int or repo_metadata["manifestVersion"] != 1:
        raise ReleaseError("repository metadata must use manifestVersion 1")
    if output.exists():
        raise ReleaseError(f"output directory must not already exist: {output}")

    base_url = f"https://raw.githubusercontent.com/{repository}/builds"
    prepared = []
    destinations = set()
    for entry in entries:
        artifact, size, file_hash = validate_artifact(entry, artifacts)
        filename = f"{entry['internalName']}-v{entry['version']}.cs3"
        for destination in (artifact.name, filename):
            if destination in destinations:
                raise ReleaseError(f"duplicate package output: {destination}")
            destinations.add(destination)
        prepared.append((artifact, filename, {
            **entry,
            "url": f"{base_url}/{filename}",
            "repositoryUrl": f"https://github.com/{repository}",
            "fileSize": size,
            "fileHash": file_hash,
        }))

    # Validate every input before creating anything that the workflow can publish.
    output.mkdir(parents=True)
    for artifact, filename, _ in prepared:
        shutil.copy2(artifact, output / artifact.name)
        shutil.copy2(artifact, output / filename)
    catalogue_json = json.dumps([entry for _, _, entry in prepared], indent=2) + "\n"
    repository_json = json.dumps({**repo_metadata, "pluginLists": [f"{base_url}/plugins.json"]}, indent=2) + "\n"
    (output / "plugins.json").write_text(catalogue_json, encoding="utf-8")
    (output / "repo.json").write_text(repository_json, encoding="utf-8")

    # Existing installations may retain either the repository or catalogue URL.
    # Advance every old alias to the latest catalogue; retain old binary versions
    # on the builds branch via the publisher's keep_files option.
    latest_version = max(entry["version"] for _, _, entry in prepared)
    for version in range(1, latest_version + 1):
        (output / f"plugins-v{version}.json").write_text(catalogue_json, encoding="utf-8")
        (output / f"repo-v{version}.json").write_text(repository_json, encoding="utf-8")
    print(f"Prepared {len(prepared)} verified package(s), stable endpoints, and aliases v1–v{latest_version}.")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--plugins", required=True, type=Path)
    parser.add_argument("--artifacts", required=True, type=Path)
    parser.add_argument("--repo", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--repository", required=True)
    args = parser.parse_args()
    try:
        prepare_release(args.plugins, args.artifacts, args.repo, args.output, args.repository)
    except (ReleaseError, OSError) as exc:
        print(f"Release packaging failed: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
