#!/usr/bin/env python3
"""Generate a 15.00s vertical MiniMax H3 video through customer-media-api.

The API key is read only from MINIMAX_API_KEY and is never written to disk.
"""

from __future__ import annotations

import argparse
import base64
from concurrent.futures import ThreadPoolExecutor, as_completed
import json
import os
import subprocess
import sys
import time
from pathlib import Path
from urllib.parse import urljoin

import imageio_ffmpeg
import requests


BASE_URL = "https://ai.usmercari.com"
MODEL = "MiniMax H3"

PROMPT_FULL = """竖屏写实服装展示。三张参考图按顺序使用：参考图1是人物身份、脸、丸子头、身材、完整穿搭、室内场景和期望开场姿势的最高优先级锚点；参考图2是同一个人在01到09九个先后时刻的动作轨迹，九格绝不是九个人，禁止在同一画面出现两格；参考图3只校验米白短袖T恤的颜色、版型、圆领、袖长以及胸前印花内容、位置和比例。[Static shot] 全程固定机位、不中断、不切镜，同一背景、光线、焦段和人物尺度，始终只有参考图1中的同一位成年女性。开场尽量匹配参考图1：正面站立，双手捏住衣服肩部两侧。所有动作由同一个身体连续完成，身体中心稳定，双脚贴地，动作克制。0-1.7秒：前臂同步向外微拉1-2厘米并略上抬，头抬平，衣服拉平。1.7-3.3秒：双手保持，头微微前探，眼睛稍睁大，嘴角打开形成自然惊喜。3.3-5秒：双手连续松开并移到身后，头回正，肩膀放松，恢复微笑。5-6.7秒：右手捏住右侧下摆，向右下轻拉2-4厘米。6.7-8.4秒：只向画面左侧迈一小步，小幅侧转后稳定，右前臂抬到胸前。8.4-10秒：回到正面稳定站姿，双手轻柔整理T恤胸前布料，停在锁骨两侧。10-11.7秒：双手捏住圆领左右两侧，向上提1-2厘米并轻微外拉，领口变平整。11.7-13.4秒：双手自然放下，正面站稳微笑。13.4-15秒：左手移到身后，右手在脸侧短暂竖起拇指，随后前臂缓慢下移、手腕内旋，改为食指垂直指向下方；头向右偏约10度，双脚不动。胸前棕色“QNW Worldwide”、椭圆“2026”、四只彩色小狗及下方英文印花只能附着于衣服布料，位置比例不变。禁止九宫格、拼贴、格线、编号、第二人物、复制、分身、镜像、残影、换脸、换发型、换装、增生肢体、手指畸形、关节反折、穿模、走向镜头、舞蹈、挥手、转圈、坐下、商品桌面特写、字幕、额外Logo和水印。"""

PROMPT_1 = """竖屏写实服装展示视频。图像就是第1帧和最高优先级锚点。[Static shot] 全程固定机位、单一连续镜头、同一室内背景、同一光线和焦段，始终只有首帧中的同一位成年女性、同一张脸、丸子头、身材、米白色短袖T恤和白色短裤。身体中心稳定，双脚贴地，动作自然克制。0-1.7秒：从首帧姿势开始，双手捏住衣服肩部两侧，前臂同步向外微拉1-2厘米并略上抬，头从微低抬平，衣服轻轻拉平。1.7-3.3秒：双手保持，头微微前探，眼睛稍睁大，嘴角打开形成自然惊喜表情。3.3-5秒：双手连续、自然地松开衣服并移到身后，头回正，肩膀放松，恢复温和微笑。5-6.7秒：右手回到右侧下摆，用拇指和食指捏住布料，向右下轻拉2-4厘米，左侧身体不动。6.7-8.4秒：只向画面左侧迈一小步，身体小幅侧转后立即稳定，右前臂抬到胸前。8.4-10秒：回到正面稳定站姿，双手轻柔整理T恤胸前和领口附近布料，最后停在锁骨两侧。所有姿态由同一个身体连续过渡，不切镜，不淡入淡出，不复制人物。胸前棕色“QNW Worldwide”、椭圆“2026”、四只彩色小狗及下方英文印花始终只附着于布料，位置比例不变，随布料自然变形。禁止第二人物、分身、镜像、残影、换脸、换发型、换装、增生肢体、手指畸形、穿模、走向镜头、舞蹈、挥手、转圈、字幕、额外Logo、水印、格线、拼贴和编号。"""

