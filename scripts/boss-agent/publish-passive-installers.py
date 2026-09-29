"""Publish the verified 0.1.5 installers without restarting any service or BOSS session."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

root = Path(sys.argv[1]).resolve()
downloads = Path("/opt/heyi/shared/hr-desktop-downloads")
manifest = json.loads((root / "artifacts/heyi-passive-artifacts.json").read_text())
sources = {
    "Heyi-Recruiter-mac-arm64.dmg": "Heyi-Recruiter-0.1.5-mac-arm64.dmg",
    "Heyi-Recruiter-windows-x64-Setup.exe": "Heyi-Recruiter-0.1.5-windows-x64-Setup.exe",
}
assert set(manifest) == set(sources)
assert (root / "activation.json").exists(), "Publish matching platform features first"
assert not (root / "installers-activation.json").exists(), "Installers already published"


def sha(file):
    digest = hashlib.sha256()
    with file.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def processes():
    return {name: json.loads(subprocess.check_output(["docker", "inspect", name]))[0]["Id"]
            for name in ["ai-commerce-app", "ai-commerce-frontend", "heyi-pi-runtime"]}


before = processes()
previous = json.loads((downloads / "checksums.json").read_text())
backup = root / "installer-rollback"
backup.mkdir(exist_ok=True)
for name, source in sources.items():
    file = root / "artifacts" / source
    assert file.stat().st_size == manifest[name]["size"] and sha(file) == manifest[name]["sha256"]
    assert sha(downloads / name) == previous[name]["sha256"], "Previous installer changed"
    os.link(downloads / name, backup / name)
    # Same filesystem: immutable release artifacts can share an inode with their
    # public alias, avoiding another full installer copy on the production disk.
    os.link(file, downloads / (name + ".new"))
    (downloads / (name + ".new")).chmod(0o644)
shutil.copy2(downloads / "checksums.json", backup / "checksums.json")
try:
    for name in sources:
        os.replace(downloads / (name + ".new"), downloads / name)
    temporary = downloads / "checksums.json.new"
    temporary.write_text(json.dumps(manifest, indent=2))
    os.replace(temporary, downloads / "checksums.json")
    for name, record in manifest.items():
        headers = subprocess.check_output(["curl", "--noproxy", "*", "-fsSI", "--max-time", "10",
            "--resolve", "hoyee.org.cn:443:127.0.0.1", "https://hoyee.org.cn/downloads/" + name], text=True).lower()
        assert "content-length: " + str(record["size"]) in headers
        assert "content-type: application/octet-stream" in headers
        served = subprocess.check_output(["docker", "exec", "ai-commerce-frontend",
            "sha256sum", "/usr/share/nginx/html/downloads/" + name], text=True).split()[0]
        assert served == record["sha256"]
    assert processes() == before, "Service process changed during publication"
except BaseException:
    for name in sources:
        temporary = downloads / (name + ".restore")
        os.link(backup / name, temporary)
        os.replace(temporary, downloads / name)
    shutil.copy2(backup / "checksums.json", downloads / "checksums.json")
    raise
(root / "installers-activation.json").write_text(json.dumps({
    "version": "0.1.5", "artifacts": manifest, "preservedProcesses": before}, indent=2))
print("Mac and Windows 0.1.5 installers published; hashes and download headers verified; no service restart")
