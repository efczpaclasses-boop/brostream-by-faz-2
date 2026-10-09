#!/usr/bin/env python3
"""Reports what each source really exposes (with small HTML samples), so parser selectors are fixed from facts."""

from __future__ import annotations

import json
import re
import sys
import urllib.request

from catalogue_config import load_rows, load_sources
from health_check import UA, card_links, card_titles, detail_links, extract_streams, fetch

FEMALE_APPROX = re.compile(
    r"\b(?:girls?|wom[ae]n|female|milf|mom|mommy|wife|daughter|sister|girlfriend|lesbian|bisexual|pussy|tits|trans|shemale)\b", re.I)
CARD_MARK = {"MP": rb'class=["\'][^"\']*thumb', "GV": rb'list-videos', "GPT": rb'data-video-id'}


def squeeze(text: str, limit: int) -> str:
    return re.sub(r"\s+", " ", text)[:limit]


def sample_listing(prefix: str, page: bytes) -> str:
    match = re.search(CARD_MARK[prefix], page, re.I)
    if not match:
        return ""
    start = max(0, match.start() - 80)
    return squeeze(page[start:start + 2400].decode("utf-8", "ignore"), 2000)


def anchors(page: str, pattern: str, limit: int = 6) -> list[str]:
    found = re.findall(r"<a\b[^>]*" + pattern + r"[^>]*>.*?</a>", page, re.I | re.S)
    return [squeeze(x, 220) for x in found[:limit]]


def window(page: str, pattern: str, size: int) -> str | None:
    match = re.search(pattern, page, re.I)
    return squeeze(page[match.start():match.start() + size * 3], size) if match else None


def sample_detail(page: str) -> dict:
    ld = re.search(r'<script[^>]+application/ld\+json[^>]*>(.*?)</script>', page, re.I | re.S)
    return {
        "meta_keywords": re.findall(r'<meta[^>]+name=["\']keywords["\'][^>]*>', page, re.I)[:1],
        "meta_description": re.findall(r'<meta[^>]+name=["\']description["\'][^>]*>', page, re.I)[:1],
        "h1_window": window(page, r"<h1", 2600),
        "player_window": window(page, r"video_url", 700),
        "json_ld": squeeze(ld.group(1), 1800) if ld else None,
        "og_video": re.findall(r'<meta[^>]+og:video[^>]*>', page, re.I)[:4],
        "source_tags": [squeeze(x, 200) for x in re.findall(r"<source[^>]*>", page, re.I)[:6]],
        "video_tags": [squeeze(x, 200) for x in re.findall(r"<video[^>]*>", page, re.I)[:3]],
        "tag_anchors": anchors(page, r'href=["\'][^"\']*(?:categor|/tags?/)'),
        "performer_anchors": anchors(page, r'href=["\'][^"\']*(?:pornstar|/models?/|performer|/stars/)'),
        "time_elements": [squeeze(x, 160) for x in re.findall(r"<time[^>]*>.*?</time>", page, re.I | re.S)[:3]],
        "views_lines": [squeeze(x, 160) for x in re.findall(r"[^<>]{0,40}(?:views|Views)[^<>]{0,40}", page)[:3]],
        "flashvars_or_player": [squeeze(x, 240) for x in re.findall(r"(?:flashvars|video_url|video_alt_url|file)\s*[:=]\s*['\"][^'\"]{10,}", page)[:6]],
    }


def probe_stream(url: str, referer: str) -> dict:
    request = urllib.request.Request(url, headers={"User-Agent": UA, "Referer": referer, "Range": "bytes=0-31", "Accept-Encoding": "identity"})
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            return {"url": url[:200], "status": response.status, "type": response.headers.get("Content-Type"),
                    "range": response.headers.get("Content-Range"), "head_hex": response.read(32).hex()}
    except Exception as exc:  # noqa: BLE001
        return {"url": url[:200], "error": str(exc)}


def main() -> int:
    bases, rows, report = load_sources(), load_rows(), {}
    for prefix, base in sorted(bases.items()):
        feed = next(((p, path) for row in rows for p, path in row.feeds if p == prefix), None)
        entry: dict = {"base": base}
        try:
            page, _ = fetch(base + feed[1])
            titles = card_titles(page)
            entry.update(cards=len(card_links(prefix, page, base)), detail_links=len(detail_links(prefix, page, base)),
                         approx_female_titles=sum(bool(FEMALE_APPROX.search(t)) for t in titles),
                         listing_card_sample=sample_listing(prefix, page))
            links = detail_links(prefix, page, base)
            if links:
                detail, _ = fetch(links[0])
                text = detail.decode("utf-8", "ignore")
                entry["detail_url"] = links[0]
                entry["detail"] = sample_detail(text)
                streams = extract_streams(text, links[0])
                entry["streams_found"] = [probe_stream(u, links[0]) for u in streams[:5]]
        except Exception as exc:  # noqa: BLE001
            entry["error"] = str(exc)
        report[prefix] = entry
    print(json.dumps(report, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
