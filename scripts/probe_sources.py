#!/usr/bin/env python3
"""Reports which metadata each source really exposes, so parser selectors can be fixed from facts."""

from __future__ import annotations

import json
import re
import sys

from catalogue_config import load_rows, load_sources
from health_check import card_links, card_titles, fetch

FEMALE_APPROX = re.compile(
    r"\b(?:girls?|wom[ae]n|female|milf|mom|mommy|wife|daughter|sister|girlfriend|lesbian|bisexual|pussy|tits|trans|shemale)\b", re.I)


def inspect_detail(page: str) -> dict:
    return {
        "json_ld_video_object": bool(re.search(r'"@type"\s*:\s*"VideoObject"', page)),
        "upload_date": bool(re.search(r'uploadDate|article:published_time|video:release_date|<time[^>]+datetime', page, re.I)),
        "view_count": bool(re.search(r'interactionCount|userInteractionCount|class=["\'][^"\']*views', page, re.I)),
        "tag_links": len(re.findall(r'href=["\'][^"\']*(?:categor|/tags?/)', page, re.I)),
        "performer_links": len(re.findall(r'href=["\'][^"\']*(?:pornstar|/models?/|performer|/stars/)', page, re.I)),
        "gender_metadata": bool(re.search(r'itemprop=["\']gender|actor:gender|"gender"\s*:', page, re.I)),
        "declared_source_tag": bool(re.search(r"<source[^>]+src=", page, re.I)),
        "og_video": bool(re.search(r'og:video', page, re.I)),
    }


def main() -> int:
    bases, rows, report = load_sources(), load_rows(), {}
    for prefix, base in sorted(bases.items()):
        feed = next(((p, path) for row in rows for p, path in row.feeds if p == prefix), None)
        entry: dict = {"base": base}
        try:
            page, _ = fetch(base + feed[1])
            links = card_links(prefix, page, base)
            titles = card_titles(page)
            entry.update(cards=len(links), approx_female_titles=sum(bool(FEMALE_APPROX.search(t)) for t in titles),
                         listing_has_duration=bool(re.search(rb'duration', page, re.I)),
                         listing_has_views=bool(re.search(rb'views', page, re.I)),
                         listing_has_date=bool(re.search(rb'<time|class=["\'][^"\']*(?:added|date|age)', page, re.I)))
            if links and prefix != "GPT":
                detail, _ = fetch(links[0])
                entry["detail"] = inspect_detail(detail.decode("utf-8", "ignore"))
        except Exception as exc:  # noqa: BLE001
            entry["error"] = str(exc)
        report[prefix] = entry
    print(json.dumps(report, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
