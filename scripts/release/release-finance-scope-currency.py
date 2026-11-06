"""Publish currency-safe finance scope switches as a frontend-only overlay."""
import gzip
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import sys

ROOT = Path(sys.argv[1]).resolve()
spec = importlib.util.spec_from_file_location(
    "finance_release", "/opt/heyi/releases/finance-bi-20261020/release-finance-bi.py")
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)
core, FRONT = release.core, release.FRONT
FILES = {"index.html", "finance-workspace.js"}
IMAGE = "ai-commerce-frontend:finance-scope-currency-20261029"


def snapshot():
    ROOT.mkdir(parents=True, exist_ok=True, mode=0o700)
    assert not (ROOT / "snapshot.json").exists()
    core.write_json(ROOT / "before-private.json", {FRONT: core.inspect(FRONT)})
    expected = {}
    folder = ROOT / "baseline-frontend"
    folder.mkdir()
    for name in sorted(FILES):
        value = core.run("docker", "exec", FRONT, "cat", "/usr/share/nginx/html/" + name)
        (folder / name).write_bytes(value)
        expected[name] = core.sha(value)
    core.write_json(ROOT / "snapshot.json", {"expectedFiles": expected})
    print("Captured exact live frontend; backend and database untouched.")


def baseline():
    before = core.read_json("before-private.json")
    assert core.inspect(FRONT)["Id"] == before[FRONT]["Id"], "Frontend changed during review"
    for name, digest in core.read_json("manifest.json")["expectedFiles"].items():
        assert core.sha(core.run("docker", "exec", FRONT, "cat",
                                "/usr/share/nginx/html/" + name)) == digest, name
    return before


def build():
    assert not (ROOT / "prepared.json").exists()
    before, manifest = baseline(), core.read_json("manifest.json")
    assert set(manifest["files"]) == FILES == set(manifest["expectedFiles"])
    folder = ROOT / "frontend-image"
    folder.mkdir()
    lines = ["FROM " + before[FRONT]["Image"]]
    for name, digest in manifest["files"].items():
        source = ROOT / "frontend" / name
        value = source.read_bytes()
        assert core.sha(value) == digest, name
        shutil.copy2(source, folder / name)
        (folder / (name + ".gz")).write_bytes(gzip.compress(value, mtime=0))
        for target in (name, name + ".gz"):
            lines.append("COPY " + target + " /usr/share/nginx/html/" + target)
    (folder / "Dockerfile").write_text("\n".join(lines) + "\n")
    core.run("docker", "build", "-t", IMAGE, str(folder))
    core.run("docker", "run", "--rm", "--volumes-from", FRONT, IMAGE, "nginx", "-t")
    core.write_json(ROOT / "prepared.json", {"image": IMAGE})
    print("Frontend overlay and compressed assets built; Nginx validation passed.")


def verify():
    assert core.inspect(FRONT)["Config"]["Image"] == IMAGE
    core.run("docker", "exec", FRONT, "nginx", "-t")
    for name, digest in core.read_json("manifest.json")["files"].items():
        path = "/usr/share/nginx/html/" + name
        assert core.sha(core.run("docker", "exec", FRONT, "cat", path)) == digest, name
        for encoding in ("identity", "gzip"):
            body = core.run("curl", "--noproxy", "*", "-fsS", "--max-time", "15",
                            "--resolve", "hoyee.org.cn:443:127.0.0.1",
                            "-H", "Accept-Encoding: " + encoding,
                            "https://hoyee.org.cn/" + name)
            if encoding == "gzip":
                body = gzip.decompress(body)
            assert core.sha(body) == digest, name + " " + encoding
    core.health(core.APP)
    print("HTTPS identity/gzip assets match reviewed files; backend is healthy.")


def deploy():
    assert not (ROOT / "activation.json").exists()
    assert core.read_json("prepared.json")["image"] == IMAGE
    before = baseline()
    preserved = {c["Name"]: c["Id"] for c in json.loads(core.run(
        "docker", "inspect", *core.run("docker", "ps", "-q").decode().split()))
        if c["Name"].lstrip("/") != FRONT}
    core.run("docker", "tag", before[FRONT]["Image"], IMAGE + "-before")
    try:
        core.activate(before, FRONT, IMAGE)
        verify()
        current = core.inspect(FRONT)
        assert core.configuration(current) == core.configuration(before[FRONT])
        assert current["HostConfig"]["LogConfig"] == before[FRONT]["HostConfig"]["LogConfig"]
        assert all(core.inspect(name)["Id"] == identifier for name, identifier in preserved.items())
    except BaseException:
        core.activate(before, FRONT, IMAGE + "-before")
        raise
    core.write_json(ROOT / "activation.json", {
        "image": IMAGE, "rollbackImage": IMAGE + "-before", "backendRestarted": False,
        "schemaChanges": False, "preservedContainers": preserved})
    print("Finance scope fix is live; backend, sync workers and database stayed running.")


if __name__ == "__main__":
    try:
        {"snapshot": snapshot, "build": build, "deploy": deploy, "verify": verify}[sys.argv[2]]()
    except subprocess.CalledProcessError:
        raise SystemExit("Frontend release failed; activation rolls back automatically.")
