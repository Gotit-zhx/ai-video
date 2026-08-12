#!/usr/bin/env python3
"""Run the documented SSE video endpoint with three public reference URLs."""

from __future__ import annotations

import json
import os
import sys
from pathlib import Path
from urllib.parse import urljoin

import requests

from generate_minimax_h3_vertical import PROMPT_FULL


BASE_URL = "https://ai.usmercari.com"
REFERENCE_URLS = [
    "https://tempfile.org/pTb4JMzThBv/download",
    "https://tempfile.org/M86SMjQos6H/download",
    "https://tempfile.org/59A6nyFA3h6/download",
]


def main() -> int:
    api_key = os.environ.get("MINIMAX_API_KEY")
    if not api_key:
        raise SystemExit("MINIMAX_API_KEY is not set")

    output_dir = Path(__file__).resolve().parents[1] / "minimax_h3_stream_output"
    output_dir.mkdir(parents=True, exist_ok=True)
    events_path = output_dir / "events.jsonl"
    manifest_path = output_dir / "manifest.json"
    video_path = output_dir / "minimax-h3-reference-vertical-12s.mp4"

    payload = {
        "model": "MiniMax H3",
        "prompt": PROMPT_FULL,
        "duration": 12,
        "image_urls": REFERENCE_URLS,
        "metadata": {
            "aspect_ratio": "9:16",
            "audio": False,
        },
    }
    manifest = {
        "endpoint": f"{BASE_URL}/cpa-file/stream/video",
        "request": payload,
        "status": "SUBMITTING",
        "task_id": None,
        "output": None,
    }
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")

    headers = {
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json",
        "Accept": "text/event-stream",
    }
    with requests.post(
        f"{BASE_URL}/cpa-file/stream/video",
        headers=headers,
        json=payload,
        stream=True,
        timeout=(60, 1800),
    ) as response:
        if not response.ok:
            raise RuntimeError(f"HTTP {response.status_code}: {response.text[:1000]}")
        with events_path.open("w", encoding="utf-8") as events:
            for raw_line in response.iter_lines(chunk_size=1, decode_unicode=True):
                if not raw_line or raw_line.startswith(":"):
                    continue
                if not raw_line.startswith("data:"):
                    continue
                text = raw_line[5:].strip()
                if text == "[DONE]":
                    continue
                try:
                    event = json.loads(text)
                except json.JSONDecodeError:
                    print(f"unparsed SSE: {text}", flush=True)
                    continue
                events.write(json.dumps(event, ensure_ascii=False) + "\n")
                events.flush()
                event_type = event.get("type")
                task_id = event.get("task_id")
                if task_id:
                    manifest["task_id"] = task_id
                manifest["status"] = str(event.get("status") or event_type or "UNKNOWN").upper()
                manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
                print(
                    f"type={event_type} status={event.get('status')} "
                    f"progress={event.get('progress', '')} task_id={task_id or manifest['task_id']}",
                    flush=True,
                )
                if event_type == "failed":
                    manifest["fail_reason"] = event.get("fail_reason")
                    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
                    return 2
                if event_type == "done":
                    result_url = urljoin(BASE_URL, event["url"])
                    with requests.get(result_url, stream=True, timeout=300) as video_response:
                        video_response.raise_for_status()
                        with video_path.open("wb") as destination:
                            for chunk in video_response.iter_content(1024 * 1024):
                                if chunk:
                                    destination.write(chunk)
                    manifest.update(
                        {
                            "status": "SUCCESS",
                            "url": result_url,
                            "credits_used": event.get("credits_used"),
                            "output": str(video_path),
                        }
                    )
                    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
                    print(f"DONE: {video_path}", flush=True)
                    return 0

    manifest["status"] = "STREAM_ENDED_WITHOUT_RESULT"
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    return 3


if __name__ == "__main__":
    sys.exit(main())
