"""Finance-only backend correction; preserve the deployed migration, UI and all other services."""
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import sys
import zipfile

ROOT = Path(sys.argv[1]).resolve()
BASE = Path("/opt/heyi/releases/finance-bi-20261020")
spec = importlib.util.spec_from_file_location("finance_release", BASE / "release-finance-bi.py")
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)
core = release.core
APP = core.APP
PREFIX = "BOOT-INF/classes/com/yingdao/aicommerce/finance/"
TAG = "ai-commerce-platform:finance-bi-proxy-fix-20261020"


def main():
    ROOT.mkdir(parents=True, exist_ok=True, mode=0o700)
    assert not (ROOT / "activation.json").exists()
    expected = json.loads((BASE / "manifest.json").read_text())
    payload = json.loads((ROOT / "patch-manifest.json").read_text())
    before = core.inspect(APP)
    core.write_json(ROOT / "before-private.json", {APP: before})
    previous = core.run("docker", "exec", APP, "cat", "/app/app.jar")
    (ROOT / "previous.jar").write_bytes(previous)
    (ROOT / "previous.jar").chmod(0o600)
    directory = ROOT / "image"
    directory.mkdir(exist_ok=False)
    with zipfile.ZipFile(io.BytesIO(previous)) as old, zipfile.ZipFile(directory / "app.jar", "w") as new:
        old_names = {n for n in old.namelist() if n.startswith(PREFIX)}
        assert old_names == set(payload), "Unexpected finance classes; review before patching"
        assert all(core.sha(old.read(n)) == expected["classes"][n] for n in old_names), "Finance changed since release"
        new.comment = old.comment
        for info in old.infolist():
            if info.filename not in payload:
                new.writestr(info, old.read(info.filename))
        for name, digest in payload.items():
            data = (ROOT / "classes" / name.removeprefix("BOOT-INF/classes/")).read_bytes()
            assert core.sha(data) == digest
            new.writestr(name, data, compress_type=zipfile.ZIP_DEFLATED)
    with zipfile.ZipFile(io.BytesIO(previous)) as old, zipfile.ZipFile(directory / "app.jar") as new:
        assert set(old.namelist()) == set(new.namelist())
        assert all(old.read(n) == new.read(n) for n in old.namelist() if n not in payload)
    (directory / "Dockerfile").write_text("FROM " + before["Image"] + "\nCOPY app.jar /app/app.jar\n")
    core.run("docker", "build", "-t", TAG, str(directory))
    release.idle()
    assert core.inspect(APP)["Id"] == before["Id"], "Application changed during build"
    preserved = {item["Name"]: item["Id"] for item in json.loads(core.run("docker", "inspect", *core.run("docker", "ps", "-q").decode().split()))
                 if item["Name"].lstrip("/") != APP}
    rollback = TAG + "-before"
    core.run("docker", "tag", before["Image"], rollback)
    try:
        core.activate({APP: before}, APP, TAG)
        core.health(APP)
        assert core.configuration(core.inspect(APP)) == core.configuration(before)
        assert all(core.inspect(n)["Id"] == identifier for n, identifier in preserved.items())
        assert core.sha(core.run("docker", "exec", APP, "cat", "/app/app.jar")) == core.sha((directory / "app.jar").read_bytes())
        core.write_json(ROOT / "activation.json", {"image": TAG, "rollbackImage": rollback,
            "configurationPreserved": True, "otherContainersPreserved": True, "migrationChanged": False,
            "frontendChanged": False, "classes": payload})
    except BaseException:
        core.activate({APP: before}, APP, rollback)
        core.health(APP)
        raise
    print("Finance backend dependency patch active; migration, frontend and other services unchanged.")


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError as error:
        (ROOT / "failure.private.log").write_bytes(error.output or b"")
        (ROOT / "failure.private.log").chmod(0o600)
        raise SystemExit("Patch command failed; private diagnostics retained.")
