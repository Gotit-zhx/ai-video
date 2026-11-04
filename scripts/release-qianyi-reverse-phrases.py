"""Deploy explicit returned-to-sender wording and rescan affected receipts."""
import importlib.util
import io
import json
import sys
import zipfile

spec = importlib.util.spec_from_file_location("latest",
    "/opt/heyi/releases/qianyi-return-total-20261104/release-qianyi-return-total.py")
latest = importlib.util.module_from_spec(spec)
spec.loader.exec_module(latest)
core, storage = latest.core, latest.storage
ROOT, APP, FRONT = latest.ROOT, latest.APP, latest.FRONT
IMAGE = "ai-commerce-platform:qianyi-reverse-phrases-20261104"
ENTRY = "BOOT-INF/classes/com/yingdao/aicommerce/finance/FinanceQianyiReceipt.class"
sql = latest.naming.previous.month.priority.sql


def build():
    assert core.read_json("qa-passed.json")["unitTests"] == 12
    app = core.inspect(APP)
    assert app["Config"]["Image"] == "ai-commerce-platform:qianyi-return-total-20261104"
    original = core.run("docker", "exec", APP, "cat", "/app/app.jar")
    replacement = (ROOT / "classes" / ENTRY.removeprefix("BOOT-INF/classes/")).read_bytes()
    expected = core.read_json("baseline-expected.json")
    folder = ROOT / "app-image"
    folder.mkdir()
    with zipfile.ZipFile(io.BytesIO(original)) as old, zipfile.ZipFile(folder / "app.jar", "w") as new:
        assert core.sha(old.read(ENTRY)) == expected["classHash"]
        new.comment = old.comment
        for item in old.infolist():
            new.writestr(item, replacement if item.filename == ENTRY else old.read(item.filename))
    with zipfile.ZipFile(io.BytesIO(original)) as old, zipfile.ZipFile(folder / "app.jar") as new:
        assert old.namelist() == new.namelist()
        assert all(old.read(n) == new.read(n) for n in old.namelist() if n != ENTRY)
    (folder / "app.jar").chmod(0o644)
    guard = json.loads(core.run("python3", "/usr/local/lib/heyi/compact-release-image.py",
        "--prepare", app["Image"], IMAGE + "-base").decode())
    core.write_json(ROOT / "image-guard.json", guard)
    (folder / "Dockerfile").write_text("FROM " + guard["baseImage"] + "\nCOPY app.jar /app/app.jar\n")
    core.run("docker", "build", "--pull=false", "-t", IMAGE, str(folder))
    core.write_json(ROOT / "prepared.json", {
        "sourceImageId": app["Image"], "sourceJarHash": core.sha(original),
        "jarHash": core.sha((folder / "app.jar").read_bytes()),
        "classHash": core.sha(replacement), "unrelatedEntriesPreserved": True})
    print("One verified receipt classifier prepared.", flush=True)


def verify():
    core.health(APP)
    app = core.inspect(APP)
    assert app["Config"]["Image"] == IMAGE
    assert app["HostConfig"]["LogConfig"]["Type"] == "none"
    assert core.sha(core.run("docker", "exec", APP, "cat", "/app/app.jar")) == core.read_json("prepared.json")["jarHash"]
    for path, expected in (("/", "200"), ("/api/v1/finance/qianyi/returns", "401")):
        assert core.run("curl", "--noproxy", "*", "-sS", "--max-time", "10",
            "--resolve", "hoyee.org.cn:443:127.0.0.1", "-o", "/dev/null", "-w", "%{http_code}",
            "https://hoyee.org.cn" + path).decode() == expected
    print("Live classifier, health and access protection verified.", flush=True)


