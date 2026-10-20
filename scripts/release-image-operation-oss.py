"""Runtime-only OSS recovery-storage rollout; preserve app, model routes and task ledgers."""
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

HERE = Path(__file__).resolve()
library = HERE.with_name("release-core.py")
if not library.exists():
    library = HERE.with_name("release-banana-default.py")
spec = importlib.util.spec_from_file_location("release_core", library)
core = importlib.util.module_from_spec(spec)
spec.loader.exec_module(core)
ROOT, PI = core.ROOT, core.PI
TAG = "image-operation-oss-20261020-v2"
FILES = ("image-operation-store.js", "image-operation-oss.js")
TESTS = ("image-operation-oss.test.mjs", "image-operation-store.test.mjs",
         "image-network-recovery.test.mjs", "image-network-fallback-integration.test.mjs",
         "image-model-fallback.test.mjs", "image-model-fallback-http.test.mjs",
         "image-progress.test.mjs", "image-public-errors.test.mjs", "image-stream-response.test.mjs")


def pack():
    source = Path(sys.argv[3]).resolve()
    baseline_commit = sys.argv[4] if len(sys.argv) > 4 else "f29c0b9"
    ROOT.mkdir(mode=0o700, exist_ok=False)
    for name in FILES:
        shutil.copy2(source / "pi-runtime/src" / name, ROOT / name)
    (ROOT / "tests").mkdir()
    for name in TESTS:
        shutil.copy2(source / "pi-runtime/test" / name, ROOT / "tests" / name)
    shutil.copy2(HERE, ROOT / "release.py")
    shutil.copy2(library, ROOT / "release-core.py")
    manifest = {"sourceCommit": core.run("git", "-C", str(source), "rev-parse", "HEAD").decode().strip(),
                "files": {name: core.sha((ROOT / name).read_bytes()) for name in FILES},
                "baselineCommit": baseline_commit,
                "baseline": core.sha(core.run("git", "-C", str(source), "show",
                                             baseline_commit + ":pi-runtime/src/image-operation-store.js"))}
    core.write_json(ROOT / "manifest.json", manifest)
    print("Packed runtime storage adapter; model adapters and server handlers unchanged.")


def build():
    assert not (ROOT / "prepared.json").exists()
    before = core.inspect(PI)
    manifest = core.read_json("manifest.json")
    assert core.sha(core.run("docker", "exec", PI, "cat", "/app/src/image-operation-store.js")) == manifest["baseline"]
    core.write_json(ROOT / "before-private.json", before)
    directory = ROOT / "image"
    directory.mkdir()
    for name in FILES:
        assert core.sha((ROOT / name).read_bytes()) == manifest["files"][name]
        shutil.copy2(ROOT / name, directory / name)
    (directory / "Dockerfile").write_text(
        "FROM " + before["Image"] + "\nUSER root\n"
        "RUN npm install --prefix /opt/image-oss-sdk ali-oss@6.23.0 --omit=dev --ignore-scripts "
        "--no-audit --no-fund && npm cache clean --force\n"
        "COPY image-operation-store.js image-operation-oss.js /app/src/\nUSER "
        + before["Config"]["User"] + "\n")
    image = "heyi-pi-runtime:" + TAG
    core.run("docker", "build", "-t", image, str(directory))
    tests = core.run("docker", "run", "--rm", "--network", "none",
                    "-e", "IMAGE_OPERATION_OSS_SDK_MODULE=file:///opt/image-oss-sdk/node_modules/ali-oss/lib/client.js",
                    "-v", str(ROOT / "tests") + ":/app/test:ro",
                    image, "node", "--test", *("/app/test/" + name for name in TESTS))
    (ROOT / "tests.log").write_bytes(tests)
    app = core.inspect(core.APP)
    env = dict(value.split("=", 1) for value in app["Config"]["Env"])
    destination = env["APP_DAYAN_OSS_CREDENTIALS_FILE"]
    mount = next(m for m in app["Mounts"] if m["Destination"] == destination)
    assert mount["RW"] is False and env["APP_DAYAN_OSS_BUCKET"] == "test-hoyee"
    core.write_json(ROOT / "prepared.json", {"image": image, "bucket": env["APP_DAYAN_OSS_BUCKET"],
                    "credentialSource": mount["Source"], "credentialTarget": destination})
    print("Built isolated OSS SDK and storage adapter on the exact live runtime image.")


