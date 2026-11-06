#!/usr/bin/env python3
"""Call the documented synchronous video endpoint with public reference URLs."""

from __future__ import annotations

import json
import os
import sys
from pathlib import Path
from urllib.parse import urljoin

import requests

from generate_minimax_h3_vertical import PROMPT_FULL


BASE_URL = "https://ai.usmercari.com"
ENDPOINT = f"{BASE_URL}/v1/videos/generations"
REFERENCE_URLS = [
    "https://tempfile.org/pTb4JMzThBv/download",
    "https://tempfile.org/M86SMjQos6H/download",
    "https://tempfile.org/59A6nyFA3h6/download",
]


def main() -> int:
    api_key = os.environ.get("MINIMAX_API_KEY")
    if not api_key:
        raise SystemExit("MINIMAX_API_KEY is not set")

    payload = {
        "model": "MiniMax H3",
        "prompt": PROMPT_FULL,
        "duration": 15,
        "image_urls": REFERENCE_URLS,
        "aspect_ratio": "9:16",
        "resolution": "768p",
        "audio": False,
    }
    output_dir = Path(__file__).resolve().parents[3] / "minimax_h3_direct_output"
    output_dir.mkdir(parents=True, exist_ok=True)
    manifest_path = output_dir / "manifest.json"
    manifest = {"endpoint": ENDPOINT, "request": payload, "status": "SUBMITTING", "output": None}
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")

    response = requests.post(
        ENDPOINT,
        headers={"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"},
        json=payload,
        timeout=(60, 1800),
    )
    try:
        data = response.json()
    except ValueError:
        data = {"raw_response": response.text[:5000]}
    if not response.ok:
        manifest.update({"status": "HTTP_ERROR", "http_status": response.status_code, "response": data})
        manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps(data, ensure_ascii=False), flush=True)
        return 2

    manifest.update({"status": "SUCCESS", "response": data})
    urls: list[str] = []
    if isinstance(data, dict):
        if isinstance(data.get("url"), str):
            urls.append(data["url"])
        for item in data.get("data") or []:
            if isinstance(item, dict) and isinstance(item.get("url"), str):
                urls.append(item["url"])
    if urls:
        video_url = urljoin(BASE_URL, urls[0])
        video_path = output_dir / "minimax-h3-reference-vertical-15s.mp4"
        with requests.get(video_url, stream=True, timeout=300) as video_response:
            video_response.raise_for_status()
            with video_path.open("wb") as destination:
                for chunk in video_response.iter_content(1024 * 1024):
                    if chunk:
                        destination.write(chunk)
        manifest.update({"url": video_url, "output": str(video_path)})
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(data, ensure_ascii=False), flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
