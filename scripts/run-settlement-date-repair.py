"""Run the date-only repair with limited SQL rights, checkpoints, progress and cleanup."""
from pathlib import Path
import hashlib
import importlib.util
import json
import os
import re
import secrets
import signal
import subprocess
import sys
import time

os.umask(0o077)
root = Path(sys.argv[1]).resolve()
priority = sys.argv[2] if len(sys.argv) > 2 else ""
assert not priority or re.fullmatch(r"[a-f0-9-]{36}", priority)
metadata = root / "statement-completion-metadata.json"
digest = hashlib.sha256(metadata.read_bytes()).hexdigest()
stamp = root / "repair-input.sha256"
if stamp.exists():
    assert stamp.read_text() == digest, "Do not change the verified input during a resumable repair"
else:
    assert time.time() - metadata.stat().st_mtime < 3600, "Refresh the official statement metadata first"
    stamp.write_text(digest)
shops = {row["shop_id"] for row in json.loads(metadata.read_text())}
app = json.loads(subprocess.check_output(["docker", "inspect", "ai-commerce-app"]))[0]
mysql = json.loads(subprocess.check_output(["docker", "inspect", "ai-commerce-mysql"]))[0]
settings = dict(item.split("=", 1) for item in mysql["Config"]["Env"])
schema = settings["MYSQL_DATABASE"]
assert re.fullmatch(r"[a-zA-Z0-9_]+", schema)
network = next(iter(set(app["NetworkSettings"]["Networks"]) & set(mysql["NetworkSettings"]["Networks"])))
user, password = "fi_dates_" + secrets.token_hex(5), secrets.token_hex(32)
environment = root / "date-repair-private.env"
process = None

def sql(statement):
    result = subprocess.run(["docker", "exec", "-i", "ai-commerce-mysql", "sh", "-c",
        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -NB'],
        input=statement.encode(), capture_output=True, timeout=30)
    if result.returncode:
        raise RuntimeError("Date repair account setup/cleanup failed")

def interrupted(signum, frame):
    raise KeyboardInterrupt("Date repair interrupted; checkpoints retained")

signal.signal(signal.SIGTERM, interrupted)
signal.signal(signal.SIGHUP, interrupted)
spec = importlib.util.spec_from_file_location("date_progress", root / "settlement-progress.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
progress = module.SettlementProgress(root, app)
progress.shops = [shop for shop in progress.shops if shop["shopId"] in shops]
try:
    sql(f"CREATE USER '{user}'@'%' IDENTIFIED BY '{password}';"
        f"GRANT SELECT,UPDATE ON {schema}.finance_records TO '{user}'@'%';"
        f"GRANT SELECT ON {schema}.finance_connections TO '{user}'@'%';")
    environment.write_text(
        f"BACKFILL_JDBC=jdbc:mysql://ai-commerce-mysql:3306/{schema}?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&connectTimeout=8000&socketTimeout=30000&rewriteBatchedStatements=true\n"
        f"BACKFILL_USER={user}\nBACKFILL_PASSWORD={password}\nDATE_REPAIR_PRIORITY_SHOP={priority}\n")
    environment.chmod(0o600)
    command = ["docker", "run", "--rm", "--user", "0:0", "--name", "finance-settlement-date-repair",
        "--network", network, "--memory", "512m", "--cpus", "1", "--env-file", str(environment),
        "-v", str(root) + ":/repair", "-v", "/tmp/finance-order-tags-qa-20261026:/deps:ro",
        "--entrypoint", "java", app["Config"]["Image"], "-Xmx320m",
        "-cp", "/repair/maintenance:/repair/classes:/deps/runtime-classes:/deps/lib/*",
        "com.yingdao.aicommerce.finance.FinanceSettlementDateRepair", "/repair"]
    process = subprocess.Popen(command)
    while process.poll() is None:
        progress.publish("RUNNING")
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            pass
    if process.returncode:
        raise RuntimeError("Date repair stopped; review the failed batch before resuming")
    subprocess.run([sys.executable, str(root / "check-settlement-net-ledger.py"), str(root), "after"], check=True, timeout=60)
    time.sleep(6)
    progress.invalidate_snapshots()
    progress.publish("COMPLETE")
except BaseException:
    if process is not None and process.poll() is None:
        subprocess.run(["docker", "stop", "--timeout", "20", "finance-settlement-date-repair"], capture_output=True, timeout=30)
        process.wait(timeout=30)
    progress.publish("FAILED")
    raise
finally:
    environment.unlink(missing_ok=True)
    sql(f"DROP USER IF EXISTS '{user}'@'%';")
    print("Temporary date-repair account and credentials removed.")
