"""Restore exact cached previews from retained audit images, without AI calls."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import uuid

task_ids = sys.argv[1:]
assert task_ids and all(str(uuid.UUID(value)) == value for value in task_ids)
app = json.loads(subprocess.check_output(["docker", "inspect", "ai-commerce-app"]))[0]
root = Path(next(m["Source"] for m in app["Mounts"] if m["Destination"] == "/data/ai-video-tasks"))
query = (
    "SELECT JSON_OBJECT('id',id,'task',task_id,'location',file_path,'size',size_bytes) "
    "FROM ai_video_task_assets WHERE asset_type='VIDEO_FIRST_FRAME' AND task_id IN ("
    + ",".join("'" + task + "'" for task in task_ids) + ")"
)
records = subprocess.check_output([
    "docker", "exec", "ai-commerce-mysql", "sh", "-c",
    'MYSQL_PWD="$MYSQL_PASSWORD" mysql --default-character-set=utf8mb4 '
    '-u "$MYSQL_USER" "$MYSQL_DATABASE" -NB --raw -e "$1"',
    "query", query,
], text=True).splitlines()
cache = root / ".asset-cache"
cache.mkdir(exist_ok=True)
results = []
for record in records:
    asset = json.loads(record)
    location = asset["location"]
    if not location.startswith("oss://"):
        continue
    pieces = location.split("/")
    job_id, digest = pieces[-3:-1]
    matches = list(root.glob("*/" + asset["task"] + "/*/private-video-review/" + job_id + "/first-frame.jpg"))
    verified = [
        path for path in matches if path.is_file() and not path.is_symlink()
        and path.stat().st_size == asset["size"]
        and hashlib.sha256(path.read_bytes()).hexdigest().startswith(digest)
    ]
    if len(verified) != 1:
        results.append({"asset": asset["id"], "recovered": False})
        continue
    source = verified[0]
    destination = cache / (hashlib.sha256(location.encode()).hexdigest() + ".bin")
    temporary = destination.with_suffix("." + str(uuid.uuid4()) + ".part")
    try:
        shutil.copy2(source, temporary)
        assert hashlib.sha256(source.read_bytes()).digest() == hashlib.sha256(temporary.read_bytes()).digest()
        temporary.replace(destination)
        results.append({"asset": asset["id"], "recovered": True, "size": asset["size"]})
    finally:
        temporary.unlink(missing_ok=True)
print(json.dumps(results, indent=2))
