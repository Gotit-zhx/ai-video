"""Deploy captured merchant SKU naming over the exact verified production JAR."""
import importlib.util
import io
import json
import os
from pathlib import Path
import sys
import zipfile

os.umask(0o077)
PREVIOUS = Path("/opt/heyi/releases/finance-qianyi-receipt-20261103")
spec = importlib.util.spec_from_file_location("receipt", PREVIOUS / "release-finance-qianyi-receipt.py")
previous = importlib.util.module_from_spec(spec)
spec.loader.exec_module(previous)
core, storage = previous.core, previous.storage
ROOT, APP, FRONT = previous.ROOT, previous.APP, previous.FRONT
IMAGE = "ai-commerce-platform:product-sku-naming-20261104"
FAMILIES = {"ProductCaptureService", "ProductSkuNaming", "ProductSkuNamingService"}
MIGRATION = "BOOT-INF/classes/db/migration/V145__captured_merchant_sku_styles.sql"


def replaced(name):
    return (name.startswith("BOOT-INF/classes/com/yingdao/aicommerce/product/application/")
            and name.endswith(".class")
            and name.rsplit("/", 1)[-1].removesuffix(".class").split("$")[0] in FAMILIES)


def baseline():
    expected = core.read_json("baseline.json")
    before = {name: core.inspect(name) for name in (APP, FRONT)}
    assert before[APP]["Image"] == expected["appImageId"]
    jar = core.run("docker", "exec", APP, "cat", "/app/app.jar")
    assert core.sha(jar) == expected["jarHash"]
    with zipfile.ZipFile(io.BytesIO(jar)) as archive:
        for name, digest in expected["classes"].items():
            assert core.sha(archive.read(name)) == digest
    return before, jar


def build():
    qa = core.read_json("qa-passed.json")
    assert qa["unitTests"] >= 73 and qa["databaseTests"] == 3
    assert qa["publishingTests"] > 0 and qa["baselineBytecodeEqual"]
    before, old = baseline()
    folder = ROOT / "app-image"
    folder.mkdir()
    entries = {"BOOT-INF/classes/" + str(p.relative_to(ROOT / "classes")): p.read_bytes()
               for p in (ROOT / "classes").rglob("*.class")}
    assert entries and all(replaced(name) for name in entries)
    assert {name.rsplit("/", 1)[-1].removesuffix(".class").split("$")[0]
            for name in entries} == FAMILIES
    entries[MIGRATION] = (ROOT / "V145__captured_merchant_sku_styles.sql").read_bytes()
    with zipfile.ZipFile(io.BytesIO(old)) as source, zipfile.ZipFile(folder / "app.jar", "w") as target:
        assert MIGRATION not in source.namelist()
        target.comment = source.comment
        for item in source.infolist():
            if not replaced(item.filename):
                target.writestr(item, source.read(item.filename))
        for name, value in entries.items():
            target.writestr(name, value, compress_type=zipfile.ZIP_DEFLATED)
    with zipfile.ZipFile(io.BytesIO(old)) as source, zipfile.ZipFile(folder / "app.jar") as target:
        assert set(target.namelist()) == {n for n in source.namelist() if not replaced(n)} | set(entries)
        assert all(source.read(n) == target.read(n) for n in source.namelist() if not replaced(n))
    (folder / "app.jar").chmod(0o644)
    guard = json.loads(core.run("python3", "/usr/local/lib/heyi/compact-release-image.py",
        "--prepare", before[APP]["Image"], IMAGE + "-base").decode())
    core.write_json(ROOT / "image-guard.json", guard)
    (folder / "Dockerfile").write_text("FROM " + guard["baseImage"] + "\nCOPY app.jar /app/app.jar\n")
    core.run("docker", "build", "--pull=false", "-t", IMAGE, str(folder))
    core.write_json(ROOT / "prepared.json", {
        "sourceImageId": before[APP]["Image"], "sourceJarHash": core.sha(old),
        "jarHash": core.sha((folder / "app.jar").read_bytes()),
        "classes": {n: core.sha(v) for n, v in entries.items()},
        "unrelatedEntriesPreserved": True})
    print("Three reviewed product class families and additive SKU catalog prepared.", flush=True)


def verify():
    core.health(APP)
    app = core.inspect(APP)
    assert app["Config"]["Image"] == IMAGE
    assert app["HostConfig"]["LogConfig"]["Type"] == "none"
    assert core.sha(core.run("docker", "exec", APP, "cat", "/app/app.jar")) == core.read_json("prepared.json")["jarHash"]
    assert previous.month.priority.sql(
        "SELECT COUNT(*) FROM flyway_schema_history WHERE version='145' AND success=1").strip() == "1"
    for endpoint, expected in (("/", "200"), ("/api/v1/product-captures", "401"),
                               ("/api/v1/finance/overview", "401")):
        value = core.run("curl", "--noproxy", "*", "-sS", "--max-time", "10",
                         "--resolve", "hoyee.org.cn:443:127.0.0.1", "-o", "/dev/null",
                         "-w", "%{http_code}", "https://hoyee.org.cn" + endpoint).decode()
        assert value == expected, (endpoint, value)
    print("Live image, additive schema, health and access protection verified.", flush=True)


def deploy():
    assert not (ROOT / "activation.json").exists()
    before, _ = baseline()
    # The existing queue helper drains finance, Qianyi and label workers. Also
    # avoid interrupting marketplace create requests or authorization callbacks.
    for table, condition in (
        ("product_publish_workflows", "status='RUNNING' AND locked_until>UTC_TIMESTAMP(6)"),
        ("finance_authorization_sessions", "status='PROCESSING' AND expires_at>UTC_TIMESTAMP(6)"),
    ):
        assert previous.month.priority.sql("SELECT COUNT(*) FROM " + table + " WHERE " + condition).strip() == "0"
    rollback = before[APP]["Config"]["Image"]
    try:
        core.run("docker", "stop", "--timeout", "120", APP)
        storage.activate(before, APP, IMAGE)
        verify()
        assert core.inspect(FRONT)["Id"] == before[FRONT]["Id"]
        for name in (APP, FRONT):
            assert core.configuration(core.inspect(name)) == core.configuration(before[name])
    except BaseException:
        storage.activate(before, APP, rollback)
        core.health(APP)
        raise
    core.write_json(ROOT / "activation.json", {
        "image": IMAGE, "rollbackImage": rollback, "configurationPreserved": True,
        "unrelatedJarEntriesPreserved": True, "frontendUnchanged": True, "persistentLogs": False})
    print("SKU naming activated; configuration and finance checkpoints preserved.", flush=True)


def drain_deploy():
    storage.queue.deploy = deploy
    storage.queue.drain_deploy()


if __name__ == "__main__":
    {"build": build, "drain-deploy": drain_deploy, "verify": verify}[sys.argv[2]]()
