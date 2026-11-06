#!/usr/bin/env python3
"""Generate a 12-second multi-shot reference video with a BGM reference."""

from __future__ import annotations

import base64
import json
import os
import sys
from pathlib import Path
from urllib.parse import urljoin

import requests


BASE_URL = "https://ai.usmercari.com"
BGM_REFERENCE = "https://tempfile.org/YQmQBKhY8AY/download"
REFERENCE_URLS = [
    "https://tempfile.org/nLTG5XLV1LY/download",
    "https://tempfile.org/doc8ZVQ8wuy/download",
    "https://tempfile.org/u9V7EA1mCtz/download",
]
REFERENCE_IMAGES = [
    Path(r"C:\Users\yingdao\Downloads\素材图片-分镜首帧 (4).png"),
    Path(r"C:\Users\yingdao\Downloads\素材图片-4K分镜九宫格 (5).png"),
    Path(r"C:\Users\yingdao\Documents\xwechat_files\wxid_9ihacmb2vuk622_4df0\msg\file\2026-08\AI\AI\素材图片.png"),
]

PROMPT = """12秒竖屏写实多镜头电商短片。参考图1只锚定第01镜的同一位成年女性、脸、丸子头、身材、米白印花T恤、牛仔短裤与初始姿势，不得用图1背景覆盖后续镜头。参考图2九格从左到右、从上到下严格对应01至09，每格分别决定动作、场景、构图、景别和机位。参考图3只校验T恤颜色、版型、圆领、袖长及胸前QNW Worldwide、2026、四只彩色小狗与下方英文印花的内容、位置和比例。所有有人镜头始终同一人同一套穿搭，每镜最多1人；纯商品镜头可无人。同场景自然连续；换景、站坐切换、人物与商品特写切换必须干净硬切，不得人体变形、背景融化或强行走位。
01 0-1.36秒：居家杂物空间竖屏中景，正面站立，双手捏住T恤两侧上缘，小幅向外并上提，拉平印花，缓慢推近。
02 1.36-2.63秒：承接上镜上半身近景，衣服稳定居中，头微前探，眼睛睁大、自然张嘴惊喜。
硬切。03 2.63-3.99秒：整洁客厅中景，已穿T恤，双手背后，头回正并稳定微笑，展示正面版型。
硬切。04 3.99-5.35秒：胸前超近景，聚焦印花与面料，右手从右侧进入，捏住下摆向右下轻拉2-4厘米。
硬切。05 5.35-6.70秒：整洁客厅全身偏中景，人物侧身向左走1-2小步，右手逐渐抬到锁骨附近，展示侧面版型。
硬切。06 6.70-7.98秒：米色沙发坐姿中景，左右红色抱枕，双腿交叠，双手将浅色开衫向两侧拉开，露出T恤印花。
硬切。07 7.98-9.34秒：正面站立近景，双手捏住圆领两侧轻提1-2厘米并外拉，领口平整，稳定微笑。
硬切。08 9.34-10.69秒：木桌平铺特写，折叠米白T恤居中，右手将棕色吊牌与标签放到衣服右下侧后撤出。
硬切。09 10.69-12秒：正面站立中景，左手背后，右手先在脸侧点赞，随后前臂下移、手腕内旋改为食指向下，保持微笑。
每镜必须真实出现，不跳格。禁止九宫格、拼贴、格线、编号、第二人物、复制、分身、镜像、残影、换脸、换发型、换装、增生肢体、关节反折、穿模、背景融化、字幕、额外Logo和水印。印花只能附着在T恤布料表面。"""


def image_data_uri(path: Path) -> str:
    mime = "image/png" if path.suffix.lower() == ".png" else "image/jpeg"
    encoded = base64.b64encode(path.read_bytes()).decode("ascii")
    return f"data:{mime};base64,{encoded}"


def main() -> int:
    api_key = os.environ.get("MINIMAX_API_KEY")
    if not api_key:
        raise SystemExit("MINIMAX_API_KEY is not set")
    for image_path in REFERENCE_IMAGES:
        if not image_path.is_file():
            raise SystemExit(f"reference image not found: {image_path}")

    output_dir = Path(__file__).resolve().parents[3] / "minimax_h3_storyboard_bgm_output"
    output_dir.mkdir(parents=True, exist_ok=True)
    events_path = output_dir / "events.jsonl"
    manifest_path = output_dir / "manifest.json"
    no_audio = os.environ.get("MINIMAX_NO_AUDIO") == "1"
    video_path = output_dir / (
        "minimax-h3-storyboard-silent-12s.mp4" if no_audio
        else "minimax-h3-storyboard-bgm-12s.mp4"
    )

    payload = {
        "model": "MiniMax H3",
        "prompt": PROMPT,
        "duration": 12,
        "image_urls": REFERENCE_URLS,
        "metadata": {
            "aspect_ratio": "9:16",
            # Keep the successful request's native-audio switch off.
            # The supplied BGM is passed separately through audio_reference.
            "audio": False,
        },
    }
    if not no_audio:
        payload["audio_reference"] = BGM_REFERENCE
    manifest = {
        "endpoint": f"{BASE_URL}/cpa-file/stream/video",
        "request": {
            "model": payload["model"],
            "prompt": payload["prompt"],
            "duration": payload["duration"],
            "reference_images": [str(path) for path in REFERENCE_IMAGES],
            "audio_reference": None if no_audio else BGM_REFERENCE,
            "metadata": payload["metadata"],
        },
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
        timeout=(180, 1800),
    ) as response:
        if not response.ok:
            raise RuntimeError(f"HTTP {response.status_code}: {response.text[:2000]}")
        with events_path.open("w", encoding="utf-8") as events:
            for raw_line in response.iter_lines(chunk_size=1024, decode_unicode=False):
                if not raw_line or raw_line.startswith(b":"):
                    continue
                if not raw_line.startswith(b"data:"):
                    continue
                text = raw_line[5:].strip().decode("utf-8", errors="strict")
                if text == "[DONE]":
                    continue
                event = json.loads(text)
                events.write(json.dumps(event, ensure_ascii=False) + "\n")
                events.flush()
                if event.get("task_id"):
                    manifest["task_id"] = event["task_id"]
                manifest["status"] = str(event.get("status") or event.get("type") or "UNKNOWN").upper()
                manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
                print(
                    f"type={event.get('type')} status={event.get('status')} "
                    f"progress={event.get('progress', '')} task_id={manifest['task_id']}",
                    flush=True,
                )
                if event.get("type") == "failed" or event.get("status") == "failed":
                    manifest["fail_reason"] = event.get("fail_reason")
                    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
                    return 2
                if event.get("type") == "done" or event.get("status") == "success":
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