PROMPT_2 = """这是上一段竖屏服装展示的无缝续接，输入图就是精确首帧。[Static shot] 保持同一成年女性、同一张脸与丸子头、同一米白短袖T恤白短裤、同一室内背景、光线、焦段、人物尺寸，固定机位，单一连续镜头，始终只有一个人。0-1.7秒：从输入姿势继续，双手分别捏住圆领左右两侧，向上提1-2厘米并轻微向外拉，肩部不动，头正直，领口变平整后双手停在锁骨两侧。1.7-3.4秒：双手自然放下，身体正面站稳，保持温和微笑，不增加其他动作。3.4-5秒：左手移到身后，右肘约90度，右手先在脸侧短暂竖起拇指，然后右前臂缓慢下移、手腕内旋，收起拇指并改为食指垂直指向下方；头轻微向右偏约10度，双脚不动，结束时右手停在胸前偏左、食指向下。5秒后完全静止。胸前棕色“QNW Worldwide”、椭圆“2026”、四只彩色小狗及下方英文印花始终只附着在衣服布料上，位置比例不变。禁止切镜、运镜、第二人物、复制、分身、镜像、残影、换脸、换发型、换装、增生肢体、手指畸形、穿模、舞蹈、挥手、举臂、转圈、字幕、额外Logo、水印、格线、拼贴和编号。"""


