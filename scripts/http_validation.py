"""Bounded checks for initial media bytes; this does not decode or play a file."""

from __future__ import annotations

import re
from collections.abc import Mapping


PROBE_BYTES = 1024
MIN_MEDIA_BYTES = 512
MEDIA_TYPES = {
    "video/mp4", "application/mp4", "video/x-m4v", "video/quicktime",
    "video/webm", "application/octet-stream", "binary/octet-stream",
}


class MediaProbeError(ValueError):
    """The response is not a consistent initial media-byte sample."""


def media_signature(body: bytes) -> str:
    if body.startswith(b"\x1a\x45\xdf\xa3"):
        return "WebM/EBML"
    if body[4:8] == b"ftyp":
        box_size = int.from_bytes(body[:4], "big")
        header_size = 8
        if box_size == 1:
            box_size = int.from_bytes(body[8:16], "big")
            header_size = 16
        # A complete file-type box contains a major brand and minor version,
        # followed by zero or more four-byte compatible brands.
        if header_size + 8 <= box_size <= len(body) and (box_size - header_size) % 4 == 0:
            return "ISO-BMFF"
    raise MediaProbeError("response lacks a complete initial MP4/ISO-BMFF or WebM signature")


def validate_media_probe(status: int, headers: Mapping[str, str], body: bytes) -> str:
    """Validate a response to Range: bytes=0-1023 and return an honest summary."""
    if status not in (200, 206):
        raise MediaProbeError(f"unexpected media response HTTP {status}")
    fields = {key.lower(): value.strip() for key, value in headers.items()}
    content_type = fields.get("content-type", "").split(";", 1)[0].strip().lower()
    if content_type not in MEDIA_TYPES:
        raise MediaProbeError(f"unsupported or missing media Content-Type: {content_type or '(missing)'}")
    if fields.get("content-encoding", "identity").lower() != "identity":
        raise MediaProbeError("media probe must use an uncompressed response")
    if not MIN_MEDIA_BYTES <= len(body) <= PROBE_BYTES:
        raise MediaProbeError(f"media probe must contain {MIN_MEDIA_BYTES}–{PROBE_BYTES} bytes")

    length = None
    if "content-length" in fields:
        if not re.fullmatch(r"[0-9]+", fields["content-length"]):
            raise MediaProbeError("invalid media Content-Length")
        length = int(fields["content-length"])
    expected = min(length, PROBE_BYTES) if length is not None else PROBE_BYTES
    range_summary = "HTTP 200; server ignored Range"

    if status == 206:
        content_range = fields.get("content-range")
        range_summary = "HTTP 206; Content-Range absent, range unverified"
        if length is not None and length > PROBE_BYTES:
            raise MediaProbeError("partial response exceeds the requested byte range")
        if content_range is not None:
            match = re.fullmatch(r"bytes ([0-9]+)-([0-9]+)/([0-9]+|\*)", content_range, re.I)
            if not match:
                raise MediaProbeError("invalid media Content-Range")
            start, end = int(match[1]), int(match[2])
            total = None if match[3] == "*" else int(match[3])
            if start != 0 or end < start or end >= PROBE_BYTES:
                raise MediaProbeError("Content-Range does not match the requested initial bytes")
            if total is not None and (total <= end or end != min(PROBE_BYTES, total) - 1):
                raise MediaProbeError("Content-Range has an inconsistent total or truncated range")
            expected = end - start + 1
            if length is not None and length != expected:
                raise MediaProbeError("Content-Length disagrees with Content-Range")
            range_summary = f"HTTP 206; verified bytes {start}-{end}"
    if len(body) != expected:
        raise MediaProbeError(f"truncated or inconsistent media body: expected {expected}, received {len(body)} bytes")

    signature = media_signature(body)
    if (content_type == "video/webm" and signature != "WebM/EBML") or (
        content_type in {"video/mp4", "application/mp4", "video/x-m4v", "video/quicktime"}
        and signature != "ISO-BMFF"
    ):
        raise MediaProbeError("media Content-Type disagrees with the file signature")
    return f"{signature} header; {range_summary}"


def read_media_probe(response) -> tuple[bytes, str]:
    """Never read more than the requested probe, even when a server ignores Range."""
    body = response.read(PROBE_BYTES)
    return body, validate_media_probe(response.status, response.headers, body)
