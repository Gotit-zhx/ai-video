"""Snapshot recent failures, dispatch each once via normal APIs, and observe read-only."""
import argparse
import base64
import fcntl
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time
import urllib.parse


def command(*args, data=None):
    result = subprocess.run(args, input=data, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode:
        # Commands can carry session or Redis credentials; never include argv.
        raise RuntimeError("Operational command failed, exit " + str(result.returncode))
    return result.stdout


def query(sql):
    return command("docker", "exec", "ai-commerce-mysql", "sh", "-c",
                   'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql -u "$MYSQL_USER" "$MYSQL_DATABASE" --default-character-set=utf8mb4 -NB --raw -e "$1"',
                   "query", sql).decode().splitlines()


def text(value):
    return "CONVERT(0x" + value.encode().hex() + " USING utf8mb4)"


def save(path, value):
    metadata = path.stat() if path.exists() else None
    temporary = path.with_suffix(".part")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2))
    if metadata:
        os.chown(temporary, metadata.st_uid, metadata.st_gid)
    temporary.chmod(metadata.st_mode & 0o777 if metadata else 0o600)
    temporary.replace(path)


def inspect(name):
    return json.loads(command("docker", "inspect", name))[0]


def existing_cookies(owners):
    env = dict(value.split("=", 1) for value in inspect("ai-commerce-app")["Config"]["Env"])
    password = next(value for key, value in env.items() if "REDIS" in key and "PASSWORD" in key)

    def redis(*args):
        return command("docker", "exec", "-e", "REDISCLI_AUTH=" + password,
                       "ai-commerce-redis", "redis-cli", "--no-auth-warning", "--raw", *args)

    cookies = {}
    for key in redis("--scan", "--pattern", "heyi:session:sessions:*").decode().splitlines():
        if key.startswith("heyi:session:sessions:expires:"):
            continue
        context = redis("HGET", key, "sessionAttr:SPRING_SECURITY_CONTEXT")
        for owner in owners:
            if owner.encode() in context:
                cookies.setdefault(owner, []).append(base64.b64encode(key.rsplit(":", 1)[1].encode()).decode())
    return cookies


def api(path, cookie, method="GET"):
    assert path.startswith("/api/v1/") and "\n" not in path
    config = ('url = "http://127.0.0.1:8080' + path + '"\n'
              'header = "Cookie: HEYI_SESSION=' + cookie + '"\n'
              'request = "' + method + '"\n'
              'max-time = 30\nsilent\nshow-error\nwrite-out = "\\n%{http_code}"\n')
    raw = command("docker", "exec", "-i", "ai-commerce-app", "curl", "--config", "-", data=config.encode()).decode()
    body, status = raw.rsplit("\n", 1)
    return int(status), json.loads(body)


def snapshot(root):
    path = root / "recovery-targets.json"
    if path.exists():
        return json.loads(path.read_text())
    # Shanghai 2026-10-15 only. Never sweep historical failures into paid retries.
    since = "'2026-10-14 16:00:00'"
    tasks = [json.loads(row) for row in query(
        "SELECT JSON_OBJECT('id',t.id,'name',t.name,'owner',t.created_by,'organizationId',t.organization_id,"
        "'status',t.status,'scriptId',t.script_id,'updatedAt',t.updated_at,'mode',t.generation_mode,"
        "'items',(SELECT JSON_ARRAYAGG(JSON_OBJECT('id',i.id,'status',i.status,'error',i.error_message)) "
        "FROM ai_video_task_items i WHERE i.task_id=t.id)) FROM ai_video_tasks t "
        "WHERE t.status='FAILED' AND t.task_type='PRODUCT' AND t.updated_at>=" + since)]
    scripts = [json.loads(row) for row in query(
        "SELECT JSON_OBJECT('id',id,'name',name,'owner',created_by,'organizationId',organization_id,"
        "'status',status,'updatedAt',updated_at) FROM video_scripts WHERE status='FAILED' AND updated_at>=" + since)]
    value = {"createdAt": time.time(), "tasks": tasks, "scripts": scripts}
    save(path, value)
    return value


