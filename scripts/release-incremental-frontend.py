"""Publish reviewed static files over the running frontend image; backend stays up."""
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import sys

ROOT = Path(sys.argv[1]).resolve()
spec = importlib.util.spec_from_file_location("finance_release", "/opt/heyi/releases/finance-bi-20261020/release-finance-bi.py")
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)
core = release.core
FRONT = core.FRONT
IMAGE = "ai-commerce-frontend:incremental-refresh-20261021"


def build():
    assert not (ROOT / "prepared.json").exists()
    before = {FRONT: core.inspect(FRONT)}
    core.write_json(ROOT / "before-private.json", before)
    manifest = core.read_json("manifest.json")
    folder = ROOT / "frontend-image"
    folder.mkdir()
    lines = ["FROM " + before[FRONT]["Image"]]
    for file, digest in manifest["files"].items():
        assert "/" not in file and file.endswith((".js", ".html", ".css"))
        path = "/usr/share/nginx/html/" + file
        expected = manifest["expectedFiles"][file]
        if expected is None:
            core.run("docker", "exec", FRONT, "test", "!", "-e", path)
        else:
            live = core.run("docker", "exec", FRONT, "cat", path)
            assert core.sha(live) == expected, "Live file changed; review before overwriting: " + file
        source = ROOT / "frontend" / file
        assert core.sha(source.read_bytes()) == digest
        shutil.copy2(source, folder / file)
        lines.append("COPY " + file + " " + path)
    (folder / "Dockerfile").write_text("\n".join(lines) + "\n")
    core.run("docker", "build", "-t", IMAGE, str(folder))
    core.write_json(ROOT / "prepared.json", {"image": IMAGE})
    print("Static update built with exact live-file guards.")


def verify():
    assert core.inspect(FRONT)["Config"]["Image"] == IMAGE
    core.run("docker", "exec", FRONT, "nginx", "-t")
    for file, digest in core.read_json("manifest.json")["files"].items():
        body = core.run("curl", "--noproxy", "*", "-fsS", "--max-time", "10",
                        "--resolve", "hoyee.org.cn:443:127.0.0.1", "https://hoyee.org.cn/" + file)
        assert core.sha(body) == digest, file


def deploy():
    assert not (ROOT / "activation.json").exists()
    before = core.read_json("before-private.json")
    assert core.inspect(FRONT)["Id"] == before[FRONT]["Id"]
    preserved = {x["Name"]: x["Id"] for x in json.loads(core.run("docker", "inspect", *core.run("docker", "ps", "-q").decode().split())) if x["Name"].lstrip("/") != FRONT}
    core.run("docker", "tag", before[FRONT]["Image"], IMAGE + "-before")
    try:
        core.activate(before, FRONT, IMAGE)
        verify()
        assert core.configuration(core.inspect(FRONT)) == core.configuration(before[FRONT])
        assert all(core.inspect(name)["Id"] == value for name, value in preserved.items())
    except BaseException:
        core.activate(before, FRONT, IMAGE + "-before")
        raise
    core.write_json(ROOT / "activation.json", {"image": IMAGE, "rollbackImage": IMAGE + "-before", "backendRestarted": False, "preservedContainers": preserved})
    print("Incremental refresh is live; backend and other containers unchanged.")


if __name__ == "__main__":
    try:
        {"build": build, "deploy": deploy, "verify": verify}[sys.argv[2]]()
    except subprocess.CalledProcessError as error:
        (ROOT / "failure.private.log").write_bytes(error.output or b"")
        (ROOT / "failure.private.log").chmod(0o600)
        raise SystemExit("Static release failed; private diagnostics retained.")
