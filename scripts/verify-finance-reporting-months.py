"""Run the deployed code against production finance tables with a temporary SELECT-only account."""
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import sys
import zipfile

os.umask(0o077)
root = Path(sys.argv[1]).resolve()
app = json.loads(subprocess.check_output(["docker", "inspect", "ai-commerce-app"]))[0]
mysql = json.loads(subprocess.check_output(["docker", "inspect", "ai-commerce-mysql"]))[0]
settings = dict(item.split("=", 1) for item in mysql["Config"]["Env"])
schema = settings["MYSQL_DATABASE"]
assert re.fullmatch(r"[a-zA-Z0-9_]+", schema)
network = next(iter(set(app["NetworkSettings"]["Networks"]) & set(mysql["NetworkSettings"]["Networks"])))
user, password = "fi_month_" + secrets.token_hex(5), secrets.token_hex(32)
environment = root / "verify-private.env"

def sql(statement):
    result = subprocess.run(["docker", "exec", "-i", "ai-commerce-mysql", "sh", "-c",
        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -NB'],
        input=statement.encode(), capture_output=True, timeout=30)
    if result.returncode:
        raise RuntimeError("Read-only validation account setup/cleanup failed")
    return result.stdout.decode()

try:
    tables = sql(f"SELECT table_name FROM information_schema.tables WHERE table_schema='{schema}' AND table_name LIKE 'finance\\_%'").splitlines()
    assert tables and all(re.fullmatch(r"finance_[a-z_]+", t) for t in tables)
    sql(f"CREATE USER '{user}'@'%' IDENTIFIED BY '{password}';"
        + "".join(f"GRANT SELECT ON {schema}.{table} TO '{user}'@'%';" for table in tables))
    environment.write_text(
        f"BACKFILL_JDBC=jdbc:mysql://ai-commerce-mysql:3306/{schema}?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&connectTimeout=8000&socketTimeout=45000\n"
        f"BACKFILL_USER={user}\nBACKFILL_PASSWORD={password}\n")
    environment.chmod(0o600)
    # Use exactly the just-verified deployed JAR, not another local build.
    prepared = json.loads((root / "prepared.json").read_text())
    import hashlib
    deployed = subprocess.check_output(["docker", "exec", "ai-commerce-app", "cat", "/app/app.jar"])
    assert hashlib.sha256(deployed).hexdigest() == prepared["jarHash"]
    import io
    with zipfile.ZipFile(io.BytesIO(deployed)) as archive:
        for name in archive.namelist():
            if name.startswith("BOOT-INF/classes/") and name.endswith(".class"):
                relative = name.removeprefix("BOOT-INF/classes/")
                assert ".." not in Path(relative).parts
                path = root / "production-classes" / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(archive.read(name))
    result = subprocess.run(["docker", "run", "--rm", "--user", "0:0", "--name", "finance-reporting-month-verify",
        "--network", network, "--memory", "768m", "--cpus", "1", "--env-file", str(environment),
        "-v", str(root) + ":/verify", "-v", "/tmp/finance-order-tags-qa-20261026:/deps:ro",
        "--entrypoint", "java", app["Config"]["Image"], "-Xmx512m",
        "-cp", "/verify/maintenance:/verify/production-classes:/deps/lib/*",
        "com.yingdao.aicommerce.finance.FinanceReportingMonthVerify", "/verify"], timeout=150)
    if result.returncode:
        raise RuntimeError("Production reporting-month validation failed")
finally:
    environment.unlink(missing_ok=True)
    sql(f"DROP USER IF EXISTS '{user}'@'%';")
    print("Temporary read-only validation account removed.")
