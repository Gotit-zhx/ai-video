"""Company financial groups, permissions, settlement summaries and refund recovery; exact-image guarded overlay."""
import importlib.util
import io
import gzip
import json
from pathlib import Path
import shutil
import subprocess
import sys
import zipfile
import datetime
import time

ROOT = Path(sys.argv[1]).resolve()
MODE = sys.argv[2]
spec = importlib.util.spec_from_file_location(
    "finance_release", "/opt/heyi/releases/finance-bi-20261020/release-finance-bi.py")
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)
core = release.core
APP, FRONT = core.APP, core.FRONT
TAG = "finance-groups-20261022"
FINANCE = "BOOT-INF/classes/com/yingdao/aicommerce/finance/"
NAMES = {"FinanceData","FinanceRepository","FinanceService","FinanceController","FinanceGroups",
         "FinanceShopAuthorization","FinanceAds","FinanceAdAuthorization","FinanceImport","FinanceFields",
         "FinanceMarketplace","FinanceReadCache","FinanceSnapshotCache","FinanceCacheWarmer"}
MIGRATIONS = {"BOOT-INF/classes/db/migration/" + n for n in
              ("V125__finance_company_groups.sql","V126__finance_shopee_refund_status.sql")}
FILES = {"index.html", "app.js", "finance-workspace.css", "finance-workspace.js"}


def connector(name):
    return name in MIGRATIONS or any(name == FINANCE + base + ".class" or name.startswith(FINANCE + base + "$") for base in NAMES)


def sql(text):
    return core.run("docker", "exec", "-i", "ai-commerce-mysql", "sh", "-c",
                    'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --default-character-set=utf8mb4 -uroot -NB "$MYSQL_DATABASE"', data=text.encode()).decode().strip()


def dump(tables):
    assert all(t.replace("_", "").isalnum() for t in tables)
    return core.run("docker", "exec", "ai-commerce-mysql", "sh", "-c",
                    'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot --single-transaction --skip-lock-tables --no-tablespaces --skip-comments --compact --order-by-primary "$MYSQL_DATABASE" ' + " ".join(tables))


def snapshot():
    ROOT.mkdir(parents=True, exist_ok=True, mode=0o700)
    assert not (ROOT / "snapshot.json").exists()
    before = {name: core.inspect(name) for name in (APP, FRONT)}
    core.write_json(ROOT / "before-private.json", before)
    previous = core.run("docker", "exec", APP, "cat", "/app/app.jar")
    (ROOT / "previous.jar").write_bytes(previous)
    (ROOT / "previous.jar").chmod(0o600)
    with zipfile.ZipFile(io.BytesIO(previous)) as jar:
        assert not MIGRATIONS.intersection(jar.namelist()), "Finance migration numbering changed; review before deployment"
        expected = {"jar": core.sha(previous), "expectedClasses": {n: core.sha(jar.read(n)) for n in jar.namelist() if connector(n)}, "expectedFiles": {}}
    for file in FILES:
        value = core.run("docker", "exec", FRONT, "cat", "/usr/share/nginx/html/" + file)
        expected["expectedFiles"][file] = core.sha(value)
        target = ROOT / "baseline-frontend" / file; target.parent.mkdir(exist_ok=True); target.write_bytes(value)
    assert sql("SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1") == "124"
    core.write_json(ROOT / "snapshot.json", expected)
    assert sql("SELECT COUNT(*) FROM (SELECT organization_id,platform,external_shop_id FROM finance_connections GROUP BY organization_id,platform,external_shop_id HAVING COUNT(*)>1) d") == "0", "Existing duplicate shops require reconciliation"
    assert sql("SELECT COUNT(*) FROM (SELECT organization_id,advertiser_id FROM finance_ad_accounts GROUP BY organization_id,advertiser_id HAVING COUNT(*)>1) d") == "0"
    assert sql("SELECT COUNT(*) FROM (SELECT organization_id FROM finance_ad_applications GROUP BY organization_id HAVING COUNT(*)>1) d") == "0"
    print("Captured exact production class/file hashes; company uniqueness preflight passed.")


