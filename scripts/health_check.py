#!/usr/bin/env python3
"""Live check of every visible category: listing, first poster, detail page and a byte-range video request."""

from __future__ import annotations

import html
import json
import re
import sys
import unicodedata
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from urllib.parse import urljoin

from catalogue_config import Row, feed_url, load_rows, load_sources
from http_validation import PROBE_BYTES, read_media_probe

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0 Safari/537.36"
DUPLICATE_THRESHOLD = 0.35

CARD = {
    "MP": re.compile(rb'href=["\']([^"\']*/videos/[0-9]+/[^"\']*)', re.I),
    "GV": re.compile(rb'href=["\']([^"\']*/videos/[0-9]+/[^"\']*)', re.I),
    "GPT": re.compile(rb'data-video-id=["\']([0-9]+)', re.I),
}
STUDIO_PREFIX = re.compile(r"^\s*(?:\[[^\]]{1,40}\]|\([^)]{1,40}\))\s*")
SEPARATOR_PREFIX = re.compile(r"^\s*[^-–—|:]{2,30}?\s+[-–—|:]\s+")
LABELS = re.compile(r"\b(?:4k|8k|2160p|1440p|1080p|720p|480p|360p|uhd|full hd|hd|full video|full movie)\b")


def fetch(url: str, referer: str | None = None, byte_range: bool = False, limit: int = 3_000_000):
    headers = {"User-Agent": UA, "Accept-Encoding": "identity"}
    if referer:
        headers["Referer"] = referer
    if byte_range:
        headers["Range"] = f"bytes=0-{PROBE_BYTES - 1}"
    request = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(request, timeout=25) as response:
        if response.status not in (200, 206):
            raise RuntimeError(f"HTTP {response.status} for {url}")
        if byte_range:
            return read_media_probe(response)
        return response.read(limit), response.headers.get("Content-Type", "")


def normalize_title(title: str) -> str:
    """Same idea as the Kotlin Titles.normalize: no studio prefix, resolution label or punctuation."""
    text = unicodedata.normalize("NFKC", title).lower()
    for _ in range(2):
        text = STUDIO_PREFIX.sub("", text)
    stripped = SEPARATOR_PREFIX.sub("", text)
    if stripped != text and len(re.findall(r"\w+", stripped)) >= 2:
        text = stripped
    return " ".join(re.findall(r"[^\W_]+", LABELS.sub(" ", text)))


def duplicate_ratio(titles: list[str]) -> float:
    keys = [key for key in (normalize_title(t) for t in titles) if key]
    return 0.0 if not keys else 1 - len(set(keys)) / len(keys)


def card_links(prefix: str, page: bytes, base: str) -> list[str]:
    seen: dict[str, None] = {}
    for match in CARD[prefix].finditer(page):
        value = html.unescape(match.group(1).decode("utf-8", "ignore"))
        seen.setdefault(urljoin(base, value) if prefix != "GPT" else value, None)
    return list(seen)


def card_titles(page: bytes) -> list[str]:
    return [html.unescape(x.decode("utf-8", "ignore")) for x in re.findall(rb'(?:title|alt)=["\']([^"\']{6,200})', page, re.I)]


def first_poster(page: bytes, base: str) -> str | None:
    match = re.search(rb'<img[^>]+(?:data-original|data-src|src)=["\']([^"\']+\.(?:jpe?g|png|webp)[^"\']*)', page, re.I)
    return urljoin(base, html.unescape(match.group(1).decode("utf-8", "ignore"))) if match else None


def extract_streams(page: str, page_url: str) -> list[str]:
    """Declared <source> and structured data first; the broad .mp4 regex only when they found nothing."""
    def clean(value: str) -> str:
        return urljoin(page_url, html.unescape(value).replace("\\u0026", "&").replace("\\/", "/").strip())

    declared = [clean(m) for m in re.findall(r'<source[^>]+src=["\']([^"\']+)', page, re.I)]
    declared += [clean(m) for m in re.findall(r'<video[^>]+src=["\']([^"\']+)', page, re.I)]
    structured = [clean(m) for m in re.findall(r'"contentUrl"\s*:\s*"([^"]+)"', page)]
    structured += [clean(m) for m in re.findall(r'<meta[^>]+property=["\']og:video(?::url|:secure_url)?["\'][^>]+content=["\']([^"\']+)', page, re.I)]
    found = [u for u in declared + structured if re.search(r"\.(?:mp4|m3u8)", u, re.I)]
    if not found:
        found = [clean(m) for m in re.findall(r'https?[^"\'\s<>]+?\.mp4[^"\'\s<>]*', page, re.I)]
        found = [u for u in found if ".mp4.jpg" not in u]
    seen, result = set(), []
    for url in found:
        if url.split("?")[0] not in seen:
            seen.add(url.split("?")[0])
            result.append(url)
    return result


