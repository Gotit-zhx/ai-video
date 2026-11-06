"""Guarded overlay for server-managed advertising applications and copied OAuth links."""
import gzip
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import sys
import zipfile

ROOT = Path(sys.argv[1]).resolve()
MODE = sys.argv[2]
spec = importlib.util.spec_from_file_location(
    "finance_groups_release", "/opt/heyi/releases/finance-groups-20261022/release-finance-company-groups.py")
shared = importlib.util.module_from_spec(spec)
spec.loader.exec_module(shared)
core, sql = shared.core, shared.sql
APP, FRONT = shared.APP, shared.FRONT
shared.TAG = "finance-ad-links-20261022"
shared.NAMES = {"FinanceAdApplicationSettings", "FinanceAdAuthorization", "FinanceController"}
shared.MIGRATIONS = set()
shared.FILES = {"index.html", "finance-workspace.js", "finance-workspace.css"}


def snapshot():
    ROOT.mkdir(parents=True, exist_ok=True, mode=0o700)
    assert not (ROOT / "snapshot.json").exists()
    before = {name: core.inspect(name) for name in (APP, FRONT)}
    core.write_json(ROOT / "before-private.json", before)
    previous = core.run("docker", "exec", APP, "cat", "/app/app.jar")
    (ROOT / "previous.jar").write_bytes(previous)
    (ROOT / "previous.jar").chmod(0o600)
    with zipfile.ZipFile(io.BytesIO(previous)) as jar:
        expected = {"jar": core.sha(previous),
                    "expectedClasses": {n: core.sha(jar.read(n)) for n in jar.namelist() if shared.connector(n)},
                    "expectedFiles": {}}
    for file in shared.FILES:
        value = core.run("docker", "exec", FRONT, "cat", "/usr/share/nginx/html/" + file)
        expected["expectedFiles"][file] = core.sha(value)
        target = ROOT / "baseline-frontend" / file
        target.parent.mkdir(exist_ok=True)
        target.write_bytes(value)
    assert sql("SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1") == "126"
    core.write_json(ROOT / "snapshot.json", expected)
    print("Captured current production baseline; no data or runtime configuration changed.")


def verify():
    prepared, manifest = core.read_json("prepared.json"), core.read_json("manifest.json")
    core.health(APP)
    for name, image in prepared["images"].items():
        assert core.inspect(name)["Config"]["Image"] == image
    core.run("docker", "exec", FRONT, "nginx", "-t")
    assert core.sha(core.run("docker", "exec", APP, "cat", "/app/app.jar")) == prepared["jarHash"]
    assert sql("SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1") == "126"
    for file, digest in manifest["files"].items():
        args = ["curl", "--noproxy", "*", "-fsS", "--max-time", "15", "--resolve",
                "hoyee.org.cn:443:127.0.0.1"]
        live = core.run(*args, "https://hoyee.org.cn/" + file)
        assert core.sha(live) == digest, file
        compressed = core.run(*args, "-H", "Accept-Encoding: gzip", "https://hoyee.org.cn/" + file)
        assert (gzip.decompress(compressed) if compressed.startswith(b"\x1f\x8b") else compressed) == live
    for method, endpoint in (("GET", "ads/application"), ("POST", "ads/authorize"), ("PUT", "ads/application")):
        code = core.run("curl", "--noproxy", "*", "-sS", "-o", "/dev/null", "-w", "%{http_code}",
                        "--max-time", "10", "--resolve", "hoyee.org.cn:443:127.0.0.1", "-X", method,
                        "-H", "Content-Type: application/json", "--data", "{}",
                        "https://hoyee.org.cn/api/v1/finance/" + endpoint).decode()
        assert code == "401", "Anonymous advertising access must remain blocked"


def deploy():
    assert not (ROOT / "activation.json").exists()
    before, prepared = core.read_json("before-private.json"), core.read_json("prepared.json")
    for name, info in before.items():
        assert core.inspect(name)["Id"] == info["Id"], "Live container changed during preparation"
    shared.release.idle()
    for query, message in (
        ("SELECT COUNT(*) FROM finance_sync_states WHERE status='RUNNING' AND lease_until>UTC_TIMESTAMP(6)", "Finance request in progress"),
        ("SELECT COUNT(*) FROM finance_ad_accounts WHERE status='RUNNING' AND lease_until>UTC_TIMESTAMP(6)", "Advertising request in progress"),
        ("SELECT COUNT(*) FROM product_publish_workflows WHERE status='RUNNING' AND locked_until>UTC_TIMESTAMP(6)", "Product workflow in progress"),
        ("SELECT COUNT(*) FROM finance_authorization_sessions WHERE status='PROCESSING' AND expires_at>UTC_TIMESTAMP(6)", "Authorization callback in progress"),
    ):
        assert sql(query) == "0", message
    tables = ["shops", "shop_credentials", "finance_connections", "finance_ad_applications", "finance_ad_accounts"]
    original = core.sha(shared.dump(tables))
    preserved = {item["Name"]: item["Id"] for item in json.loads(core.run(
        "docker", "inspect", *core.run("docker", "ps", "-q").decode().split()))
        if item["Name"].lstrip("/") not in before}
    rollback = {}
    for name, info in before.items():
        rollback[name] = prepared["images"][name] + "-before"
        core.run("docker", "tag", info["Image"], rollback[name])
    try:
        core.activate(before, APP, prepared["images"][APP])
        core.health(APP)
        core.activate(before, FRONT, prepared["images"][FRONT])
        verify()
        assert core.sha(shared.dump(tables)) == original, "Credential or account records changed during activation"
        assert all(core.configuration(core.inspect(name)) == core.configuration(info) for name, info in before.items())
        assert all(core.inspect(name)["Id"] == identifier for name, identifier in preserved.items())
        core.write_json(ROOT / "activation.json", {
            "images": prepared["images"], "rollbackImages": rollback, "configurationPreserved": True,
            "preservedContainers": preserved, "databaseMigrationsAdded": [], "credentialsAndAccountsPreserved": True,
            "unrelatedJarEntriesPreserved": True})
    except BaseException:
        for name in (APP, FRONT):
            core.activate(before, name, rollback[name])
        core.health(APP)
        raise
    print("Copied advertising authorization links deployed; credentials and other services preserved.")


shared.deploy = deploy
if __name__ == "__main__":
    try:
        {"snapshot": snapshot, "build": shared.build, "deploy": deploy,
         "drain-deploy": shared.drain_deploy, "verify": verify}[MODE]()
    except subprocess.CalledProcessError as error:
        (ROOT / "failure.private.log").write_bytes(error.output or b"")
        (ROOT / "failure.private.log").chmod(0o600)
        raise SystemExit("Release command failed; private diagnostics retained.")
