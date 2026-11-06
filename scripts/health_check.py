#!/usr/bin/env python3
"""Release gate for the official catalogue and live feeds used by the homepage."""

from __future__ import annotations

import json
import sys
import urllib.request

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0 Safari/537.36"


def fetch_json(url: str) -> object:
    request = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "application/json"})
    with urllib.request.urlopen(request, timeout=25) as response:
        if response.status != 200:
            raise RuntimeError(f"HTTP {response.status} for {url}")
        body = response.read(2_000_000)
        return json.loads(body)


def check_chaturbate() -> None:
    payload = fetch_json(
        "https://chaturbate.com/api/public/affiliates/onlinerooms/"
        "?format=json&wm=0esJu&client_ip=request_ip&gender=m&limit=20&offset=0"
    )
    rooms = payload.get("results") if isinstance(payload, dict) else payload
    if not isinstance(rooms, list):
        raise RuntimeError("Chaturbate: response has no results list")
    male = [room for room in rooms if isinstance(room, dict) and room.get("gender") in (None, "m") and room.get("username")]
    if len(male) < 10:
        raise RuntimeError(f"Chaturbate: only {len(male)} male rooms")
    print(f"PASS Chaturbate male rooms: {len(male)}")


def check_stripchat() -> None:
    payload = fetch_json(
        "https://go.rmhfrtnd.com/api/models?tag=men&sortBy=viewersCount&limit=20&offset=0"
        "&fields=id,username,gender,status,stream"
    )
    models = payload.get("models") if isinstance(payload, dict) else None
    if not isinstance(models, list):
        raise RuntimeError("Stripchat: response has no models list")
    male = []
    for model in models:
        if not isinstance(model, dict):
            continue
        if str(model.get("gender", "")).lower() not in {"male", "males"}:
            continue
        stream = model.get("stream") if isinstance(model.get("stream"), dict) else {}
        url = stream.get("url") if isinstance(stream, dict) else None
        if model.get("username") and isinstance(url, str) and url.startswith("http"):
            male.append(model)
    if len(male) < 5:
        raise RuntimeError(f"Stripchat: only {len(male)} male rooms with a stream url")
    print(f"PASS Stripchat male rooms with stream urls: {len(male)}")


def check_eporner() -> None:
    payload = fetch_json(
        "https://www.eporner.com/api/v2/video/search/"
        "?gay=2&lq=0&format=json&thumbsize=medium&per_page=20&page=1&order=top-weekly&query=gay"
    )
    videos = payload.get("videos") if isinstance(payload, dict) else None
    if not isinstance(videos, list):
        raise RuntimeError("Eporner: response has no videos list")
    kept = [
        video for video in videos
        if isinstance(video, dict)
        and video.get("title")
        and isinstance(video.get("embed"), str)
        and video["embed"].startswith("http")
    ]
    if len(kept) < 10:
        raise RuntimeError(f"Eporner: only {len(kept)} gay videos with embed urls")
    print(f"PASS Eporner gay videos: {len(kept)}")


def main() -> int:
    checks = (check_chaturbate, check_stripchat, check_eporner)
    try:
        for check in checks:
            check()
        print(f"Release probe passed: {len(checks)} official feeds returned catalogue items.")
        return 0
    except Exception as exc:
        print(f"RELEASE GATE FAILED: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