def activate(before, image, migrate):
    labels = before["Config"]["Labels"]
    command = ["docker", "compose", "--project-directory", labels["com.docker.compose.project.working_dir"],
               "-p", labels["com.docker.compose.project"]]
    for name in labels["com.docker.compose.project.config_files"].split(","):
        command += ["-f", name]
    prepared = core.read_json("prepared.json")
    override = ROOT / ("compose.oss.json" if migrate else "compose.oss-reader-only.json")
    core.write_json(override, {"services": {labels["com.docker.compose.service"]: {
        "image": image, "environment": {
            "IMAGE_OPERATION_OSS_BUCKET": prepared["bucket"],
            "IMAGE_OPERATION_OSS_CREDENTIALS_FILE": prepared["credentialTarget"],
            "IMAGE_OPERATION_OSS_SDK_MODULE": "file:///opt/image-oss-sdk/node_modules/ali-oss/lib/client.js",
            "IMAGE_OPERATION_OSS_MIGRATE": "true" if migrate else "false",
        }, "volumes": [prepared["credentialSource"] + ":" + prepared["credentialTarget"] + ":ro"]}}})
    core.run(*command, "-f", str(override), "up", "-d", "--no-deps", "--no-build", "--pull", "never",
             labels["com.docker.compose.service"],
             env=dict(os.environ, HR_FRONTEND_IMAGE=core.inspect(core.FRONT)["Config"]["Image"]))


def deploy():
    assert not (ROOT / "activation.json").exists()
    before, prepared = core.read_json("before-private.json"), core.read_json("prepared.json")
    assert core.inspect(PI)["Id"] == before["Id"], "Concurrent deployment"
    core.idle()
    preserved = {item["Name"]: item["Id"] for item in json.loads(core.run(
        "docker", "inspect", *core.run("docker", "ps", "-q").decode().split()))
        if item["Name"].lstrip("/") != PI}
    core.run("docker", "tag", before["Image"], "heyi-pi-runtime:" + TAG + "-before")
    try:
        activate(before, prepared["image"], False)
        # Enable migration only after reader-only startup and configuration verification pass.
        core.health(PI)
        verify(preserved)
    except BaseException:
        core.activate({PI: before}, PI, "heyi-pi-runtime:" + TAG + "-before")
        core.health(PI)
        raise
    core.write_json(ROOT / "activation.json", {"image": prepared["image"], "preservedContainers": preserved,
                    "migrationEnabled": False, "rollbackAfterMigration": "Use reader-only mode; do not remove the OSS reader."})
    print("Reader-only runtime is healthy. Historical results have not been migrated by this deployment.")


def enable():
    activation = core.read_json("activation.json")
    assert not activation["migrationEnabled"]
    assert core.read_json("probe.json")["readbackVerified"]
    core.idle()
    current = core.inspect(PI)
    try:
        activate(current, core.read_json("prepared.json")["image"], True)
        core.health(PI)
        verify(activation["preservedContainers"])
    except BaseException:
        # Some indexes may already exist. Roll back to the new reader, never to
        # the old image that cannot hydrate results and might repeat paid work.
        activate(core.inspect(PI), core.read_json("prepared.json")["image"], False)
        core.health(PI)
        raise
    activation["migrationEnabled"] = True
    core.write_json(ROOT / "activation.json", activation)
    print("Background migration enabled after healthy reader-only verification.")