def verify_image_receipts(task):
    app = inspect("ai-commerce-app")
    runtime = inspect("heyi-pi-runtime")
    task_root = Path(next(m["Source"] for m in app["Mounts"] if m["Destination"] == "/data/ai-video-tasks"))
    operation_root = Path(next(m["Source"] for m in runtime["Mounts"] if m["Destination"] == "/data/image-operations"))
    for item in task["items"]:
        if item["status"] not in ("FAILED", "VIDEO_FAILED"):
            continue
        directory = task_root / task["organizationId"] / task["id"] / item["id"] / "private-validation"
        ledgers = list(directory.glob("*.requests.json"))
        if not ledgers:
            assert "STRUCTURE_ANALYSIS_PAUSED" in (item.get("error") or ""), "Missing original request evidence"
        for file in ledgers:
            ledger = json.loads(file.read_text())
            for attempt in ledger.get("attempts", []):
                operation = operation_root / (hashlib.sha256(attempt["id"].encode()).hexdigest() + ".json")
                receipt = json.loads(operation.read_text())
                assert receipt["status"] == "FAILED", "A saved or running image result must not be regenerated"
                assert receipt.get("error", {}).get("requestState") in ("REJECTED", "NOT_SENT"), "Original image billing/result is still unknown"


def verified_original_proof(root, case):
    assert case and case.replace("-", "").isalnum(), "A verified real-image case is required"
    proof_root = root / "live-proof" / case
    proof = json.loads((proof_root / "summary.json").read_text())
    storage = json.loads((proof_root / "storage.json").read_text())
    assert proof["status"] == "COMPLETED" and proof["posts"] == 1
    assert proof["size"] == "2160x3840" and proof["stream"] is True and proof["imageArray"] is False
    post = next(row for row in proof["wire"] if row["method"] == "POST")
    assert post["fields"]["input_fidelity"] == "high"
    assert storage["status"] == "VERIFIED" and storage["sha256"] == proof["sha256"]
    assert storage["privateReadMatches"] is True and storage["signedReadMatches"] is True
    data = (proof_root / Path(proof["file"]).name).read_bytes()
    assert hashlib.sha256(data).hexdigest() == proof["sha256"]


def authorize_exhausted_once(root, task, case):
    info = inspect("ai-commerce-app")
    mount = next(m["Source"] for m in info["Mounts"] if m["Destination"] == "/data/ai-video-tasks")
    directory = Path(mount) / task["organizationId"] / task["id"]
    for item in task["items"]:
        for file in (directory / item["id"] / "private-validation").glob("*.requests.json"):
            original = file.read_bytes()
            value = json.loads(original)
            assert isinstance(value.get("spent"), int) and not isinstance(value["spent"], bool)
            if value["spent"] < 2 or value.get("recoveryAllowance", 0) == 1:
                continue
            assert value["spent"] == 2 and len(value["attempts"]) == 2, "Unproven historical attempts"
            assert all(attempt["rejected"] is True for attempt in value["attempts"])
            assert query("SELECT status FROM ai_video_tasks WHERE id=" + text(task["id"])) == ["FAILED"]
            backup = root / "recovery-ledger-backups" / (hashlib.sha256(str(file).encode()).hexdigest() + ".json")
            backup.parent.mkdir(mode=0o700, exist_ok=True)
            assert not backup.exists(), "A prior recovery authorization must not be repeated"
            backup.write_bytes(original)
            backup.chmod(0o600)
            value["recoveryAllowance"] = 1
            value["recoveryReason"] = (
                "User authorized one recovery after upstream channel change on 2026-10-15; "
                "original-request image and OSS verified in " + case + "; spent and history retained")
            assert file.read_bytes() == original, "Concurrent ledger change"
            save(file, value)
            print("Authorized one recovery slot without resetting history:", task["id"], item["id"], flush=True)


