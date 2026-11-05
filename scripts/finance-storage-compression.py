#!/usr/bin/env python3
"""Enable native write-time compression after an isolated, verified LZ4 trial.

No table rebuild, recurring OPTIMIZE, index guessing, data deletion, or logs.
Run on the production host with the verified release receipt directory.
"""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import time

TABLES = ("finance_records", "finance_order_costs", "finance_qianyi_returns",
          "finance_label_invoice_rows", "finance_label_order_totals")


def sql(query, timeout=30):
    process = subprocess.run(
        ["docker", "exec", "-i", "ai-commerce-mysql", "sh", "-c",
         'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --default-character-set=utf8mb4 -uroot -NB "$MYSQL_DATABASE"'],
        input="SET SESSION MAX_EXECUTION_TIME=10000; SET SESSION lock_wait_timeout=3;\n" + query,
        text=True, capture_output=True, timeout=timeout)
    if process.returncode:
        raise RuntimeError(process.stderr[:1500])
    return process.stdout


def sizes():
    return sql("SELECT NAME,FS_BLOCK_SIZE,FILE_SIZE,ALLOCATED_SIZE FROM information_schema.INNODB_TABLESPACES "
               "WHERE NAME IN (" + ",".join("CONCAT(DATABASE(),'/" + name + "')" for name in TABLES) + ") ORDER BY NAME;")


def index_columns(name):
    rows = sql("SELECT NON_UNIQUE,COLUMN_NAME,COALESCE(SUB_PART,0),COLLATION,INDEX_TYPE "
               "FROM information_schema.statistics WHERE table_schema=DATABASE() AND "
               "table_name='finance_records' AND index_name='" + name + "' ORDER BY SEQ_IN_INDEX;")
    return rows.splitlines()


def sample():
    # Financial control fields only; raw API JSON and credentials never leave MySQL.
    query = """SELECT id,kind,external_id,order_id,statement_id,record_date,currency,amount,revenue
        FROM finance_records FORCE INDEX(idx_finance_shop_dashboard)
        WHERE organization_id='6a61f788-6685-4b64-82b1-eec97ed8cf90'
          AND shop_id='98c482ea-5f42-4117-a8d0-d14d8bb640da'
          AND record_date>='2026-10-01' AND record_date<'2026-11-01'
          AND kind IN ('BILL','SETTLEMENT') ORDER BY id;"""
    value = sql(query)
    return {"rows": len(value.splitlines()), "sha256": hashlib.sha256(value.encode()).hexdigest()}


def apply(root):
    qa = json.loads((root / "compression-qa.json").read_text())
    assert qa["count"] == 5000 and qa["plainHash"] == qa["compressedHash"]
    qa_sizes = [line.split("\t") for line in qa["sizes"].strip().splitlines()]
    assert int(qa_sizes[0][2]) < int(qa_sizes[1][2]) and qa_sizes[0][0].endswith("/compressed")
    assert 'lz4' in qa["compression"].lower()
    assert sql("SELECT @@innodb_file_per_table;").strip() == "1"
    before = {"sizes": sizes(), "sample": sample(), "freeBytes": __import__("shutil").disk_usage("/").free}
    assert before["sample"]["rows"] >= 5577
    changes = []
    for table in TABLES:
        attributes = sql("SELECT ROW_FORMAT,SPACE_TYPE,FS_BLOCK_SIZE,PAGE_SIZE FROM information_schema.INNODB_TABLESPACES "
                         "WHERE NAME=CONCAT(DATABASE(),'/" + table + "');").strip().split("\t")
        assert len(attributes) == 4 and attributes[:2] == ["Dynamic", "Single"]
        assert 0 < int(attributes[2]) * 2 <= int(attributes[3])
        option = sql("SELECT CREATE_OPTIONS FROM information_schema.tables WHERE table_schema=DATABASE() "
                     "AND table_name='" + table + "';").lower()
        if 'compression="lz4"' in option:
            continue
        assert "compression=" not in option or 'compression="none"' in option
        start = time.monotonic()
        sql("ALTER TABLE " + table + " COMPRESSION='lz4', ALGORITHM=INPLACE, LOCK=NONE;")
        changes.append({"table": table, "seconds": round(time.monotonic() - start, 3)})
        # A receipt is overwritten in place; no unbounded diagnostics.
        (root / "compression-progress.json").write_text(json.dumps(changes))
    old, covering = index_columns("idx_finance_bill"), index_columns("idx_finance_reconcile_tx")
    dropped = False
    if old:
        assert len(old) == 5 and len(covering) == 8 and covering[:len(old)] == old
        assert all(row.startswith("1\t") for row in old), "Never remove unique constraints"
        assert sql("SELECT COUNT(*) FROM information_schema.key_column_usage WHERE table_schema=DATABASE() "
                   "AND table_name='finance_records' AND referenced_table_name IS NOT NULL;").strip() == "0"
        # The covering index must remain visible and available to the optimizer.
        assert sql("SELECT MIN(IS_VISIBLE) FROM information_schema.statistics WHERE table_schema=DATABASE() "
                   "AND table_name='finance_records' AND index_name='idx_finance_reconcile_tx';").strip() == "YES"
        sql("ALTER TABLE finance_records DROP INDEX idx_finance_bill, ALGORITHM=INPLACE, LOCK=NONE;")
        dropped = True
    after = {"sizes": sizes(), "sample": sample(), "freeBytes": __import__("shutil").disk_usage("/").free}
    assert before["sample"] == after["sample"], "Historical control changed; investigate before claiming acceptance"
    result = {"before": before, "after": after, "changed": changes, "redundantBillIndexDropped": dropped,
              "compression": "lz4", "automaticOnPageWrites": True, "largeTableRebuilt": False,
              "rowDataDeleted": False, "persistentLogsAdded": False}
    (root / "compression-activation.json").write_text(json.dumps(result, indent=2))
    print(json.dumps(result))


if __name__ == "__main__":
    apply(Path(sys.argv[1]).resolve(strict=True))
