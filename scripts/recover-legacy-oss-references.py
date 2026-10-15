"""Recover proven audit-frame copies without depending on an unreadable bucket.

Run on the production host, with an already generated private recovery manifest.
No source object is deleted. A database reference changes only after exact job
provenance, original key SHA-256 prefix, audit SHA-256 and destination bytes agree.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import urllib.parse

UUID = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")


def sql_text(value):
    return "CONVERT(0x" + str(value).encode("utf-8").hex() + " USING utf8mb4)"


def validate_recovery(row, task_root, target_bucket):
    for field in ("id", "organizationId", "taskId", "itemId", "proofJobId"):
        if not UUID.fullmatch(row.get(field, "")):
            raise ValueError("invalid recovery identity")
    if row["type"] != "VIDEO_FIRST_FRAME" or row["contentType"] != "image/jpeg":
        raise ValueError("unsupported recovery asset")
    original = urllib.parse.urlsplit(row["path"])
    if (original.scheme != "oss" or original.netloc != "hoyee"
            or original.query or original.fragment):
        raise ValueError("invalid original storage")
    parts = original.path.lstrip("/").split("/")
    if (len(parts) != 7 or parts[:2] != ["heyi", "video-audits"]
            or parts[2:5] != [row["organizationId"], row["taskId"], row["proofJobId"]]
            or not re.fullmatch(r"[0-9a-f]{24}", parts[5]) or parts[6] != "first-frame.jpg"):
        raise ValueError("original audit identity mismatch")
    relative = (Path(row["organizationId"]) / row["taskId"] / row["itemId"]
                / "private-video-review" / row["proofJobId"] / "first-frame.jpg")
    source = "/data/ai-video-tasks/" + relative.as_posix()
    if row["proofSource"] != source:
        raise ValueError("local proof identity mismatch")
    destination = "oss://" + target_bucket + "/heyi/server-materials/ai-video-tasks/" + relative.as_posix()
    if row["newPath"] != destination:
        raise ValueError("archived copy identity mismatch")
    task_root = Path(task_root).resolve()
    file = task_root / relative
    audit = file.parent / "audit-result.json"
    if any(path.resolve() != path for path in (file, audit)):
        raise ValueError("symlink proof rejected")
    data = file.read_bytes()
    if not data.startswith(b"\xff\xd8\xff"):
        raise ValueError("invalid JPEG proof")
    sha = hashlib.sha256(data).hexdigest()
    md5 = hashlib.md5(data).hexdigest()
    evidence = json.loads(audit.read_text())
    if (len(data) != row["size"] or sha != row["sha256"] or sha[:24] != parts[5] or md5 != row["md5"]
            or evidence.get("version") != "VIDEO_FRAME_LAYOUT_V1"
            or evidence.get("frameSha256") != sha):
        raise ValueError("audit proof checksum mismatch")
    return data


def verify_destination(content, row):
    if (len(content) != row["size"] or hashlib.sha256(content).hexdigest() != row["sha256"]
            or hashlib.md5(content).hexdigest() != row["md5"]):
        raise ValueError("destination checksum mismatch")


def same_asset(current, row, location):
    return (current and current.get("path") == location and current.get("size") == row["size"]
            and current.get("type") == row["type"] and current.get("organizationId") == row["organizationId"]
            and current.get("taskId") == row["taskId"] and current.get("itemId") == row["itemId"])


def current_asset(query, row):
    value = query("SELECT JSON_OBJECT('path',file_path,'size',size_bytes,'type',asset_type,"
                  "'organizationId',organization_id,'taskId',task_id,'itemId',item_id) "
                  "FROM ai_video_task_assets WHERE id=" + sql_text(row["id"])).strip()
    return json.loads(value) if value else None


def switch_reference(query, row):
    predicates = {
        "id": row["id"], "file_path": row["path"], "asset_type": row["type"],
        "organization_id": row["organizationId"], "task_id": row["taskId"], "item_id": row["itemId"],
    }
    where = " AND ".join("`" + field + "`=" + sql_text(value) for field, value in predicates.items())
    where += " AND size_bytes=" + str(int(row["size"]))
    result = query("UPDATE ai_video_task_assets SET file_path=" + sql_text(row["newPath"])
                   + " WHERE " + where + "; SELECT ROW_COUNT();").strip()
    if result != "1":
        raise ValueError("asset changed during switch")
    if not same_asset(current_asset(query, row), row, row["newPath"]):
        raise ValueError("post-switch verification failed")


def append_event(path, value):
    with path.open("a") as stream:
        stream.write(json.dumps(value) + "\n")
        stream.flush()
        os.fsync(stream.fileno())
    path.chmod(0o600)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default="/opt/heyi/migrations/aliyun-to-dayan-20261008")
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    import fcntl
    import oss2
    sys.path.insert(0, "/opt/heyi/deployments/test-to-prod-20260917")
    import release
    root = Path(args.root)
    root.mkdir(parents=True, exist_ok=True, mode=0o700)
    root.chmod(0o700)
    lock = (root / "recovery.lock").open("a")
    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    info = json.loads(subprocess.check_output(["docker", "inspect", "ai-commerce-app"]))[0]
    env = dict(value.split("=", 1) for value in info["Config"]["Env"])
    raw = subprocess.check_output(["docker", "exec", "ai-commerce-app", "cat",
                                   env["APP_DAYAN_OSS_CREDENTIALS_FILE"]]).decode()
    credentials = {}
    for line in raw.splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            key, value = line.split("=", 1)
            credentials[key.strip()] = value.strip()
    target_bucket = env["APP_DAYAN_OSS_BUCKET"]
    if target_bucket == "hoyee":
        raise ValueError("source and destination must differ")
    bucket = oss2.Bucket(oss2.Auth(credentials["accessKeyId"], credentials["accessKeySecret"]),
                         "https://oss.cloud.dayan.cloud", target_bucket, connect_timeout=10,
                         is_path_style=True)
    bucket.session.session.trust_env = False
    task_root = next(m["Source"] for m in info["Mounts"] if m["Destination"] == "/data/ai-video-tasks")
    rows = json.loads((root / "verified-local-recovery.json").read_text())
    summary = {"verified": 0, "switched": 0, "alreadySwitched": 0, "conflicts": 0, "failed": 0, "bytes": 0}
    for row in rows:
        try:
            current = current_asset(release.sql, row)
            if same_asset(current, row, row["newPath"]):
                summary["alreadySwitched"] += 1
                continue
            if not same_asset(current, row, row["path"]):
                summary["conflicts"] += 1
                continue
            validate_recovery(row, task_root, target_bucket)
            key = urllib.parse.urlsplit(row["newPath"]).path.lstrip("/")
            meta = bucket.head_object(key)
            if meta.content_length != row["size"] or meta.etag.strip('"').lower() != row["md5"]:
                raise ValueError("destination metadata mismatch")
            obj = bucket.get_object(key)
            try:
                content = obj.read(row["size"] + 1)
            finally:
                obj.close()
            verify_destination(content, row)
            # Validate the local evidence again before changing any reference.
            validate_recovery(row, task_root, target_bucket)
            summary["verified"] += 1
            if args.apply:
                append_event(root / "recovery-events.jsonl", {"stage": "verified", **row})
                switch_reference(release.sql, row)
                append_event(root / "recovery-events.jsonl", {"stage": "switched", "id": row["id"]})
                summary["switched"] += 1
            summary["bytes"] += row["size"]
        except Exception as error:
            summary["failed"] += 1
            append_event(root / "recovery-events.jsonl", {
                "stage": "failed", "id": row["id"], "failureType": type(error).__name__,
                "httpStatus": getattr(error, "status", None),
            })
        if sum(summary[k] for k in ("verified", "alreadySwitched", "conflicts", "failed")) % 10 == 0:
            print(json.dumps(summary), flush=True)
    (root / "recovery-summary.json").write_text(json.dumps(summary))
    (root / "recovery-summary.json").chmod(0o600)
    print("FINAL " + json.dumps(summary), flush=True)
    return 1 if summary["failed"] else 0


if __name__ == "__main__":
    sys.exit(main())