def api_headers(api_key: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"}


def check_response(response: requests.Response, action: str) -> dict:
    try:
        data = response.json()
    except ValueError as exc:
        raise RuntimeError(f"{action} returned HTTP {response.status_code}: {response.text[:500]}") from exc
    if not response.ok:
        raise RuntimeError(f"{action} returned HTTP {response.status_code}: {json.dumps(data, ensure_ascii=False)}")
    return data


def upload_image(session: requests.Session, api_key: str, path: Path) -> str:
    mime = "image/png" if path.suffix.lower() == ".png" else "image/jpeg"
    encoded = base64.b64encode(path.read_bytes()).decode("ascii")
    payload = {"data": f"data:{mime};base64,{encoded}", "extension": path.suffix.lstrip(".").lower()}
    response = session.post(f"{BASE_URL}/v1/media/upload/image", headers=api_headers(api_key), json=payload, timeout=180)
    data = check_response(response, f"upload {path.name}")
    media_id = data.get("id")
    if not media_id:
        raise RuntimeError(f"upload response has no id: {json.dumps(data, ensure_ascii=False)}")
    return str(media_id)


def submit_video(session: requests.Session, api_key: str, image_ids: list[str], prompt: str, duration: int) -> str:
    payload = {
        "model": MODEL,
        "prompt": prompt,
        "duration": duration,
        "metadata": {
            "aspect_ratio": "9:16",
            "resolution": "720p",
            "audio": False,
            "image_ids": image_ids,
        },
    }
    response = session.post(f"{BASE_URL}/cpa-file/submit/video", headers=api_headers(api_key), json=payload, timeout=180)
    data = check_response(response, "submit video")
    task_id = data.get("task_id")
    if not task_id:
        raise RuntimeError(f"submit response has no task_id: {json.dumps(data, ensure_ascii=False)}")
    return str(task_id)


def result_url_from_fetch(item: dict) -> tuple[str | None, int | None]:
    outer = item.get("data") or {}
    items = outer.get("items") or []
    if not items:
        return None, None
    first = items[0] or {}
    products = first.get("data") or []
    if not products:
        return None, first.get("credits_used")
    return products[0].get("url"), first.get("credits_used")


def wait_for_video(session: requests.Session, api_key: str, task_id: str, timeout_seconds: int = 1800) -> tuple[str, int | None]:
    deadline = time.monotonic() + timeout_seconds
    last_status = None
    while time.monotonic() < deadline:
        response = session.post(
            f"{BASE_URL}/cpa-file/fetch",
            headers=api_headers(api_key),
            json={"ids": [task_id]},
            timeout=90,
        )
        data = check_response(response, f"fetch task {task_id}")
        rows = data.get("data") or []
        if not rows:
            raise RuntimeError(f"fetch returned no task data: {json.dumps(data, ensure_ascii=False)}")
        row = rows[0]
        status = str(row.get("status", "UNKNOWN")).upper()
        if status != last_status:
            print(f"task {task_id}: {status} {row.get('progress', '')}", flush=True)
            last_status = status
        if status == "SUCCESS":
            url, credits = result_url_from_fetch(row)
            if not url:
                raise RuntimeError(f"successful task has no video URL: {json.dumps(row, ensure_ascii=False)}")
            return urljoin(BASE_URL, url), credits
        if status == "FAILURE":
            raise RuntimeError(f"task {task_id} failed: {row.get('fail_reason') or json.dumps(row, ensure_ascii=False)}")
        time.sleep(5)
    raise TimeoutError(f"task {task_id} did not finish within {timeout_seconds} seconds")


def download(session: requests.Session, url: str, destination: Path) -> None:
    with session.get(url, stream=True, timeout=300) as response:
        response.raise_for_status()
        with destination.open("wb") as handle:
            for chunk in response.iter_content(1024 * 1024):
                if chunk:
                    handle.write(chunk)


def run_ffmpeg(args: list[str]) -> None:
    command = [imageio_ffmpeg.get_ffmpeg_exe(), "-hide_banner", "-loglevel", "error", "-y", *args]
    subprocess.run(command, check=True)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--reference-image", required=True, action="append", type=Path)
    parser.add_argument("--output-dir", type=Path, default=Path("minimax_h3_output"))
    args = parser.parse_args()

    api_key = os.environ.get("MINIMAX_API_KEY")
    if not api_key:
        raise SystemExit("MINIMAX_API_KEY is not set")
    if not 1 <= len(args.reference_image) <= 4:
        raise SystemExit("provide between 1 and 4 reference images")
    for image_path in args.reference_image:
        if not image_path.is_file():
            raise SystemExit(f"reference image not found: {image_path}")

    output_dir = args.output_dir.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    session = requests.Session()
    manifest: dict = {"model": MODEL, "segments": []}

    reference_ids: list[str | None] = [None] * len(args.reference_image)

    def upload_one(index: int, path: Path) -> tuple[int, str]:
        print(f"uploading {index + 1}/{len(args.reference_image)}: {path.name}", flush=True)
        with requests.Session() as upload_session:
            media_id = upload_image(upload_session, api_key, path)
        print(f"uploaded {index + 1}/{len(args.reference_image)}: {path.name}", flush=True)
        return index, media_id

    with ThreadPoolExecutor(max_workers=len(args.reference_image)) as executor:
        futures = [executor.submit(upload_one, index, path) for index, path in enumerate(args.reference_image)]
        for future in as_completed(futures):
            index, media_id = future.result()
            reference_ids[index] = media_id

    if any(media_id is None for media_id in reference_ids):
        raise RuntimeError("one or more reference image uploads returned no media id")
    reference_ids = [str(media_id) for media_id in reference_ids]
    manifest["reference_ids"] = reference_ids
    print("all reference images uploaded; submitting one video task", flush=True)
    task1 = submit_video(session, api_key, reference_ids, PROMPT_FULL, 15)
    manifest["segments"].append({"task_id": task1, "duration": 15})
    (output_dir / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    url1, credits1 = wait_for_video(session, api_key, task1)
    segment1 = output_dir / "minimax-h3-vertical-15s.mp4"
    download(session, url1, segment1)
    manifest["segments"][0].update({"url": url1, "credits_used": credits1, "file": segment1.name})

    final_video = segment1
    manifest.update({"final_file": final_video.name, "duration": 15.0})
    (output_dir / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"DONE: {final_video}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