def rescan():
    # Called while the established release helper holds all idle Qianyi leases.
    # Nest the exact prior cursor instead of replacing or resetting history.
    assert not (ROOT / "receipt-rescan.json").exists()
    result = sql("""
        SET @repair_start=DATE_FORMAT(DATE_SUB(CONVERT_TZ(UTC_TIMESTAMP(),'+00:00','+08:00'),INTERVAL 1 MONTH),'%Y-%m-01');
        SET @repair_until=DATE(CONVERT_TZ(UTC_TIMESTAMP(),'+00:00','+08:00'));
        UPDATE finance_qianyi_connections q JOIN finance_connections c
          ON c.organization_id=q.organization_id AND c.id=q.shop_id
        SET q.checkpoint_json=JSON_OBJECT(
              'from',@repair_start,'until',CAST(@repair_until AS CHAR),'page',1,'sourceVersion',3,
              'receiptScan',JSON_EXTRACT('true','$'),'phraseRepair','20261104',
              'backfillResume',COALESCE(q.checkpoint_json,JSON_OBJECT())),
            q.next_at=IF(q.shop_id='7fcf44fd-e982-4969-a1c4-30743f1ae32a',
              DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 5 MINUTE),UTC_TIMESTAMP(6))
        WHERE q.enabled=TRUE AND c.status<>'REMOVED'
          AND q.lease_token='__LEASE__' AND q.lease_until>UTC_TIMESTAMP(6)
          AND EXISTS (SELECT 1 FROM finance_qianyi_returns r
            WHERE r.organization_id=q.organization_id AND r.shop_id=q.shop_id
              AND r.return_receipt_state='UNKNOWN' AND r.return_state='FINISH'
              AND r.aftersale_type='SALES_RETURN'
              AND r.source_received_at>=UNIX_TIMESTAMP(CONVERT_TZ(@repair_start,'+08:00','+00:00'))*1000
              AND JSON_UNQUOTE(JSON_EXTRACT(r.data_json,'$._returnReceipt.event')) IN
                ('Parcel has been returned to sender','Parcel has been returned to sender.',
                 'Package has been returned to the seller','Package has been returned to the seller.'));
        SELECT ROW_COUNT();
        """.replace("__LEASE__", storage.queue.QIANYI_LEASE))
    count = int(result.strip())
    core.write_json(ROOT / "receipt-rescan.json", {
        "shops": count, "historicalCursorsNested": True, "sharedLimiterPreserved": True,
        "sourceRecordsDirectlyEdited": False})
    print("Affected shops queued for fresh source and full-track verification: " + str(count), flush=True)


def deploy():
    assert not (ROOT / "activation.json").exists()
    before = {n: core.inspect(n) for n in (APP, FRONT)}
    prepared = core.read_json("prepared.json")
    assert before[APP]["Image"] == prepared["sourceImageId"]
    assert core.sha(core.run("docker", "exec", APP, "cat", "/app/app.jar")) == prepared["sourceJarHash"]
    for table, condition in (
        ("product_publish_workflows", "status='RUNNING' AND locked_until>UTC_TIMESTAMP(6)"),
        ("finance_authorization_sessions", "status='PROCESSING' AND expires_at>UTC_TIMESTAMP(6)"),
    ):
        assert sql("SELECT COUNT(*) FROM " + table + " WHERE " + condition).strip() == "0"
    rollback = before[APP]["Config"]["Image"]
    try:
        core.run("docker", "stop", "--timeout", "120", APP)
        storage.activate(before, APP, IMAGE)
        verify()
        assert core.inspect(FRONT)["Id"] == before[FRONT]["Id"]
        assert all(core.configuration(core.inspect(n)) == core.configuration(before[n]) for n in before)
    except BaseException:
        storage.activate(before, APP, rollback)
        core.health(APP)
        raise
    core.write_json(ROOT / "activation.json", {
        "image": IMAGE, "rollbackImage": rollback, "configurationPreserved": True,
        "frontendUnchanged": True, "unrelatedEntriesPreserved": True})
    rescan()


def drain_deploy():
    storage.queue.deploy = deploy
    storage.queue.drain_deploy()


if __name__ == "__main__":
    {"build": build, "drain-deploy": drain_deploy, "verify": verify}[sys.argv[2]]()