def build():
    assert not (ROOT / "prepared.json").exists()
    manifest = core.read_json("manifest.json")
    before = core.read_json("before-private.json")
    for name, info in before.items():
        assert core.inspect(name)["Id"] == info["Id"], "Container changed during preparation"
    previous = core.run("docker", "exec", APP, "cat", "/app/app.jar")
    assert core.sha(previous) == core.read_json("snapshot.json")["jar"]
    appdir = ROOT / "app-image"
    appdir.mkdir(exist_ok=False)
    with zipfile.ZipFile(io.BytesIO(previous)) as old, zipfile.ZipFile(appdir / "app.jar", "w") as new:
        assert {n: core.sha(old.read(n)) for n in old.namelist() if connector(n)} == manifest["expectedClasses"]
        new.comment = old.comment
        for entry in old.infolist():
            if not connector(entry.filename):
                new.writestr(entry, old.read(entry.filename))
        for name, digest in manifest["classes"].items():
            assert connector(name)
            data = (ROOT / "classes" / name.removeprefix("BOOT-INF/classes/")).read_bytes()
            assert core.sha(data) == digest
            new.writestr(name, data, compress_type=zipfile.ZIP_DEFLATED)
    with zipfile.ZipFile(io.BytesIO(previous)) as old, zipfile.ZipFile(appdir / "app.jar") as new:
        assert set(new.namelist()) == {n for n in old.namelist() if not connector(n)} | set(manifest["classes"])
        assert all(old.read(n) == new.read(n) for n in old.namelist() if not connector(n))
    assert set(manifest["files"]) == FILES
    images = {}
    for name in (APP, FRONT):
        folder = appdir if name == APP else ROOT / "frontend-image"
        folder.mkdir(exist_ok=True)
        lines = ["FROM " + before[name]["Image"]]
        if name == APP:
            lines.append("COPY app.jar /app/app.jar")
        else:
            for file, digest in manifest["files"].items():
                live = core.run("docker", "exec", FRONT, "cat", "/usr/share/nginx/html/" + file)
                assert core.sha(live) == manifest["expectedFiles"][file], "Changed live file: " + file
                baseline = ROOT / "baseline-frontend" / file
                baseline.parent.mkdir(exist_ok=True)
                baseline.write_bytes(live)
                assert core.sha((ROOT / "frontend" / file).read_bytes()) == digest
                shutil.copy2(ROOT / "frontend" / file, folder / file)
                lines.append("COPY " + file + " /usr/share/nginx/html/" + file)
        (folder / "Dockerfile").write_text("\n".join(lines) + "\n")
        images[name] = ("ai-commerce-platform" if name == APP else name) + ":" + TAG
        core.run("docker", "build", "-t", images[name], str(folder))
    core.write_json(ROOT / "prepared.json", {
        "images": images, "jarHash": core.sha((appdir / "app.jar").read_bytes()),
        "unrelatedJarEntriesPreserved": True})
    print("Reviewed finance overlays built; production unchanged.")


