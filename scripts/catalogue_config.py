"""Reads the category table and source base URLs straight from the Kotlin sources, so checks never drift."""

from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PACKAGE = ROOT / "BroStream/src/main/kotlin/com/brostream"
DEFAULT_MIN_ITEMS = 8


@dataclass(frozen=True)
class Row:
    key: str
    title: str
    feeds: tuple[tuple[str, str], ...]
    min_items: int
    windowed: bool


def load_rows(rows_file: Path = PACKAGE / "Rows.kt") -> list[Row]:
    text = rows_file.read_text(encoding="utf-8")
    body = text[text.index("val all"):]
    rows = []
    for chunk in body.split("CategoryRow(\"")[1:]:
        chunk = '"' + chunk
        head = re.match(r'"([^"]+)",\s*"([^"]+)"', chunk)
        if not head:
            raise ValueError(f"cannot read a category row: {chunk[:60]!r}")
        feeds = tuple(re.findall(r'feed\("(\w+)",\s*"([^"]+)"\)', chunk))
        if not feeds:
            raise ValueError(f"row {head[1]} has no feeds")
        minimum = re.search(r"minItems\s*=\s*(\d+)", chunk)
        rows.append(Row(head[1], head[2], feeds, int(minimum[1]) if minimum else DEFAULT_MIN_ITEMS,
                        "windowMillis" in chunk))
    if not rows:
        raise ValueError("no category rows found")
    return rows


def load_sources(directory: Path = PACKAGE / "sources") -> dict[str, str]:
    """Maps each source prefix (MP, GV, GPT) to its fixed base URL."""
    result = {}
    for path in sorted(directory.glob("*Source.kt")):
        text = path.read_text(encoding="utf-8")
        prefix = re.search(r'override val prefix = "(\w+)"', text)
        base = re.search(r'const val BASE_URL = "([^"]+)"', text)
        if prefix and base:
            result[prefix[1]] = base[1]
    if not result:
        raise ValueError("no sources found")
    return result


def feed_url(base: str, prefix: str, path: str) -> str:
    """First-page URL for a feed, mirroring each Kotlin source's pageUrl for page 1."""
    return f"{base}{path}"
