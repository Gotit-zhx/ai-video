"""App-only prompt overlay; preserve runtime, paid request ledgers and existing outputs."""
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

HERE = Path(__file__).resolve()
library = HERE.with_name("release-core.py")
if not library.exists():
    library = HERE.with_name("release-banana-default.py")
spec = importlib.util.spec_from_file_location("image_release", library)
core = importlib.util.module_from_spec(spec)
spec.loader.exec_module(core)
core.PREFIXES = ["com/yingdao/aicommerce/video/application/AiVideoImageProcessor"]
ROOT, APP = core.ROOT, core.APP
TAG = "video-brightness-20261020"
TESTS = ("AiVideoLightingPromptTest", "AiVideoImageProcessorTest", "AiVideoImageAttemptsTest",
         "AiVideoImageGatewayTest", "AiVideoTaskRegenerationPromptTest")


def query(sql):
    return core.run("docker", "exec", "-i", "ai-commerce-mysql", "sh", "-c",
                    'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -NB "$MYSQL_DATABASE"',
                    data=sql.encode()).decode().splitlines()


def idle():
    core.idle()
    count = query("""
        SELECT COUNT(*) FROM ai_video_generation_jobs
        WHERE status IN ('SUBMITTING','REVIEWING')
        OR (status IN ('QUEUED','RUNNING') AND
            (provider_task_id IS NULL OR provider_task_id=''
             OR next_poll_at > DATE_ADD(UTC_TIMESTAMP(), INTERVAL 120 SECOND)))
        """)
    assert count == ["0"], "Video submission, review or polling lease is active; no restart"
    print("No active paid request or video polling lease; persisted provider jobs remain resumable.")


def pack():
    source, baseline = Path(sys.argv[3]).resolve(), Path(sys.argv[4]).resolve()
    ROOT.mkdir(mode=0o700, exist_ok=False)
    manifest = {"classes": {}, "expectedClasses": {},
                "sourceCommit": core.run("git", "-C", str(source), "rev-parse", "HEAD").decode().strip()}
    tests = {}
    for test in TESTS:
        report = ET.parse(source / "target/surefire-reports" /
                          ("TEST-com.yingdao.aicommerce.video.application." + test + ".xml")).getroot()
        counts = {key: int(report.get(key, 0)) for key in ("tests", "failures", "errors", "skipped")}
        assert counts["tests"] > 0 and not any(counts[key] for key in ("failures", "errors", "skipped")), test
        tests[test] = counts
    manifest["tests"] = tests
    prefix = core.PREFIXES[0]
    for folder, key in [(baseline, "expectedClasses"), (source / "target/classes", "classes")]:
        stem = folder / prefix
        matches = list(stem.parent.glob(stem.name + ".class")) + list(stem.parent.glob(stem.name + "$*.class"))
        assert matches, "Missing compiled processor"
        for item in matches:
            relative = str(item.relative_to(folder))
            manifest[key]["BOOT-INF/classes/" + relative] = core.sha(item.read_bytes())
            if key == "classes":
                target = ROOT / "classes" / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(item, target)
    shutil.copy2(HERE, ROOT / "release.py")
    shutil.copy2(library, ROOT / "release-core.py")
    core.write_json(ROOT / "manifest.json", manifest)
    print("Packed processor class family and passing focused tests.")