def verify():
    prepared, manifest = core.read_json("prepared.json"), core.read_json("manifest.json")
    core.health(APP)
    for name, image in prepared["images"].items():
        assert core.inspect(name)["Config"]["Image"] == image
    core.run("docker", "exec", FRONT, "nginx", "-t")
    assert sql("SELECT COUNT(*) FROM flyway_schema_history WHERE version IN ('125','126') AND success=1") == "2"
    assert sql("SELECT COUNT(*) FROM permissions WHERE code IN ('finance:view','finance:manage')") == "2"
    assert sql("SELECT COUNT(*) FROM finance_records WHERE kind='REFUND' AND platform='SHOPEE' AND source='API' AND status='CLOSED' AND amount IS NOT NULL") == "0"
    assert sql("SELECT COUNT(*) FROM finance_records WHERE kind='REFUND' AND platform='SHOPEE' AND source='API' AND status='ACCEPTED' AND JSON_UNQUOTE(JSON_EXTRACT(raw_json,'$.refund_amount')) REGEXP '^-?[0-9]{1,18}([.][0-9]{1,6})?$' AND (amount IS NULL OR amount<>CAST(JSON_UNQUOTE(JSON_EXTRACT(raw_json,'$.refund_amount')) AS DECIMAL(24,6)))") == "0"
    assert core.sha(core.run("docker", "exec", APP, "cat", "/app/app.jar")) == prepared["jarHash"]
    for file, digest in manifest["files"].items():
        live = core.run("curl", "--noproxy", "*", "-fsS", "--max-time", "10",
                        "--resolve", "hoyee.org.cn:443:127.0.0.1", "https://hoyee.org.cn/" + file)
        assert core.sha(live) == digest, file
        compressed = core.run("curl", "--noproxy", "*", "-fsS", "--max-time", "10", "--resolve",
                              "hoyee.org.cn:443:127.0.0.1", "-H", "Accept-Encoding: gzip", "https://hoyee.org.cn/" + file)
        assert (gzip.decompress(compressed) if compressed.startswith(b"\x1f\x8b") else compressed) == live
    for endpoint in ("overview","shops","groups","records","reconciliation","ads","export"):
        code=core.run("curl","--noproxy","*","-sS","-o","/dev/null","-w","%{http_code}","--max-time","10",
                      "--resolve","hoyee.org.cn:443:127.0.0.1","https://hoyee.org.cn/api/v1/finance/"+endpoint).decode()
        assert code=="401", "Anonymous finance access must remain blocked: "+endpoint


def deploy():
    assert not (ROOT / "activation.json").exists()
    before, prepared = core.read_json("before-private.json"), core.read_json("prepared.json")
    for name, info in before.items():
        assert core.inspect(name)["Id"] == info["Id"], "Live container changed during build"
    release.idle()
    count = core.run("docker", "exec", "-i", "ai-commerce-mysql", "sh", "-c",
                     'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --default-character-set=utf8mb4 -uroot -NB "$MYSQL_DATABASE"',
                     data=b"SELECT COUNT(*) FROM finance_sync_states WHERE status='RUNNING' AND lease_until>UTC_TIMESTAMP(6);")
    assert count.decode().strip() == "0", "Finance request in progress; wait for its checkpoint before restart"
    assert sql("SELECT COUNT(*) FROM finance_ad_accounts WHERE status='RUNNING' AND lease_until>UTC_TIMESTAMP(6)") == "0", "Advertising request in progress"
    assert sql("SELECT COUNT(*) FROM product_publish_workflows WHERE status='RUNNING' AND locked_until>UTC_TIMESTAMP(6)") == "0", "Product workflow in progress"
    assert sql("SELECT COUNT(*) FROM finance_authorization_sessions WHERE status='PROCESSING' AND expires_at>UTC_TIMESTAMP(6)") == "0", "Authorization callback in progress"
    finance_backup = dump(["finance_connections", "finance_sync_states", "finance_ad_accounts", "finance_ad_applications", "finance_imports"])
    refund_before = ROOT / "refund-amounts-before.private.tsv"
    refund_before.write_text(sql("SELECT id,status,amount FROM finance_records WHERE platform='SHOPEE' AND kind='REFUND' AND source='API'")); refund_before.chmod(0o600)
    backup = ROOT / "finance-before.sql.gz"; backup.write_bytes(gzip.compress(finance_backup)); backup.chmod(0o600)
    commerce_before = core.sha(dump(["shops", "shop_credentials"]))
    core.write_json(ROOT / "data-before.json", {"commerceHash": commerce_before, "financeBackupBytes": backup.stat().st_size})
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
        assert core.sha(dump(["shops", "shop_credentials"])) == commerce_before, "Commerce records changed during activation; inspect before proceeding"
        for name in before:
            assert core.configuration(core.inspect(name)) == core.configuration(before[name])
        assert all(core.inspect(name)["Id"] == identifier for name, identifier in preserved.items())
        core.write_json(ROOT / "activation.json", {
            "images": prepared["images"], "rollbackImages": rollback,
            "configurationPreserved": True, "preservedContainers": preserved,
            "databaseMigrationsAdded": ["V125","V126"], "commerceCredentialsPreserved": True, "unrelatedJarEntriesPreserved": True})
    except BaseException:
        for name in (APP, FRONT):
            core.activate(before, name, rollback[name])
        core.health(APP)
        raise
    print("Company financial groups and refund recovery active; commerce credentials and other services preserved.")


