#!/usr/bin/env python3
import json
import os
import sys
import time
from pathlib import Path
from urllib.parse import urljoin

import requests

BASE_URL = "https://ai.usmercari.com"


def main() -> int:
    if len(sys.argv) != 3:
        raise SystemExit("usage: poll_minimax_video.py TASK_ID OUTPUT_PATH")
    task_id, output_name = sys.argv[1], sys.argv[2]
    api_key = os.environ.get("MINIMAX_API_KEY")
    if not api_key:
        raise SystemExit("MINIMAX_API_KEY is not set")
    headers = {"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"}
    output = Path(output_name)
    output.parent.mkdir(parents=True, exist_ok=True)
    last = None
    deadline = time.monotonic() + 1800
    while time.monotonic() < deadline:
        response = requests.post(
            f"{BASE_URL}/cpa-file/fetch",
            headers=headers,
            json={"ids": [task_id]},
            timeout=90,
        )
        response.raise_for_status()
        payload = response.json()
        rows = payload.get("data") or []
        if not rows:
            raise RuntimeError(json.dumps(payload, ensure_ascii=False))
        row = rows[0]
        state = (str(row.get("status", "UNKNOWN")).upper(), str(row.get("progress", "")))
        if state != last:
            print(f"status={state[0]} progress={state[1]}", flush=True)
            last = state
        if state[0] == "FAILURE":
            raise RuntimeError(row.get("fail_reason") or json.dumps(row, ensure_ascii=False))
        if state[0] == "SUCCESS":
            items = ((row.get("data") or {}).get("items") or [])
            products = ((items[0] if items else {}).get("data") or [])
            if not products or not products[0].get("url"):
                raise RuntimeError("SUCCESS response did not contain a video URL")
            url = urljoin(BASE_URL, products[0]["url"])
            with requests.get(url, stream=True, timeout=300) as video:
                video.raise_for_status()
                with output.open("wb") as handle:
                    for chunk in video.iter_content(1024 * 1024):
                        if chunk:
                            handle.write(chunk)
            print(f"downloaded={output.resolve()} bytes={output.stat().st_size}", flush=True)
            return 0
        time.sleep(15)
    raise TimeoutError("task did not finish within 30 minutes")


if __name__ == "__main__":
    raise SystemExit(main())
