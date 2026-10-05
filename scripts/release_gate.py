#!/usr/bin/env python3
"""Refuses to publish unless every release condition holds. Prints every failure, not just the first."""

from __future__ import annotations

import argparse
import json
import re
import sys
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path
from zipfile import ZipFile

MUST_PASS = (
    "every female case is rejected",
    "every lesbian case is rejected",
    "every bisexual or mixed case is rejected",
    "every male only case is accepted",
)


def declared_version(gradle_file: Path) -> int:
    match = re.search(r"^version\s*=\s*(\d+)", gradle_file.read_text(encoding="utf-8"), re.M)
    if not match:
        raise ValueError(f"no version found in {gradle_file}")
    return int(match[1])


def check_versions(plugins: Path, artifacts: Path, gradle_file: Path) -> list[str]:
    failures = []
    source_version = declared_version(gradle_file)
    entries = json.loads(plugins.read_text(encoding="utf-8"))
    for entry in entries:
        name, listed = entry.get("internalName"), entry.get("version")
        if listed != source_version:
            failures.append(f"{name}: plugins.json says v{listed} but build.gradle.kts says v{source_version}")
        package = artifacts / f"{name}.cs3"
        try:
            with ZipFile(package) as zipped:
                embedded = json.loads(zipped.read("manifest.json")).get("version")
        except Exception as exc:  # noqa: BLE001
            failures.append(f"{name}: cannot read embedded manifest ({exc})")
            continue
        if embedded != listed:
            failures.append(f"{name}: embedded .cs3 version {embedded} differs from plugins.json {listed}")
    return failures


def check_tests(results: Path) -> list[str]:
    failures, passed, seen = [], set(), 0
    files = sorted(results.glob("*.xml"))
    if not files:
        return [f"no unit test results found in {results}"]
    for path in files:
        for case in ET.parse(path).getroot().iter("testcase"):
            seen += 1
            broken = case.find("failure") is not None or case.find("error") is not None
            if broken:
                failures.append(f"test failed: {case.get('classname')}.{case.get('name')}")
            else:
                passed.add(case.get("name"))
    for required in MUST_PASS:
        if required not in passed:
            failures.append(f"required content-policy test did not pass or did not run: '{required}'")
    return failures


def resolve_once(url: str) -> str | None:
    """Where a short link redirects, without following it."""
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, *args, **kwargs):
            return None

    opener = urllib.request.build_opener(NoRedirect)
    request = urllib.request.Request(url, method="HEAD", headers={"User-Agent": "brostream-release-gate"})
    try:
        opener.open(request, timeout=20)
    except urllib.error.HTTPError as error:
        return error.headers.get("Location")
    return None


def check_links(config: Path, resolve=resolve_once) -> tuple[list[str], list[str]]:
    data = json.loads(config.read_text(encoding="utf-8"))
    short = data.get("tinyurl")
    if not short:
        return [], ["TinyURL is not configured in release/links.json; short-link check skipped"]
    target = resolve(short["url"])
    if target != short["expected"]:
        return [f"TinyURL {short['url']} points to {target!r}, expected {short['expected']!r}"], []
    return [], [f"TinyURL {short['url']} points to the repository link"]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--plugins", type=Path, required=True)
    parser.add_argument("--artifacts", type=Path, required=True)
    parser.add_argument("--gradle", type=Path, default=Path("BroStream/build.gradle.kts"))
    parser.add_argument("--tests", type=Path, default=Path("BroStream/build/test-results/testDebugUnitTest"))
    parser.add_argument("--links", type=Path, default=Path("release/links.json"))
    parser.add_argument("--offline", action="store_true", help="skip live site and short-link checks")
    args = parser.parse_args()

    failures, notes = [], []
    for step, run in (
        ("versions", lambda: check_versions(args.plugins, args.artifacts, args.gradle)),
        ("content-policy tests", lambda: check_tests(args.tests)),
    ):
        try:
            failures += run()
        except Exception as exc:  # noqa: BLE001
            failures.append(f"{step}: {exc}")
    if not args.offline:
        try:
            link_failures, link_notes = check_links(args.links)
            failures += link_failures
            notes += link_notes
        except Exception as exc:  # noqa: BLE001
            failures.append(f"short link: {exc}")
        from health_check import run_live
        try:
            live_failures, live_notes = run_live()
            failures += live_failures
            notes += live_notes
        except Exception as exc:  # noqa: BLE001
            failures.append(f"live checks: {exc}")
    for note in notes:
        print(f"INFO {note}")
    for failure in failures:
        print(f"FAIL {failure}", file=sys.stderr)
    if failures:
        print(f"RELEASE REFUSED: {len(failures)} problem(s).", file=sys.stderr)
        return 1
    print("Release gate passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
