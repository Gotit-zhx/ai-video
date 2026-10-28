"""Overlay only TikTok SKU ordering onto the exact current application image."""
import importlib.util
import io
from pathlib import Path
import subprocess
import sys
import zipfile

ROOT = Path(sys.argv[1]).resolve()
spec = importlib.util.spec_from_file_location(
    "size_order_release_base",
    "/opt/heyi/releases/finance-groups-20261022/release-finance-company-groups.py")
base = importlib.util.module_from_spec(spec)
spec.loader.exec_module(base)
core, APP = base.core, base.APP
PREFIX = "BOOT-INF/classes/com/yingdao/aicommerce/product/application/provider/"
FAMILIES = {"TikTokShopOfficialPublishingProvider", "TikTokSkuOrdering"}
IMAGE = "ai-commerce-platform:tiktok-size-order-20261028"


def replaced(name):
    return any(name == PREFIX + item + ".class" or name.startswith(PREFIX + item + "$")
               for item in FAMILIES)


def snapshot():
    ROOT.mkdir(parents=True, exist_ok=True, mode=0o700)
    assert not (ROOT / "snapshot.json").exists()
    before = {name: core.inspect(name) for name in
              (APP, "ai-commerce-frontend", "ai-commerce-mysql", "ai-commerce-redis")}
    core.write_json(ROOT / "before-private.json", before)
    value = core.run("docker", "exec", APP, "cat", "/app/app.jar")
    (ROOT / "previous.jar").write_bytes(value)
    (ROOT / "previous.jar").chmod(0o600)
    with zipfile.ZipFile(io.BytesIO(value)) as jar:
        core.write_json(ROOT / "snapshot.json", {
            "jar": core.sha(value),
            "schema": base.sql("SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1"),
            "classes": {name: core.sha(jar.read(name)) for name in jar.namelist() if replaced(name)}})
    print("Exact application snapshot saved; no production change.")


def build():
    before, snapshot, manifest = (core.read_json(name) for name in
                                 ("before-private.json", "snapshot.json", "manifest.json"))
    assert not (ROOT / "prepared.json").exists()
    assert core.inspect(APP)["Id"] == before[APP]["Id"]
    old_bytes = core.run("docker", "exec", APP, "cat", "/app/app.jar")
    assert core.sha(old_bytes) == snapshot["jar"]
    stage = ROOT / "image"
    stage.mkdir()
    with zipfile.ZipFile(io.BytesIO(old_bytes)) as old, zipfile.ZipFile(stage / "app.jar", "w") as new:
        assert {name: core.sha(old.read(name)) for name in old.namelist() if replaced(name)} == snapshot["classes"]
        new.comment = old.comment
        for entry in old.infolist():
            if not replaced(entry.filename):
                new.writestr(entry, old.read(entry.filename))
        for name, digest in manifest["classes"].items():
            assert replaced(name)
            value = (ROOT / "classes" / name.removeprefix("BOOT-INF/classes/")).read_bytes()
            assert core.sha(value) == digest
            new.writestr(name, value, compress_type=zipfile.ZIP_DEFLATED)
    with zipfile.ZipFile(io.BytesIO(old_bytes)) as old, zipfile.ZipFile(stage / "app.jar") as new:
        assert set(new.namelist()) == {name for name in old.namelist() if not replaced(name)} | set(manifest["classes"])
        assert all(old.read(name) == new.read(name) for name in old.namelist() if not replaced(name))
    (stage / "Dockerfile").write_text("FROM " + before[APP]["Image"] + "\nCOPY app.jar /app/app.jar\n")
    core.run("docker", "build", "--pull=false", "-t", IMAGE, str(stage))
    core.write_json(ROOT / "prepared.json", {"image": IMAGE, "jarHash": core.sha((stage / "app.jar").read_bytes())})
    print("SKU ordering image built; unrelated code and all migrations preserved.")


def verify():
    prepared, snapshot, before = (core.read_json(name) for name in
                                 ("prepared.json", "snapshot.json", "before-private.json"))
    core.health(APP)
    assert core.inspect(APP)["Config"]["Image"] == prepared["image"]
    assert core.sha(core.run("docker", "exec", APP, "cat", "/app/app.jar")) == prepared["jarHash"]
    assert base.sql("SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1") == snapshot["schema"]
    assert core.configuration(core.inspect(APP)) == core.configuration(before[APP])
    assert all(core.inspect(name)["Id"] == info["Id"] for name, info in before.items() if name != APP)
    code = core.run("curl", "--noproxy", "*", "-sS", "-o", "/dev/null", "-w", "%{http_code}",
                    "--max-time", "10", "--resolve", "hoyee.org.cn:443:127.0.0.1",
                    "https://hoyee.org.cn/api/v1/shops/none/publish-options").decode()
    assert code == "401"
    print("Live image, health, authentication, schema and other service identities verified.")


def deploy():
    assert not (ROOT / "activation.json").exists()
    before = core.read_json("before-private.json")
    assert all(core.inspect(name)["Id"] == info["Id"] for name, info in before.items())
    base.release.idle()
    assert base.sql("SELECT COUNT(*) FROM finance_sync_states WHERE status='RUNNING' AND lease_until>UTC_TIMESTAMP(6)") == "0", "Finance request in progress"
    assert base.sql("SELECT COUNT(*) FROM finance_ad_accounts WHERE status='RUNNING' AND lease_until>UTC_TIMESTAMP(6)") == "0"
    assert base.sql("SELECT COUNT(*) FROM product_publish_workflows WHERE status='RUNNING' AND locked_until>UTC_TIMESTAMP(6)") == "0", "Product upload in progress"
    assert base.sql("SELECT COUNT(*) FROM finance_authorization_sessions WHERE status='PROCESSING' AND expires_at>UTC_TIMESTAMP(6)") == "0"
    rollback = IMAGE + "-before"
    core.run("docker", "tag", before[APP]["Image"], rollback)
    try:
        core.activate(before, APP, IMAGE)
        verify()
        core.write_json(ROOT / "activation.json", {
            "image": IMAGE, "rollbackImage": rollback, "unrelatedJarEntriesPreserved": True,
            "schemaChanges": False, "configurationPreserved": True})
    except BaseException:
        core.activate(before, APP, rollback)
        core.health(APP)
        raise
    print("TikTok size ordering deployed. Existing marketplace drafts were not rewritten.")


base.deploy = deploy
if __name__ == "__main__":
    try:
        {"snapshot": snapshot, "build": build, "verify": verify, "drain-deploy": base.drain_deploy}[sys.argv[2]]()
    except subprocess.CalledProcessError:
        raise SystemExit("Release command failed; activation automatically rolls back if necessary.")
