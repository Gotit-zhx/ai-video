"""Run one explicit image-only preview without changing the source video task."""
import http.cookiejar
import json
from pathlib import Path
import subprocess
import sys
import urllib.error
import urllib.request
import uuid

ROOT = Path(sys.argv[1]).resolve()
MODE = sys.argv[2]
SOURCE = "8909ffc6-d52c-48dd-9ce9-464632be6013"
ORGANIZATION = "6a61f788-6685-4b64-82b1-eec97ed8cf90"
BASE = "https://hoyee.org.cn"
assert str(ROOT).startswith("/opt/heyi/diagnostics/")
ROOT.mkdir(mode=0o700, parents=True, exist_ok=True)
STATE = ROOT / "preview.json"


def save(value):
    STATE.write_text(json.dumps(value, ensure_ascii=False, indent=2))
    STATE.chmod(0o600)


def state():
    return json.loads(STATE.read_text())


def login():
    info = json.loads(subprocess.check_output(
        ["docker", "inspect", "ai-commerce-app"]))[0]
    env = dict(item.split("=", 1) for item in info["Config"]["Env"])
    assert env.get("APP_ADMIN_PHONE") and env.get("APP_ADMIN_PASSWORD"), "Missing configured login"
    opener = urllib.request.build_opener(
        urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    body = json.dumps({"organizationCode": "heyi", "phone": env["APP_ADMIN_PHONE"],
                       "password": env["APP_ADMIN_PASSWORD"]}).encode()
    with opener.open(urllib.request.Request(
            BASE + "/api/v1/auth/login", data=body,
            headers={"Content-Type": "application/json"}, method="POST"), timeout=30) as response:
        user = json.load(response)["data"]
    assert user["organizationId"] == ORGANIZATION, "Unexpected login organization"
    return opener


def request(opener, path, method="GET", body=None, content_type="application/json", binary=False):
    data = body if isinstance(body, bytes) else json.dumps(body).encode() if body is not None else None
    with opener.open(urllib.request.Request(
            BASE + path, data=data, headers={"Content-Type": content_type}, method=method),
            timeout=120) as response:
        return response.read() if binary else json.load(response)["data"]


def detail(opener, task_id):
    result = request(opener, "/api/v1/ai-video-tasks/" + task_id)
    assert result["task"]["organizationId"] == ORGANIZATION
    return result


def prepare(opener):
    assert not STATE.exists(), "Preview already prepared or creation uncertain; inspect before continuing"
    source = detail(opener, SOURCE)
    task = source["task"]
    assert task["generationMode"] == "MODEL_WEAR" and not task["directReferenceVideo"]
    assert len(source["items"]) == 1, "Review multi-item cost before creating a preview"
    tasks = request(opener, "/api/v1/ai-video-tasks")
    assert max(tasks, key=lambda item: item["createdAt"])["id"] == SOURCE, "Latest task changed"
    item = source["items"][0]
    content = request(opener, f"/api/v1/ai-video-tasks/{SOURCE}/items/{item['id']}/source", binary=True)
    assert content.startswith(b"\x89PNG\r\n\x1a\n"), "Expected the reviewed PNG source"
    record = {"sourceTaskId": SOURCE, "sourceItemId": item["id"], "phase": "CREATING",
              "originalAssetIds": sorted(asset["id"] for asset in source["assets"]),
              "originalVideoProviderTaskId": item["videoProviderTaskId"],
              "videoSubmissions": 0, "imageStartRequests": 0}
    save(record)
    command = {key: task[key] for key in (
        "scriptId", "targetMarket", "generationMode", "backgroundMode",
        "productCategory", "aspectRatio", "resolution", "videoRoute")}
    command.update(name=task["name"] + " - V6 preview 20261020",
                   voiceoverMode="NONE", useScriptReferenceVideo=False, allowHand=False)
    preview = request(opener, "/api/v1/ai-video-tasks", "POST", command)
    assert not preview["directReferenceVideo"]
    record.update(previewTaskId=preview["id"], phase="UPLOADING")
    save(record)
    boundary = "preview-" + uuid.uuid4().hex
    multipart = (
        f"--{boundary}\r\nContent-Disposition: form-data; name=\"files\"; filename=\"{item['sourceFileName']}\"\r\n"
        "Content-Type: image/png\r\n\r\n"
    ).encode() + content + f"\r\n--{boundary}--\r\n".encode()
    uploaded = request(opener, f"/api/v1/ai-video-tasks/{preview['id']}/garments",
                       "POST", multipart, "multipart/form-data; boundary=" + boundary)
    assert len(uploaded) == 1
    record.update(previewItemId=uploaded[0]["id"], phase="PREPARED")
    save(record)
    print(json.dumps({"phase": record["phase"], "sourceTaskId": SOURCE,
                      "previewTaskId": preview["id"], "sourceItems": 1, "videoSubmissions": 0}))


def generate(opener):
    record = state()
    assert record["phase"] == "PREPARED" and record["imageStartRequests"] == 0
    preview = detail(opener, record["previewTaskId"])
    assert preview["task"]["status"] == "READY" and not preview["assets"]
    record.update(phase="SUBMITTING_IMAGES", imageStartRequests=1)
    save(record)
    result = request(opener, "/api/v1/ai-video-tasks/" + record["previewTaskId"] + "/start-images", "POST")
    record.update(phase="IMAGES_SUBMITTED", status=result["status"])
    save(record)
    print(json.dumps({"phase": record["phase"], "previewTaskId": record["previewTaskId"],
                      "status": result["status"], "imageStartRequests": 1, "videoSubmissions": 0}))


def poll(opener):
    record = state()
    preview = detail(opener, record["previewTaskId"])
    source = detail(opener, SOURCE)
    assert set(record["originalAssetIds"]).issubset({asset["id"] for asset in source["assets"]}), \
        "Source assets changed; stop and review"
    assert source["items"][0]["videoProviderTaskId"] == record["originalVideoProviderTaskId"], \
        "Source video provider task changed"
    assert preview["videoSubmittedAt"] is None
    assert all(item["videoProviderTaskId"] is None for item in preview["items"])
    assert not any(asset["assetType"] == "VIDEO_RESULT" for asset in preview["assets"])
    if record.get("preservedModelAssetId"):
        assert any(asset["id"] == record["preservedModelAssetId"] and asset["assetType"] == "MODEL_IMAGE"
                   for asset in preview["assets"]), "Preview model image changed"
    item = preview["items"][0]
    record.update(status=preview["task"]["status"], assets=preview["assets"])
    save(record)
    print(json.dumps({
        "previewTaskId": record["previewTaskId"], "name": preview["task"]["name"],
        "status": record["status"], "itemStatus": item["status"], "progress": item["progress"],
        "imageProgress": json.loads(item["imageProgressJson"]) if item.get("imageProgressJson") else None,
        "error": item["errorMessage"],
        "assets": [{"id": asset["id"], "type": asset["assetType"], "bytes": asset["sizeBytes"]}
                   for asset in preview["assets"]],
        "sourceAssetsPreserved": True, "sourceVideoTaskPreserved": True, "videoSubmissions": 0
    }, ensure_ascii=False))


def regenerate_storyboard(opener):
    record = state()
    assert record["previewTaskId"] == "4cb9b2da-e0fa-484b-874c-293f77ccc9d9"
    assert record.get("storyboardRegenerationRequests", 0) == 0, "Already requested; poll instead"
    poll(opener)
    preview = detail(opener, record["previewTaskId"])
    assert preview["task"]["status"] == "IMAGE_READY"
    assert len(preview["items"]) == 1 and preview["items"][0]["id"] == record["previewItemId"]
    models = [asset for asset in preview["assets"] if asset["assetType"] == "MODEL_IMAGE"]
    assert len(models) == 1 and models[0]["id"] == "d3a47484-87d6-4592-a9e2-3c34d3a97ba2"
    sql = ("SELECT auto_start_video_after_images FROM ai_video_tasks "
           "WHERE id='4cb9b2da-e0fa-484b-874c-293f77ccc9d9' "
           "AND organization_id='6a61f788-6685-4b64-82b1-eec97ed8cf90';")
    flag = subprocess.check_output(
        ["docker", "exec", "-i", "ai-commerce-mysql", "sh", "-c",
         'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -NB "$MYSQL_DATABASE"'],
        input=sql.encode()).decode().strip()
    assert flag == "0", "Automatic video start must remain disabled"
    record.update(phase="SUBMITTING_STORYBOARD", storyboardRegenerationRequests=1,
                  preservedModelAssetId=models[0]["id"], previousPreviewAssets=preview["assets"])
    save(record)
    result = request(opener, f"/api/v1/ai-video-tasks/{record['previewTaskId']}/items/"
                     f"{record['previewItemId']}/regenerate-storyboard", "POST")
    record.update(phase="STORYBOARD_SUBMITTED", status=result["status"])
    save(record)
    print(json.dumps({"phase": record["phase"], "status": result["status"],
                      "storyboardRegenerationRequests": 1, "videoSubmissions": 0}))


def export(opener):
    record = state()
    kind = sys.argv[3]
    assert kind in ("MODEL_IMAGE", "STORYBOARD_GUIDE", "STORYBOARD_GRID", "STORYBOARD_FIRST_FRAME")
    assets = detail(opener, record["previewTaskId"])["assets"]
    matches = [asset for asset in assets if asset["assetType"] == kind]
    assert len(matches) == 1, "Requested image is not ready"
    asset = matches[0]
    content = request(opener, f"/api/v1/ai-video-tasks/{record['previewTaskId']}/assets/{asset['id']}",
                      binary=True)
    assert len(content) == asset["sizeBytes"]
    sys.stdout.buffer.write(content)


if __name__ == "__main__":
    session = None
    try:
        session = login()
        {"prepare": prepare, "generate": generate, "poll": poll, "export": export,
         "regenerate-storyboard": regenerate_storyboard}[MODE](session)
    except urllib.error.HTTPError as error:
        print(json.dumps({"error": "Platform request failed", "httpStatus": error.code,
                          "mode": MODE, "resubmitted": False}), file=sys.stderr)
        raise SystemExit(1)
    finally:
        if session is not None:
            try:
                request(session, "/api/v1/auth/logout", "POST")
            except Exception:
                pass
