"""Publish partial settlement amounts and visible recovery progress without restarting the backend."""
import gzip
import importlib.util
from pathlib import Path
import shutil
import subprocess
import sys

ROOT = Path(sys.argv[1]).resolve()
spec = importlib.util.spec_from_file_location(
    "static_release",
    "/opt/heyi/releases/incremental-refresh-20261021/release-incremental-frontend.py",
)
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)
core, FRONT = release.core, release.FRONT
release.IMAGE = "ai-commerce-frontend:finance-settlement-display-20261027"
FILES = {"index.html", "finance-workspace.js", "finance-workspace.css"}


def build():
    assert not (ROOT / "prepared.json").exists()
    before = {FRONT: core.inspect(FRONT)}
    core.write_json(ROOT / "before-private.json", before)
    manifest = core.read_json("manifest.json")
    assert set(manifest["files"]) == FILES
    folder = ROOT / "frontend-image"
    folder.mkdir()
    lines = ["FROM " + before[FRONT]["Image"]]
    for name, digest in manifest["files"].items():
        destination = "/usr/share/nginx/html/" + name
        live = core.run("docker", "exec", FRONT, "cat", destination)
        assert core.sha(live) == manifest["expectedFiles"][name], "Live file changed: " + name
        source = ROOT / "frontend" / name
        assert core.sha(source.read_bytes()) == digest
        shutil.copy2(source, folder / name)
        (folder / (name + ".gz")).write_bytes(gzip.compress(source.read_bytes(), mtime=0))
        lines.extend(["COPY " + name + " " + destination, "COPY " + name + ".gz " + destination + ".gz"])
    (folder / "Dockerfile").write_text("\n".join(lines) + "\n")
    core.run("docker", "build", "-t", release.IMAGE, str(folder))
    core.write_json(ROOT / "prepared.json", {"image": release.IMAGE})
    print("Frontend built with exact live-file guards and matching gzip assets.")


def verify():
    assert core.inspect(FRONT)["Config"]["Image"] == release.IMAGE
    core.run("docker", "exec", FRONT, "nginx", "-t")
    for name, digest in core.read_json("manifest.json")["files"].items():
        for encoding in ("identity", "gzip"):
            value = core.run(
                "curl", "--noproxy", "*", "-fsS", "--max-time", "10",
                "--resolve", "hoyee.org.cn:443:127.0.0.1",
                "-H", "Accept-Encoding: " + encoding, "https://hoyee.org.cn/" + name,
            )
            if value.startswith(b"\x1f\x8b"):
                value = gzip.decompress(value)
            assert core.sha(value) == digest, name + " " + encoding


release.verify = verify
try:
    {"build": build, "deploy": release.deploy, "verify": verify}[sys.argv[2]]()
except subprocess.CalledProcessError as error:
    (ROOT / "failure.private.log").write_bytes(error.output or b"")
    (ROOT / "failure.private.log").chmod(0o600)
    raise SystemExit("Frontend release failed; private diagnostics retained.")
