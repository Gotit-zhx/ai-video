"""Run the checkpointed derivation with a temporary table-scoped account; never log credentials."""
from pathlib import Path
import json
import os
import re
import secrets
import subprocess
import sys
import importlib.util
import time

os.umask(0o077)
root = Path(sys.argv[1]).resolve()
workers = int(sys.argv[2]) if len(sys.argv) > 2 else 2
assert 1 <= workers <= 4, "Backfill workers must be between one and four"
output = root / "backfill"
output.mkdir(exist_ok=True, mode=0o700)
deps = Path("/tmp/finance-order-tags-qa-20261026")
app = json.loads(subprocess.check_output(["docker", "inspect", "ai-commerce-app"]))[0]
mysql = json.loads(subprocess.check_output(["docker", "inspect", "ai-commerce-mysql"]))[0]
settings = dict(item.split("=", 1) for item in mysql["Config"]["Env"])
schema = settings["MYSQL_DATABASE"]
assert re.fullmatch(r"[a-zA-Z0-9_]+", schema)
network = next(iter(set(app["NetworkSettings"]["Networks"]) & set(mysql["NetworkSettings"]["Networks"])))
user, password = "fi_derive_" + secrets.token_hex(5), secrets.token_hex(32)
environment = root / "backfill-private.env"

def sql(statement):
    result = subprocess.run(["docker", "exec", "-i", "ai-commerce-mysql", "sh", "-c",
        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -NB'], input=statement.encode(), capture_output=True, timeout=30)
    if result.returncode:
        raise RuntimeError("Temporary backfill account setup/cleanup failed")

try:
    sql(f"CREATE USER '{user}'@'%' IDENTIFIED BY '{password}';"
        f"GRANT SELECT,UPDATE,INSERT,DELETE ON {schema}.finance_records TO '{user}'@'%';"
        f"GRANT SELECT ON {schema}.finance_connections TO '{user}'@'%';")
    environment.write_text(
        f"BACKFILL_JDBC=jdbc:mysql://ai-commerce-mysql:3306/{schema}?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&connectTimeout=8000&socketTimeout=30000&rewriteBatchedStatements=true\n"
        f"BACKFILL_USER={user}\nBACKFILL_PASSWORD={password}\nBACKFILL_WORKERS={workers}\n")
    environment.chmod(0o600)
    command = ["docker", "run", "--rm", "--user", "0:0", "--name", "finance-settlement-derivation",
        "--network", network, "--memory", "768m", "--cpus", "1.5", "--env-file", str(environment),
        "-v", str(root / "classes") + ":/classes:ro", "-v", str(root / "maintenance") + ":/maintenance:ro",
        "-v", str(deps) + ":/deps:ro", "-v", str(output) + ":/out", "--entrypoint", "java", app["Config"]["Image"],
        "-Xmx512m", "-cp", "/maintenance:/classes:/deps/runtime-classes:/deps/lib/*",
        "com.yingdao.aicommerce.finance.FinanceSettlementBackfill", "/out"]
    spec = importlib.util.spec_from_file_location("settlement_progress", root / "settlement-progress.py")
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    progress = module.SettlementProgress(root, app)
    checkpoints = [json.loads(p.read_text()) for p in output.glob("*.json") if p.stem.count("-") == 4]
    (root / "backfill-run.json").write_text(json.dumps({
        "workers": workers, "batchSize": 20, "startedAt": time.time(),
        "recordsBefore": sum(p.get("records", 0) for p in checkpoints),
        "billsBefore": sum(p.get("bills", 0) for p in checkpoints)}))
    process = subprocess.Popen(command)
    try:
        while process.poll() is None:
            try:
                progress.publish("RUNNING")
            except Exception:
                print("Progress heartbeat temporarily unavailable; derivation continues.", flush=True)
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                pass
        progress.publish("COMPLETE" if process.returncode == 0 else "FAILED")
    except BaseException:
        subprocess.run(["docker", "stop", "--time", "20", "finance-settlement-derivation"], capture_output=True)
        process.wait(timeout=30)
        progress.publish("FAILED")
        raise
    raise SystemExit(process.returncode)
finally:
    environment.unlink(missing_ok=True)
    sql(f"DROP USER IF EXISTS '{user}'@'%';")
    print("Backfill account and temporary credentials removed.")
