"""User-authorized recovery of this one batch. Keep all old requests and all completed assets."""
import base64
import hashlib
import json
import os
import pathlib
import shutil
import subprocess
import time

TASK = "a51f4f49-aecd-441d-bce4-3d9851addb46"
ORG = "6a61f788-6685-4b64-82b1-eec97ed8cf90"
ROOT = pathlib.Path("/opt/heyi/releases/video-recovery-20260929")
STORAGE = pathlib.Path("/var/lib/docker/volumes/web_sdk__ai_video_task_data/_data")
OPERATIONS = pathlib.Path("/var/lib/docker/volumes/heyi_image_operation_data/_data")
ITEMS = {
    "7810a2d9-411c-484c-b596-99e340dfb413": ("storyboard_grid", "72123425-7c98-46df-a13d-b1a8870cdf7a", 1),
    "a37f2701-9a0a-42bf-a73f-6d6c7d44ef74": ("storyboard_grid", "fa8f64ea-31f2-4a1d-a87f-62496f8d1991", 1),
    "ae9569fa-87e0-4a77-a39f-d544776842d9": ("storyboard_grid", "a8f9d8e6-eaea-4038-ad94-073ba6afa1f8", 1),
    "360adef2-3d89-469e-adf7-5ff138a94aa9": ("model_image", "16e7df76-b1d7-4d0f-b216-3481b7167dce", 2),
}


def sql(query):
    return subprocess.check_output(["docker", "exec", "ai-commerce-mysql", "sh", "-c",
        'MYSQL_PWD="$MYSQL_PASSWORD" mysql --default-character-set=utf8mb4 -u "$MYSQL_USER" "$MYSQL_DATABASE" -NB --raw -e "$1"',
        "query", query], text=True)


def atomic(path, data, owner):
    path.parent.mkdir(exist_ok=True, parents=True)
    os.chown(path.parent, owner.st_uid, owner.st_gid)
    temporary = path.with_name(path.name + ".recovery-part")
    temporary.write_bytes(data)
    os.chown(temporary, owner.st_uid, owner.st_gid)
    temporary.chmod(0o600)
    temporary.replace(path)


assert (ROOT / "activation.json").exists(), "Deploy and validate before resuming"
assert not (ROOT / "resume.json").exists(), "Never apply the recovery twice"
assert sql(f"SELECT status FROM ai_video_tasks WHERE id='{TASK}' AND organization_id='{ORG}'").strip() == "FAILED"
assert int(sql(f"SELECT COUNT(*) FROM ai_video_task_items WHERE task_id='{TASK}' AND status='FAILED'")) == 4
assets_before = sql(f"SELECT JSON_OBJECT('id',id,'item',item_id,'type',asset_type,'location',file_path,'size',size_bytes) "
                   f"FROM ai_video_task_assets WHERE task_id='{TASK}'")
assets = [json.loads(line) for line in assets_before.splitlines()]
assert sum(asset["type"] == "MODEL_IMAGE" for asset in assets) == 3
backup = ROOT / "resume-backup"
backup.mkdir(exist_ok=True)
(backup / "assets.jsonl").write_text(assets_before)
mutations = []
for item, (kind, request_id, spent) in ITEMS.items():
    path = STORAGE / ORG / TASK / item / "private-validation" / (kind + ".requests.json")
    state = json.loads(path.read_text())
    assert state["spent"] == spent and state["attempts"][-1]["id"] == request_id
    record = json.loads((OPERATIONS / (hashlib.sha256(request_id.encode()).hexdigest() + ".json")).read_text())
    assert record["status"] == "FAILED" and record["error"]["requestState"] == "RESPONSE_TIMEOUT"
    assert not record.get("result") and not any(route.get("response") for route in record.get("routes", {}).values())
    assert state.get("recoveryAllowance", 0) == 0
    shutil.copy2(path, backup / (item + "-" + path.name))
    state["attempts"][-1]["rejected"] = True
    if spent == 2:
        # The user explicitly requested completion of this failed batch. Grant one
        # additional attempt for this item only; never erase or refund its two calls.
        state["recoveryAllowance"] = 1
        state["recoveryReason"] = "2026-09-29 用户要求修复后接续当前批次；仅补充一次恢复，原两次请求继续计入消耗"
    mutations.append((path, json.dumps(state, ensure_ascii=False).encode(), path.stat()))

# Reuse original completed outputs directly from the runtime's durable receipts.
cached = 0
for asset in assets:
    if asset["type"] != "MODEL_IMAGE" or not asset["location"].startswith("oss://"):
        continue
    folder = STORAGE / ORG / TASK / asset["item"] / "private-validation"
    ledger = json.loads((folder / "model_image.requests.json").read_text())
    request_id = ledger["attempts"][-1]["id"]
    record = json.loads((OPERATIONS / (hashlib.sha256(request_id.encode()).hexdigest() + ".json")).read_text())
    image = (record.get("result") or {}).get("artifact", {}).get("dataBase64")
    if not image:
        continue
    data = base64.b64decode(image, validate=True)
    assert len(data) == asset["size"]
    file = STORAGE / ".asset-cache" / (hashlib.sha256(asset["location"].encode()).hexdigest() + ".bin")
    atomic(file, data, folder.stat())
    cached += 1
for path, data, owner in mutations:
    atomic(path, data, owner)
sql(f"""START TRANSACTION;
SELECT id FROM ai_video_tasks WHERE id='{TASK}' FOR UPDATE;
UPDATE ai_video_task_items SET status='READY',error_message=NULL,completed_at=NULL,updated_at=NOW()
 WHERE task_id='{TASK}' AND status='FAILED';
UPDATE ai_video_tasks SET status='IMAGE_GENERATING',progress=1,completed_items=0,
 auto_start_video_after_images=1,error_message=NULL,completed_at=NULL,updated_at=DATE_SUB(NOW(),INTERVAL 31 SECOND)
 WHERE id='{TASK}' AND status='FAILED';
COMMIT;""")
(ROOT / "resume.json").write_text(json.dumps({
    "task": TASK, "resumedAt": time.time(), "completedImagesRetained": 3,
    "cachedOriginalOutputs": cached, "oldSpentPreserved": True,
    "extraAllowance": {"360adef2-3d89-469e-adf7-5ff138a94aa9": 1},
    "maxAdditionalPostsForAlreadyFailedSteps": 4}, indent=2))
print("Resumed one batch; retained 3 original model images; each failed step gets at most one new submission")
