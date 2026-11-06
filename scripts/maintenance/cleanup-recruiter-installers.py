"""Delete obsolete installer copies only; current copies require OSS activation."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import time

parser = argparse.ArgumentParser()
parser.add_argument("--release-root", required=True)
parser.add_argument("--all-server-copies", action="store_true")
args = parser.parse_args()
release = Path(args.release_root).resolve()
downloads = Path("/opt/heyi/shared/hr-desktop-downloads")
if args.all_server_copies:
    proof = json.loads((release / "activation.json").read_text())
    manifest = json.loads((release / "oss-installers.json").read_text())
    build = json.loads((release / "release-build.json").read_text())
    assert proof["active"] and proof["version"] == build["version"]
    for stable, record in proof["artifacts"].items():
        assert any(value["verified"] and value["sha256"] == record["sha256"]
                   and value["url"] == record["url"] for value in manifest.values())
        headers = subprocess.check_output([
            "curl", "-fsSI", "--max-time", "15", "--resolve", "hoyee.org.cn:443:127.0.0.1",
            "https://hoyee.org.cn/downloads/" + stable]).decode().lower()
        assert "302" in headers.splitlines()[0] and ("location: " + record["url"]).lower() in headers
else:
    # Obsolete copies can be removed while the currently served pair is kept.
    for name in ("Heyi-Recruiter-mac-arm64.dmg", "Heyi-Recruiter-windows-x64-Setup.exe"):
        assert (downloads / name).stat().st_size > 10_000_000

pattern = re.compile(r"Heyi-Recruiter-(?:[\d.]+-)?(?:mac-arm64\.(?:dmg|zip)|windows-x64-Setup\.exe)(?:\.new)?")
paths = []
for parent in (Path("/opt/heyi/releases"), Path("/opt/heyi/deployments"),
               Path("/opt/heyi/backups"), Path("/opt/heyi/hotfix"), downloads):
    for path in parent.rglob("*"):
        if not path.is_symlink() and path.is_file() and pattern.fullmatch(path.name):
            if not args.all_server_copies and (path.is_relative_to(release) or
                    (path.parent == downloads and not path.name.endswith(".new"))):
                continue
            paths.append(path)
records, inodes = [], {}
for path in paths:
    stat = path.stat()
    sha = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            sha.update(chunk)
    assert path.stat().st_mtime_ns == stat.st_mtime_ns and path.stat().st_size == stat.st_size
    inode = (stat.st_dev, stat.st_ino)
    entry = inodes.setdefault(inode, {"links": stat.st_nlink, "deletedLinks": 0,
                                    "blocks": stat.st_blocks * 512})
    entry["deletedLinks"] += 1
    records.append({"path": str(path), "size": stat.st_size, "sha256": sha.hexdigest()})
    path.unlink()
summary = {"files": len(records), "logicalBytes": sum(row["size"] for row in records),
           "freedBytes": sum(row["blocks"] for row in inodes.values() if row["links"] == row["deletedLinks"]),
           "records": records}
log = release / ("installer-cleanup-" + str(time.time_ns()) + ".json")
log.write_text(json.dumps(summary, indent=2))
log.chmod(0o600)
print(json.dumps({key: value for key, value in summary.items() if key != "records"}))
