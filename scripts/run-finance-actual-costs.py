"""Use short-lived scoped DB access for verified ERP evidence, then verify via a SELECT-only account."""
import hashlib
import io
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import sys
import time
import uuid
import zipfile

os.umask(0o077)
root = Path(sys.argv[1]).resolve()
mode = sys.argv[2]
assert mode in {"import", "verify"}
app, mysql = [json.loads(subprocess.check_output(["docker", "inspect", name]))[0]
              for name in ("ai-commerce-app", "ai-commerce-mysql")]
settings = dict(item.split("=", 1) for item in mysql["Config"]["Env"])
schema = settings["MYSQL_DATABASE"]
assert re.fullmatch(r"[a-zA-Z0-9_]+", schema)
network = next(iter(set(app["NetworkSettings"]["Networks"]) & set(mysql["NetworkSettings"]["Networks"])))
user, password = "fi_actual_" + secrets.token_hex(5), secrets.token_hex(32)
environment = root / (mode + "-private.env")


def sql(statement):
    result = subprocess.run(["docker", "exec", "-i", "ai-commerce-mysql", "sh", "-c",
        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -NB'],
        input=statement.encode(), capture_output=True, timeout=35)
    if result.returncode:
        raise RuntimeError("Scoped actual cost maintenance database command failed")
    return result.stdout.decode().strip()


def invalidate():
    expected = json.loads((root / "expected.json").read_text())
    org = expected["organizationId"]
    raw = bytearray(hashlib.md5(("finance:" + org).encode()).digest())
    raw[6] = (raw[6] & 15) | 48
    raw[8] = (raw[8] & 63) | 128
    identity = str(uuid.UUID(bytes=bytes(raw)))
    key = "heyi:finance:snapshot:v11-cost-display-currency:{" + org + ":" + identity + "}:revision"
    env = dict(item.split("=", 1) for item in app["Config"]["Env"])
    password = next((v for k, v in env.items() if "REDIS" in k and "PASSWORD" in k), "")
    result = subprocess.run(["docker", "exec", "-i", "-e", "REDISCLI_AUTH",
        "ai-commerce-redis", "redis-cli", "--no-auth-warning", "--raw", "INCR", key],
        capture_output=True, check=True, timeout=10, env={**os.environ, "REDISCLI_AUTH": password})
    assert result.stdout.strip().isdigit(), "Cache revision could not be invalidated"


try:
    tables = sql(f"SELECT table_name FROM information_schema.tables WHERE table_schema='{schema}' AND table_name LIKE 'finance\\_%'").splitlines()
    assert tables and all(re.fullmatch(r"finance_[a-z_]+", t) for t in tables)
    grants = "".join(f"GRANT SELECT ON {schema}.{table} TO '{user}'@'%';" for table in tables)
    if mode == "import":
        grants += "".join(f"GRANT INSERT,UPDATE ON {schema}.{table} TO '{user}'@'%';" for table in (
            "finance_actual_order_costs", "finance_return_cost_receipts", "finance_order_cost_jobs", "finance_workspace_audit"))
        # MySQL checks UPDATE privilege for SELECT ... FOR UPDATE; the importer only locks this table.
        grants += f"GRANT UPDATE ON {schema}.finance_connections TO '{user}'@'%';"
    sql(f"CREATE USER '{user}'@'%' IDENTIFIED BY '{password}';" + grants)
    environment.write_text(
        f"BACKFILL_JDBC=jdbc:mysql://ai-commerce-mysql:3306/{schema}?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&connectTimeout=8000&socketTimeout=45000\n"
        f"BACKFILL_USER={user}\nBACKFILL_PASSWORD={password}\n")
    prepared = json.loads((root / "prepared.json").read_text())
    deployed = subprocess.check_output(["docker", "exec", "ai-commerce-app", "cat", "/app/app.jar"])
    assert hashlib.sha256(deployed).hexdigest() == prepared["jarHash"]
    with zipfile.ZipFile(io.BytesIO(deployed)) as archive:
        for name in archive.namelist():
            if name.startswith("BOOT-INF/classes/") and name.endswith(".class"):
                relative = name.removeprefix("BOOT-INF/classes/")
                assert ".." not in Path(relative).parts
                path = root / "production-classes" / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(archive.read(name))
    result = subprocess.run(["docker", "run", "--rm", "--log-driver", "none", "--user", "0:0",
        "--name", "finance-actual-costs-" + mode, "--network", network, "--memory", "768m", "--cpus", "1",
        "--env-file", str(environment), "-v", str(root) + ":/maintenance",
        "-v", "/tmp/finance-order-tags-qa-20261026:/deps:ro", "--entrypoint", "java", app["Config"]["Image"],
        "-Xmx512m", "-cp", "/maintenance/maintenance:/maintenance/production-classes:/deps/lib/*",
        "com.yingdao.aicommerce.finance.FinanceActualCostsMaintenance", "/maintenance", mode], timeout=300)
    if result.returncode:
        raise RuntimeError("Actual cost " + mode + " failed; committed evidence is idempotent and can resume")
    if mode == "import":
        invalidate()
        expected = json.loads((root / "expected.json").read_text())
        org, shop = expected["organizationId"], expected["shopId"]
        for _ in range(60):
            pending = sql(f"SELECT COUNT(*) FROM {schema}.finance_order_cost_jobs WHERE organization_id='{org}' AND shop_id='{shop}'")
            if pending == "0":
                invalidate()
                print("Target shop cost queue drained and cache invalidated.", flush=True)
                break
            print("Remaining cost jobs: " + pending, flush=True)
            time.sleep(3)
        else:
            raise RuntimeError("Cost queue still processing; evidence is saved, verification must wait")
finally:
    environment.unlink(missing_ok=True)
    sql(f"DROP USER IF EXISTS '{user}'@'%';")
    print("Temporary scoped database access removed.", flush=True)