def drain_deploy():
    """Defer only unclaimed pages; active requests finish and keep their checkpoints."""
    release.idle()
    marker = (datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(minutes=5)).strftime("%Y-%m-%d %H:%M:%S.%f")
    original = {}
    try:
        for _ in range(50):
            # The comparison against the selected next_at avoids overwriting a
            # concurrent claim/completion. A five-minute expiry also makes the
            # pause self-recovering if this release process is interrupted.
            found = sql("""
                CREATE TEMPORARY TABLE fi_release_pending AS
                SELECT organization_id,user_id,shop_id,channel,next_at FROM finance_sync_states
                WHERE status<>'RUNNING' AND (lease_until IS NULL OR lease_until<UTC_TIMESTAMP(6))
                  AND next_at<'%s';
                SELECT organization_id,user_id,shop_id,channel,DATE_FORMAT(next_at,'%%Y-%%m-%%d %%H:%%i:%%s.%%f')
                FROM fi_release_pending;
                UPDATE finance_sync_states st JOIN fi_release_pending p
                ON st.organization_id=p.organization_id AND st.user_id=p.user_id
                  AND st.shop_id=p.shop_id AND st.channel=p.channel
                SET st.next_at='%s'
                WHERE st.next_at=p.next_at AND st.status<>'RUNNING'
                  AND (st.lease_until IS NULL OR st.lease_until<UTC_TIMESTAMP(6));
                DROP TEMPORARY TABLE fi_release_pending;
                """ % (marker, marker))
            for line in found.splitlines():
                values = line.split("\t")
                assert len(values) == 5
                original[tuple(values[:4])] = values[4]
            core.write_json(ROOT / "drain-private.json",
                            {"marker": marker, "original": [list(key) + [value] for key, value in original.items()]})
            if sql("SELECT COUNT(*) FROM finance_sync_states WHERE status='RUNNING' AND lease_until>UTC_TIMESTAMP(6)") == "0":
                try:
                    deploy()
                    return
                except AssertionError as error:
                    if "Finance request in progress" not in str(error):
                        raise
            time.sleep(.5)
        raise RuntimeError("Finance requests have not drained; original schedules will be restored.")
    finally:
        def literal(value):
            return "CONVERT(0x" + value.encode().hex() + " USING utf8mb4)"
        for key, previous in original.items():
            sql("UPDATE finance_sync_states SET next_at=" + literal(previous)
                + " WHERE " + " AND ".join(column + "=" + literal(value)
                  for column, value in zip(("organization_id", "user_id", "shop_id", "channel"), key))
                + " AND next_at=" + literal(marker)
                + " AND status<>'RUNNING' AND (lease_until IS NULL OR lease_until<UTC_TIMESTAMP(6))")
        core.write_json(ROOT / "drain-restored.json", {"restoredSchedules": len(original), "checkpointChanges": 0})
        print("Original financial schedules restored; no checkpoint or lease was cleared.")


if __name__ == "__main__":
    try:
        {"snapshot": snapshot, "build": build, "deploy": deploy, "drain-deploy": drain_deploy, "verify": verify}[MODE]()
    except subprocess.CalledProcessError as error:
        (ROOT / "failure.private.log").write_bytes(error.output or b"")
        (ROOT / "failure.private.log").chmod(0o600)
        raise SystemExit("Release command failed; private diagnostics retained.")
