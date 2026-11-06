"""Backend-only order-cost controller fix, with exact baseline guards and rollback."""
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import sys
import zipfile

ROOT = Path(sys.argv[1]).resolve()
spec = importlib.util.spec_from_file_location(
    "finance_base", "/opt/heyi/releases/finance-groups-20261022/release-finance-company-groups.py")
base = importlib.util.module_from_spec(spec)
spec.loader.exec_module(base)
core, APP = base.core, base.APP
FAMILIES = {"FinanceCostWorkspace", "FinanceCostController"}
PREFIX = "BOOT-INF/classes/com/yingdao/aicommerce/finance/"
IMAGE = "ai-commerce-platform:finance-cost-orders-proxy-20261023"


def replaced(name):
    return any(name == PREFIX + n + ".class" or name.startswith(PREFIX + n + "$") for n in FAMILIES)


def snapshot():
    ROOT.mkdir(parents=True, exist_ok=True, mode=0o700)
    assert not (ROOT / "snapshot.json").exists()
    core.write_json(ROOT / "before-private.json", {APP: core.inspect(APP)})
    previous = core.run("docker", "exec", APP, "cat", "/app/app.jar")
    (ROOT / "previous.jar").write_bytes(previous)
    (ROOT / "previous.jar").chmod(0o600)
    with zipfile.ZipFile(io.BytesIO(previous)) as jar:
        core.write_json(ROOT / "snapshot.json", {
            "jar": core.sha(previous),
            "expectedClasses": {n: core.sha(jar.read(n)) for n in jar.namelist() if replaced(n)}})
    print("Captured exact backend baseline; production unchanged.")


def build():
    assert not (ROOT / "prepared.json").exists()
    manifest, before, snapshot = (core.read_json(n) for n in ("manifest.json", "before-private.json", "snapshot.json"))
    assert core.inspect(APP)["Id"] == before[APP]["Id"], "Live application changed"
    previous = core.run("docker", "exec", APP, "cat", "/app/app.jar")
    assert core.sha(previous) == snapshot["jar"]
    folder = ROOT / "app-image"
    folder.mkdir()
    with zipfile.ZipFile(io.BytesIO(previous)) as old, zipfile.ZipFile(folder / "app.jar", "w") as new:
        expected = {n: core.sha(old.read(n)) for n in old.namelist() if replaced(n)}
        assert expected == manifest["expectedClasses"]
        assert set(manifest["classes"]) == set(expected)
        new.comment = old.comment
        for entry in old.infolist():
            value = old.read(entry.filename)
            if replaced(entry.filename):
                value = (ROOT / "classes" / entry.filename.removeprefix("BOOT-INF/classes/")).read_bytes()
                assert core.sha(value) == manifest["classes"][entry.filename]
            new.writestr(entry, value)
    with zipfile.ZipFile(io.BytesIO(previous)) as old, zipfile.ZipFile(folder / "app.jar") as new:
        assert set(old.namelist()) == set(new.namelist())
        assert all(old.read(n) == new.read(n) for n in old.namelist() if not replaced(n))
    (folder / "Dockerfile").write_text("FROM " + before[APP]["Image"] + "\nCOPY app.jar /app/app.jar\n")
    core.run("docker", "build", "-t", IMAGE, str(folder))
    core.write_json(ROOT / "prepared.json", {"image": IMAGE, "jarHash": core.sha((folder / "app.jar").read_bytes())})
    print("Order-cost backend overlay built; all unrelated JAR entries preserved.")


def verify():
    core.health(APP)
    assert core.inspect(APP)["Config"]["Image"] == IMAGE
    assert core.sha(core.run("docker", "exec", APP, "cat", "/app/app.jar")) == core.read_json("prepared.json")["jarHash"]
    code = core.run("curl", "--noproxy", "*", "-sS", "-o", "/dev/null", "-w", "%{http_code}",
                    "--max-time", "10", "--resolve", "hoyee.org.cn:443:127.0.0.1",
                    "https://hoyee.org.cn/api/v1/finance/workspace/costs/orders").decode()
    assert code == "401", "Anonymous cost access must remain denied"
    core.run("python3", str(ROOT / "check.py"), "acceptance")


def deploy():
    assert not (ROOT / "activation.json").exists()
    before = core.read_json("before-private.json")
    assert core.inspect(APP)["Id"] == before[APP]["Id"], "Live application changed during preparation"
    base.release.idle()
    for table, condition, message in (
        ("finance_sync_states", "status='RUNNING' AND lease_until>UTC_TIMESTAMP(6)", "Finance request in progress"),
        ("finance_ad_accounts", "status='RUNNING' AND lease_until>UTC_TIMESTAMP(6)", "Advertising request in progress"),
        ("product_publish_workflows", "status='RUNNING' AND locked_until>UTC_TIMESTAMP(6)", "Product workflow in progress"),
        ("finance_authorization_sessions", "status='PROCESSING' AND expires_at>UTC_TIMESTAMP(6)", "Authorization callback in progress"),
    ):
        assert base.sql("SELECT COUNT(*) FROM " + table + " WHERE " + condition) == "0", message
    preserved = {item["Name"]: item["Id"] for item in json.loads(core.run(
        "docker", "inspect", *core.run("docker", "ps", "-q").decode().split()))
        if item["Name"].lstrip("/") != APP}
    rollback = IMAGE + "-before"
    core.run("docker", "tag", before[APP]["Image"], rollback)
    try:
        core.activate(before, APP, IMAGE)
        verify()
        assert core.configuration(core.inspect(APP)) == core.configuration(before[APP])
        assert all(core.inspect(name)["Id"] == identifier for name, identifier in preserved.items())
        core.write_json(ROOT / "activation.json", {
            "image": IMAGE, "rollbackImage": rollback, "configurationPreserved": True,
            "otherContainersPreserved": True, "unrelatedJarEntriesPreserved": True,
            "migrationChanged": False, "frontendChanged": False, "proxiedControllerVerified": True})
    except BaseException:
        core.activate(before, APP, rollback)
        core.health(APP)
        raise
    print("Order-cost controller fix deployed and verified; frontend and other services preserved.")


base.deploy = deploy
if __name__ == "__main__":
    try:
        {"snapshot": snapshot, "build": build, "verify": verify, "drain-deploy": base.drain_deploy}[sys.argv[2]]()
    except subprocess.CalledProcessError as error:
        (ROOT / "failure.private.log").write_bytes(error.output or b"")
        (ROOT / "failure.private.log").chmod(0o600)
        raise SystemExit("Release failed; private diagnostics retained.")