def verify_unused_allowance(root, task, repair_ownership=False):
    info = inspect("ai-commerce-app")
    mount = next(m["Source"] for m in info["Mounts"] if m["Destination"] == "/data/ai-video-tasks")
    count = 0
    for item in task["items"]:
        directory = Path(mount) / task["organizationId"] / task["id"] / item["id"] / "private-validation"
        for file in directory.glob("*.requests.json"):
            backup = root / "recovery-ledger-backups" / (hashlib.sha256(str(file).encode()).hexdigest() + ".json")
            if not backup.exists():
                continue
            before = json.loads(backup.read_text())
            current = json.loads(file.read_text())
            assert current.get("recoveryAllowance") == 1 and current.get("recoveryReason", "").startswith(
                "User authorized one recovery after upstream channel change on 2026-10-15;")
            restored = {k: v for k, v in current.items() if k not in ("recoveryAllowance", "recoveryReason")}
            assert restored == {k: v for k, v in before.items() if k not in ("recoveryAllowance", "recoveryReason")}
            assert current["spent"] == 2 and len(current["attempts"]) == 2
            parent = directory.stat()
            assert parent.st_uid != 0, "Application directory must belong to its non-root user"
            if repair_ownership and file.stat().st_uid == 0:
                os.chown(file, parent.st_uid, parent.st_gid)
                print("Restored application ownership without changing ledger bytes:", task["id"], item["id"], flush=True)
            assert file.stat().st_uid == parent.st_uid and file.stat().st_gid == parent.st_gid
            count += 1
    assert count == len(task["items"]), "Every item needs an unchanged unused authorized ledger"


def dispatch(root, targets, proof_case, authorize_recovery=False, resume_presubmit=False):
    verified_original_proof(root, proof_case)
    path = root / "recovery-dispatch.json"
    events = json.loads(path.read_text()) if path.exists() else {}
    owners = {row["owner"] for row in targets["tasks"] + targets["scripts"]}
    sessions = existing_cookies(owners)
    cookies = {}
    for row in targets["tasks"] + targets["scripts"]:
        owner = row["owner"]
        if owner in cookies:
            continue
        for cookie in sessions.get(owner, []):
            status, result = api("/api/v1/auth/me", cookie)
            user = result.get("data") or {}
            if status == 200 and user.get("userId") == owner and user.get("organizationId") == row["organizationId"]:
                cookies[owner] = cookie
                break
        assert owner in cookies, "No existing authorized session for task owner; do not mint credentials"
    for kind, rows in [("scripts", targets["scripts"]), ("tasks", targets["tasks"])]:
        for row in rows:
            key = kind + ":" + row["id"]
            prior = events.get(key)
            resuming = False
            if key in events:
                if not (resume_presubmit and kind == "tasks" and prior["status"] == "ACCEPTED"
                        and not prior.get("presubmitRepairAttempted")):
                    continue
                failed = query("SELECT COUNT(*) FROM ai_video_task_items WHERE task_id=" + text(row["id"])
                               + " AND status='FAILED' AND error_message LIKE "
                               + text("IMAGE_GENERATION_LIMIT: 生图次数记录无法核对%"))
                if failed != [str(len(row["items"]))]:
                    continue
                verify_image_receipts(row)
                verify_unused_allowance(root, row, repair_ownership=True)
                resuming = True
            if kind == "tasks":
                active = query("SELECT COUNT(*) FROM ai_video_tasks WHERE organization_id="
                               + text(row["organizationId"]) + " AND created_by=" + text(row["owner"])
                               + " AND id<>" + text(row["id"])
                               + " AND status IN ('IMAGE_GENERATING','VIDEO_GENERATING')")
                if active != ["0"]:
                    print("Waiting for owner's current generation:", row["name"], flush=True)
                    continue
                # New script evidence must be ready before refreshing a task's snapshot.
                dependency = next((s for s in targets["scripts"] if s["id"] == row["scriptId"]), None)
                if dependency:
                    state = query("SELECT status FROM video_scripts WHERE id=" + text(dependency["id"]))
                    if state != ["READY"]:
                        print("Waiting for script evidence:", row["name"], flush=True)
                        continue
                verify_image_receipts(row)
            prefix = "/api/v1/video-scripts/" if kind == "scripts" else "/api/v1/ai-video-tasks/"
            endpoint = prefix + urllib.parse.quote(row["id"]) + ("/retry" if kind == "scripts" else "/start-generation")
            status, current = api(prefix + urllib.parse.quote(row["id"]), cookies[row["owner"]])
            assert status == 200
            detail = current.get("data") or {}
            current_status = (detail.get("task") or detail.get("script") or detail).get("status")
            if current_status != "FAILED":
                events[key] = {"status": "SKIPPED", "reason": "Current task is not failed", "at": time.time()}
                save(path, events)
                continue
            if kind == "tasks" and authorize_recovery:
                authorize_exhausted_once(root, row, proof_case)
            extra = {"previousDispatch": prior, "presubmitRepairAttempted": True} if resuming else {}
            events[key] = {"status": "DISPATCHING", "at": time.time(), **extra}
            save(path, events)
            try:
                status, result = api(endpoint, cookies[row["owner"]], "POST")
                events[key] = {"status": "ACCEPTED" if status == 202 else "REJECTED",
                               "httpStatus": status, "at": time.time(),
                               "message": result.get("message"), **extra}
            except Exception:
                events[key] = {"status": "ACK_UNKNOWN", "at": time.time(), **extra}
                save(path, events)
                raise
            save(path, events)
            print(kind, row["name"], row["id"], events[key]["status"], flush=True)
    return events


