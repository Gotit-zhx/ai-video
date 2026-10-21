"""Resize the production MySQL read cache online, preserving the rollback setting.

Run on the server: python3 tune-finance-mysql-cache.py STATE_DIRECTORY apply|verify|rollback
Only innodb_buffer_pool_size is changed; credentials stay inside the MySQL container.
"""
import json
from pathlib import Path
import subprocess
import sys
import time

TARGET = 2 * 1024 ** 3
STATE = Path(sys.argv[1]).resolve() / "mysql-cache-before.json"


def sql(query):
    return subprocess.check_output([
        "docker", "exec", "-i", "ai-commerce-mysql", "sh", "-c",
        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -NB "$MYSQL_DATABASE"',
    ], input=query.encode()).decode().strip()


def resize_status():
    return dict(line.split("\t", 1) for line in sql(
        "SHOW GLOBAL STATUS WHERE Variable_name IN "
        "('Innodb_buffer_pool_resize_status','Innodb_buffer_pool_resize_status_code')"
    ).splitlines())


def wait_for_resize(size):
    for _ in range(45):
        status = resize_status()
        code = status.get("Innodb_buffer_pool_resize_status_code", "")
        if code == "7":
            raise RuntimeError("MySQL buffer pool resize failed: " + str(status))
        if code == "0" and int(sql("SELECT @@GLOBAL.innodb_buffer_pool_size")) == size:
            return status
        time.sleep(1)
    raise RuntimeError("Resize not confirmed; inspect MySQL resize status before proceeding")


def verify():
    status = wait_for_resize(TARGET)
    assert sql("SELECT VARIABLE_VALUE FROM performance_schema.persisted_variables "
               "WHERE VARIABLE_NAME='innodb_buffer_pool_size'") == str(TARGET)
    print(json.dumps({"bufferPoolBytes": TARGET, "persisted": True, "status": status}))


def apply():
    assert not STATE.exists(), "A previous setting is already saved; use verify or rollback"
    assert sql("SELECT @@GLOBAL.persisted_globals_load") == "1"
    assert sql("SELECT COUNT(*) FROM information_schema.innodb_trx") == "0", "Wait for active transactions"
    available = next(int(line.split()[1]) * 1024 for line in Path("/proc/meminfo").read_text().splitlines()
                     if line.startswith("MemAvailable:"))
    assert available > 8 * 1024 ** 3, "Insufficient available memory for this setting"
    config = json.loads(subprocess.check_output(["docker", "inspect", "ai-commerce-mysql"]))[0]
    limit = config["HostConfig"]["Memory"]
    assert limit == 0 or limit >= 4 * 1024 ** 3, "MySQL container memory limit is too small"
    old = int(sql("SELECT @@GLOBAL.innodb_buffer_pool_size"))
    assert old == 128 * 1024 ** 2, "Baseline changed; inspect before tuning"
    persisted = sql("SELECT VARIABLE_VALUE FROM performance_schema.persisted_variables "
                    "WHERE VARIABLE_NAME='innodb_buffer_pool_size'")
    STATE.parent.mkdir(parents=True, exist_ok=True)
    with STATE.open("x") as file:
        json.dump({"runtime": old, "persisted": persisted or None, "target": TARGET}, file)
    STATE.chmod(0o600)
    sql("SET PERSIST innodb_buffer_pool_size=" + str(TARGET))
    verify()


def rollback():
    before = json.loads(STATE.read_text())
    assert int(sql("SELECT @@GLOBAL.innodb_buffer_pool_size")) == TARGET, "Setting changed since tuning"
    if before["persisted"] is None:
        sql("RESET PERSIST IF EXISTS innodb_buffer_pool_size")
    else:
        sql("SET PERSIST_ONLY innodb_buffer_pool_size=" + str(int(before["persisted"])))
    sql("SET GLOBAL innodb_buffer_pool_size=" + str(int(before["runtime"])))
    print(json.dumps({"restoredBytes": before["runtime"], "status": wait_for_resize(before["runtime"])}))


if __name__ == "__main__":
    {"apply": apply, "verify": verify, "rollback": rollback}[sys.argv[2]]()
