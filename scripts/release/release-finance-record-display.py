"""Publish the finance record presentation fix without restarting the backend."""
import gzip
import importlib.util
from pathlib import Path
import shutil
import subprocess
import sys

ROOT = Path(sys.argv[1]).resolve()
spec = importlib.util.spec_from_file_location(
    "static_release",
    "/opt/heyi/releases/incremental-refresh-20261021/release-incremental-frontend.py")
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)
core, FRONT = release.core, release.FRONT
release.IMAGE = "ai-commerce-frontend:finance-record-compact-20261022"
FILES = {"index.html", "finance-workspace.js", "finance-workspace.css"}


def build():
    assert not (ROOT / "prepared.json").exists()
    manifest = core.read_json("manifest.json")
    assert set(manifest["files"]) == FILES
    before = {FRONT: core.inspect(FRONT)}
    core.write_json(ROOT / "before-private.json", before)
    folder = ROOT / "frontend-image"
    folder.mkdir()
    lines = ["FROM " + before[FRONT]["Image"]]
    for file, digest in manifest["files"].items():
        path = "/usr/share/nginx/html/" + file
        live = core.run("docker", "exec", FRONT, "cat", path)
        assert core.sha(live) == manifest["expectedFiles"][file], "Live file changed: " + file
        source = ROOT / "frontend" / file
        assert core.sha(source.read_bytes()) == digest
        shutil.copy2(source, folder / file)
        (folder / (file + ".gz")).write_bytes(gzip.compress(source.read_bytes(), mtime=0))
        lines.extend(["COPY " + file + " " + path, "COPY " + file + ".gz " + path + ".gz"])
    (folder / "Dockerfile").write_text("\n".join(lines) + "\n")
    core.run("docker", "build", "-t", release.IMAGE, str(folder))
    core.write_json(ROOT / "prepared.json", {"image": release.IMAGE})
    print("Three static files and matching compressed copies built with exact baseline guards.")


original_verify = release.verify


def verify():
    original_verify()
    for file, digest in core.read_json("manifest.json")["files"].items():
        body = core.run("curl", "--noproxy", "*", "-fsS", "--max-time", "10",
                        "--resolve", "hoyee.org.cn:443:127.0.0.1",
                        "-H", "Accept-Encoding: gzip", "https://hoyee.org.cn/" + file)
        decoded = gzip.decompress(body) if body.startswith(b"\x1f\x8b") else body
        assert core.sha(decoded) == digest, "Compressed content mismatch: " + file


release.verify = verify
if __name__ == "__main__":
    try:
        {"build": build, "deploy": release.deploy, "verify": verify}[sys.argv[2]]()
    except subprocess.CalledProcessError as error:
        (ROOT / "failure.private.log").write_bytes(error.output or b"")
        (ROOT / "failure.private.log").chmod(0o600)
        raise SystemExit("Finance presentation release failed; private diagnostics retained.")