def status(targets):
    task_ids = ",".join(text(row["id"]) for row in targets["tasks"]) or "NULL"
    script_ids = ",".join(text(row["id"]) for row in targets["scripts"]) or "NULL"
    statements = [
        "SELECT JSON_OBJECT('kind','task','id',id,'name',name,'status',status,'progress',progress,"
        "'error',error_message,'updatedAt',updated_at) FROM ai_video_tasks WHERE id IN (" + task_ids + ")",
        "SELECT JSON_OBJECT('kind','item','id',id,'taskId',task_id,'status',status,'progress',progress,"
        "'error',error_message,'steps',image_progress_json,'updatedAt',updated_at) "
        "FROM ai_video_task_items WHERE task_id IN (" + task_ids + ")",
        "SELECT JSON_OBJECT('kind','script','id',id,'name',name,'status',status,'progress',progress,"
        "'error',error_message,'updatedAt',updated_at) FROM video_scripts WHERE id IN (" + script_ids + ")",
    ]
    return [json.loads(row) for statement in statements for row in query(statement)]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=["snapshot", "dispatch", "status"])
    parser.add_argument("--root", default="/opt/heyi/releases/video-pipeline-20261015")
    parser.add_argument("--proof-case")
    parser.add_argument("--authorize-exhausted-once", action="store_true")
    parser.add_argument("--resume-presubmit-ledger-once", action="store_true")
    args = parser.parse_args()
    root = Path(args.root)
    root.mkdir(mode=0o700, parents=True, exist_ok=True)
    lock = (root / "recovery-observation.lock").open("a")
    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    targets = snapshot(root)
    if args.mode == "snapshot":
        print(json.dumps({"tasks": len(targets["tasks"]), "scripts": len(targets["scripts"]),
                          "items": sum(len(row["items"]) for row in targets["tasks"])}, ensure_ascii=False))
        return
    if args.mode == "dispatch":
        dispatch(root, targets, args.proof_case, args.authorize_exhausted_once, args.resume_presubmit_ledger_once)
    result = status(targets)
    save(root / "recovery-latest.json", {"observedAt": time.time(), "rows": result})
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