def build():
    assert not (ROOT / "prepared.json").exists()
    manifest = core.read_json("manifest.json")
    before = core.inspect(APP)
    core.write_json(ROOT / "before-private.json", before)
    previous = ROOT / "previous.jar"
    previous.write_bytes(core.run("docker", "exec", APP, "cat", "/app/app.jar"))
    previous.chmod(0o600)
    directory = ROOT / "app-image"
    directory.mkdir()
    with zipfile.ZipFile(previous) as old, zipfile.ZipFile(directory / "app.jar", "w") as new:
        assert {name: core.sha(old.read(name)) for name in old.namelist() if core.replaced(name)} == \
               manifest["expectedClasses"], "Live processor differs from reviewed baseline"
        new.comment = old.comment
        for entry in old.infolist():
            if not core.replaced(entry.filename):
                new.writestr(entry, old.read(entry.filename))
        for name, digest in manifest["classes"].items():
            content = (ROOT / "classes" / name.removeprefix("BOOT-INF/classes/")).read_bytes()
            assert core.sha(content) == digest
            new.writestr(name, content, compress_type=zipfile.ZIP_DEFLATED)
    with zipfile.ZipFile(previous) as old, zipfile.ZipFile(directory / "app.jar") as new:
        unchanged = {name for name in old.namelist() if not core.replaced(name)}
        assert set(new.namelist()) == unchanged | set(manifest["classes"])
        assert all(old.read(name) == new.read(name) for name in unchanged)
    image = "ai-commerce-platform:" + TAG
    (directory / "Dockerfile").write_text("FROM " + before["Image"] + "\nCOPY app.jar /app/app.jar\n")
    core.run("docker", "build", "-t", image, str(directory))
    core.write_json(ROOT / "prepared.json", {"image": image,
                    "oldJarHash": core.sha(previous.read_bytes()),
                    "jarHash": core.sha((directory / "app.jar").read_bytes())})
    print("Built app-only overlay; all unrelated JAR entries retained byte-for-byte.")


def deploy():
    assert not (ROOT / "activation.json").exists(), "Already activated"
    before, prepared = core.read_json("before-private.json"), core.read_json("prepared.json")
    assert core.inspect(APP)["Id"] == before["Id"], "Concurrent app deployment"
    assert core.sha(core.run("docker", "exec", APP, "cat", "/app/app.jar")) == prepared["oldJarHash"]
    idle()
    preserved = {item["Name"]: item["Id"] for item in json.loads(core.run(
        "docker", "inspect", *core.run("docker", "ps", "-q").decode().split()))
        if item["Name"].lstrip("/") != APP}
    rollback = "ai-commerce-platform:" + TAG + "-before"
    core.run("docker", "tag", before["Image"], rollback)
    core.run("docker", "stop", "--time", "60", APP)
    try:
        core.activate({APP: before}, APP, prepared["image"])
        core.health(APP)
        assert core.configuration(core.inspect(APP)) == core.configuration(before), "Configuration drift"
        assert all(core.inspect(name)["Id"] == value for name, value in preserved.items())
        assert core.sha(core.run("docker", "exec", APP, "cat", "/app/app.jar")) == prepared["jarHash"]
    except BaseException:
        core.activate({APP: before}, APP, rollback)
        core.health(APP)
        raise
    core.write_json(ROOT / "activation.json", {"image": prepared["image"], "rollbackImage": rollback,
                    "preservedContainers": preserved, "configurationPreserved": True,
                    "databaseModifiedByDeployment": False, "paidCalls": 0})
    verify()


def verify():
    prepared, activation = core.read_json("prepared.json"), core.read_json("activation.json")
    core.health(APP)
    assert core.inspect(APP)["Config"]["Image"] == prepared["image"]
    assert core.sha(core.run("docker", "exec", APP, "cat", "/app/app.jar")) == prepared["jarHash"]
    assert all(core.inspect(name)["Id"] == value for name, value in activation["preservedContainers"].items())
    core.write_json(ROOT / "verification.json", {"appHealthy": True, "jarHash": prepared["jarHash"],
                    "runtimeAndFrontendUnchanged": True, "paidCalls": 0})
    print("Verified healthy app, reviewed JAR, unchanged runtime/frontend and no generated test images.")


if __name__ == "__main__":
    try:
        {"pack": pack, "build": build, "idle": idle, "deploy": deploy, "verify": verify}[core.MODE]()
    except subprocess.CalledProcessError as error:
        path = ROOT / ("failure-" + core.MODE + ".log")
        path.write_bytes(error.output or b"")
        path.chmod(0o600)
        print("Command failed; private diagnostics: " + str(path), file=sys.stderr)
        raise SystemExit(1)