def evaluate_rows(rows: list[Row], counts: dict[tuple[str, str], int]) -> list[str]:
    """A row is empty when its feeds together show fewer cards than the row's own minimum."""
    failures = []
    for row in rows:
        total = sum(counts.get(feed, 0) for feed in row.feeds)
        if total < row.min_items:
            failures.append(f"category '{row.title}' would be empty: {total} cards (needs {row.min_items})")
    return failures


def check_poster(url: str | None, label: str) -> str | None:
    if not url:
        return f"{label}: no poster image found on the first card"
    try:
        _, content_type = fetch(url, limit=2048)
    except Exception as exc:  # noqa: BLE001 - report any network failure as a gate failure
        return f"{label}: poster request failed ({exc})"
    return None if content_type.lower().startswith("image/") else f"{label}: poster is {content_type or 'untyped'}, not an image"


def check_playback(prefix: str, detail_url: str) -> str | None:
    try:
        page, _ = fetch(detail_url)
        streams = extract_streams(page.decode("utf-8", "ignore"), detail_url)
        if not streams:
            return f"{prefix}: no stream found on {detail_url}"
        playable = [u for u in streams if ".m3u8" not in u]
        if not playable:
            return None  # adaptive playlist only; byte-range probe applies to MP4
        fetch(playable[0], referer=detail_url, byte_range=True)
    except Exception as exc:  # noqa: BLE001
        return f"{prefix}: playback check failed on {detail_url} ({exc})"
    return None


def run_live() -> tuple[list[str], list[str]]:
    """Returns (failures, notes). Every category, one poster, one detail page and one range request per source."""
    rows, bases = load_rows(), load_sources()
    feeds = sorted({feed for row in rows for feed in row.feeds})
    unknown = [p for p, _ in feeds if p not in bases]
    if unknown:
        return [f"rows use unknown source prefixes: {sorted(set(unknown))}"], []

    def load(feed):
        prefix, path = feed
        base = bases[prefix]
        try:
            page, _ = fetch(feed_url(base, prefix, path))
        except Exception as exc:  # noqa: BLE001
            return feed, None, f"{prefix}{path}: {exc}"
        return feed, page, None

    with ThreadPoolExecutor(max_workers=8) as pool:
        loaded = list(pool.map(load, feeds))
    failures, notes, counts, pages = [], [], {}, {}
    for feed, page, error in loaded:
        if error:
            failures.append(f"feed unreachable: {error}")
            continue
        links = card_links(feed[0], page, bases[feed[0]])
        counts[feed], pages[feed] = len(links), (page, links)
    failures += evaluate_rows(rows, counts)

    titles = [t for page, _ in (v for v in pages.values()) for t in card_titles(page)]
    ratio = duplicate_ratio(titles)
    notes.append(f"duplicate ratio across sampled titles: {ratio:.2f} (limit {DUPLICATE_THRESHOLD})")
    if ratio > DUPLICATE_THRESHOLD:
        failures.append(f"duplicate level {ratio:.2f} exceeds {DUPLICATE_THRESHOLD}")

    for prefix in sorted(bases):
        sample = next(((f, v) for f, v in pages.items() if f[0] == prefix and v[1]), None)
        if sample is None:
            failures.append(f"{prefix}: no feed produced any card")
            continue
        feed, (page, links) = sample
        if (problem := check_poster(first_poster(page, bases[prefix]), prefix)):
            failures.append(problem)
        detail = links[0] if prefix != "GPT" else None
        if detail is None:
            match = re.search(rb'href=["\'](https?[^"\']+/video[^"\']*)', page, re.I)
            detail = html.unescape(match.group(1).decode()) if match else None
        if detail is None:
            failures.append(f"{prefix}: no detail page link found")
            continue
        if (problem := check_playback(prefix, detail)):
            failures.append(problem)
        else:
            notes.append(f"{prefix}: detail page, stream and byte-range request passed")
    return failures, notes


def main() -> int:
    try:
        failures, notes = run_live()
    except Exception as exc:  # noqa: BLE001
        print(f"HEALTH CHECK ERROR: {exc}", file=sys.stderr)
        return 1
    for note in notes:
        print(f"INFO {note}")
    for failure in failures:
        print(f"FAIL {failure}", file=sys.stderr)
    if failures:
        return 1
    print("All categories, posters, detail pages and byte-range requests passed. Playback decoding is not verified.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