def probe():
    prepared = core.read_json("prepared.json")
    script = """
import {createHash,randomUUID,randomBytes} from 'node:crypto';
import {createImageOperationOss} from '/app/src/image-operation-oss.js';
const archive=await createImageOperationOss();
const key=createHash('sha256').update('storage-probe-'+randomUUID()).digest('hex');
const bytes=randomBytes(9*1024*1024);
let pointer;
try {
  pointer=await archive.store(key,bytes);
  if (!(await archive.read(key,pointer)).equals(bytes)) throw Error('Mismatch');
  console.log(JSON.stringify({readbackVerified:true,bucket:pointer.bucket,bytes:bytes.length,paidCalls:0}));
} finally {if(pointer)await archive.client.delete(pointer.key);}
"""
    result = core.run("docker", "run", "--rm", "-i", "--entrypoint", "node",
        "-e", "IMAGE_OPERATION_OSS_BUCKET=" + prepared["bucket"],
        "-e", "IMAGE_OPERATION_OSS_CREDENTIALS_FILE=" + prepared["credentialTarget"],
        "-e", "IMAGE_OPERATION_OSS_SDK_MODULE=file:///opt/image-oss-sdk/node_modules/ali-oss/lib/client.js",
        "-v", prepared["credentialSource"] + ":" + prepared["credentialTarget"] + ":ro",
        prepared["image"], "--input-type=module", "-", data=script.encode())
    report = json.loads(result)
    assert report["readbackVerified"]
    core.write_json(ROOT / "probe.json", report)
    print(json.dumps(report))


def verify(preserved=None):
    core.health(PI)
    actual = core.inspect(PI)
    assert actual["Config"]["Image"] == core.read_json("prepared.json")["image"]
    before = core.read_json("before-private.json")
    expected_env = dict(value.split("=", 1) for value in before["Config"]["Env"])
    actual_env = dict(value.split("=", 1) for value in actual["Config"]["Env"])
    prepared = core.read_json("prepared.json")
    expected_env.update({
        "IMAGE_OPERATION_OSS_BUCKET": prepared["bucket"],
        "IMAGE_OPERATION_OSS_CREDENTIALS_FILE": prepared["credentialTarget"],
        "IMAGE_OPERATION_OSS_SDK_MODULE": "file:///opt/image-oss-sdk/node_modules/ali-oss/lib/client.js",
        "IMAGE_OPERATION_OSS_MIGRATE": actual_env.get("IMAGE_OPERATION_OSS_MIGRATE")})
    assert actual_env.get("IMAGE_OPERATION_OSS_MIGRATE") in {"true", "false"}
    assert actual_env == expected_env
    original_mounts = {(m["Source"], m["Destination"], m["RW"]) for m in before["Mounts"]}
    actual_mounts = {(m["Source"], m["Destination"], m["RW"]) for m in actual["Mounts"]}
    prepared = core.read_json("prepared.json")
    assert actual_mounts == original_mounts | {(prepared["credentialSource"], prepared["credentialTarget"], False)}
    manifest = core.read_json("manifest.json")
    for name, digest in manifest["files"].items():
        assert core.sha(core.run("docker", "exec", PI, "cat", "/app/src/" + name)) == digest
    preserved = preserved or core.read_json("activation.json")["preservedContainers"]
    assert all(core.inspect(name)["Id"] == value for name, value in preserved.items())
    print("Runtime healthy; original environment and volumes retained; all other services unchanged.")


if __name__ == "__main__":
    try:
        {"pack": pack, "build": build, "probe": probe, "deploy": deploy,
         "enable": enable, "verify": verify}[core.MODE]()
    except subprocess.CalledProcessError as error:
        target = ROOT / ("failure-" + core.MODE + ".log")
        target.write_bytes(error.output or b"")
        target.chmod(0o600)
        print("Command failed; private diagnostics: " + str(target), file=sys.stderr)
        raise SystemExit(1)
