"""One authorized reporting-month correction; exact ledger guards and reversible metadata only."""
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from decimal import Decimal

os.umask(0o077)
root = Path(sys.argv[1]).resolve()
mode = sys.argv[2]
spec = json.loads((root / "approved-months.json").read_text())
org, shop, statement, month = (spec[k] for k in ("organization", "shop", "statement", "month"))
assert re.fullmatch(r"[a-f0-9-]{36}", org) and re.fullmatch(r"[a-f0-9-]{36}", shop)
assert statement.isdigit() and re.fullmatch(r"\d{4}-\d{2}", month)
targets, controls = spec["orders"], spec["septemberControls"]
assert len(set(targets)) == 27 and len(set(controls)) == 15 and not set(targets) & set(controls)
assert all(re.fullmatch(r"\d{18}", item) for item in targets + controls)

def sql(query):
    result = subprocess.run(["docker", "exec", "-i", "ai-commerce-mysql", "sh", "-c",
        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --default-character-set=utf8mb4 -uroot -NB --raw "$MYSQL_DATABASE"'],
        input=query.encode(), capture_output=True, timeout=60)
    if result.returncode:
        raise RuntimeError("Scoped reporting-month SQL failed; transaction was not committed.")
    return result.stdout.decode().strip()

def quoted(items):
    return ",".join("'" + v + "'" for v in items)

scope = f"organization_id='{org}' AND shop_id='{shop}'"
target = scope + f" AND order_id IN ({quoted(targets)}) AND statement_id='{statement}' AND platform='TIKTOK_SHOP' AND source='API' AND kind IN ('SETTLEMENT','FEE')"

def records():
    query = f"""SELECT JSON_OBJECT('id',id,'kind',kind,'order',order_id,'statement',statement_id,
      'date',CAST(record_date AS CHAR),'currency',currency,'amount',CAST(amount AS CHAR),'revenue',CAST(revenue AS CHAR),
      'status',status,'month',JSON_UNQUOTE(JSON_EXTRACT(raw_json,'$._finance_reporting_month')),
      'originalDate',JSON_UNQUOTE(JSON_EXTRACT(raw_json,'$._finance_original_settlement_date')))
      FROM finance_records WHERE {scope} AND
      (order_id IN ({quoted(targets+controls)}) OR (kind='BILL' AND statement_id IN ('{statement}','7679985599222187783')))
      ORDER BY id"""
    return [json.loads(line) for line in sql(query).splitlines()]

def costs():
    return [json.loads(line) for line in sql(f"""SELECT JSON_OBJECT('id',order_record_id,'order',order_id,
      'status',status,'amount',CAST(amount_cny AS CHAR),'known',CAST(known_amount_cny AS CHAR),
      'label',CAST(label_cost_cny AS CHAR),'input',source_hash,'dataHash',SHA2(CAST(data_json AS CHAR),256))
      FROM finance_order_costs WHERE {scope} AND order_id IN ({quoted(targets+controls)}) ORDER BY order_record_id""").splitlines()]

def persist(name, value):
    path = root / name
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n")
    path.chmod(0o600)

def money(rows):
    return [{k: row[k] for k in ("id", "kind", "order", "statement", "currency", "amount", "revenue", "status")} for row in rows]

def verify():
    before = json.loads((root / "month-before.json").read_text())
    after = records()
    assert money(before["records"]) == money(after), "Financial rows/amounts changed; investigate before accepting"
    assert before["costs"] == costs(), "Stored cost snapshots changed"
    cancelled = {r["order"] for r in after if r["kind"] == "ORDER" and r["order"] in targets and r["status"] == "CANCELLED"}
    excluded = [c for c in before["costs"] if c["order"] in cancelled]
    assert len(excluded) == 9 and all(c["status"] == "EXCLUDED" and Decimal(c["amount"]) == 0 for c in excluded)
    changed = []
    for old, row in zip(before["records"], after):
        expected = row["order"] in targets and row["statement"] == statement and row["kind"] in ("SETTLEMENT", "FEE")
        if expected:
            assert row["date"] == month + "-01" and row["month"] == month and row["originalDate"] == old["date"]
            changed.append(row)
        else:
            assert row == old, "Unrelated original date/metadata changed"
    settlements = [r for r in changed if r["kind"] == "SETTLEMENT"]
    assert {r["order"] for r in settlements} == set(targets) and len(settlements) == 27
    assert len([r for r in after if r["kind"] == "SETTLEMENT" and r["order"] in controls and r["date"].startswith("2026-09")]) == 15
    result = {"settlementsMoved": len(settlements), "feeRowsMoved": len(changed)-len(settlements),
        "septemberControlsPreserved": 15, "amountsUnchanged": True, "costSnapshotsUnchanged": True,
        "officialBillRowsUnchanged": True, "targetMonth": month}
    result["cancelledCostsStillExcluded"] = len(excluded)
    persist("month-verification.json", result)
    print(json.dumps(result, ensure_ascii=False))

if mode == "snapshot":
    assert not (root / "month-before.json").exists()
    current = records()
    selected = [r for r in current if r["kind"] == "SETTLEMENT" and r["order"] in targets]
    assert len(selected) == 27 and all(r["date"] == "2026-09-30" and r["statement"] == statement and r["month"] is None for r in selected)
    order_rows = [r for r in current if r["kind"] == "ORDER" and r["order"] in targets]
    assert len(order_rows) == 27 and sum(r["status"] == "COMPLETED" for r in order_rows) == 18 and sum(r["status"] == "CANCELLED" for r in order_rows) == 9
    assert len(costs()) == 42
    persist("month-before.json", {"records": current, "costs": costs()})
    print("Captured 27 targets, 15 September controls, original bills and 42 cost snapshots.")
elif mode == "apply":
    before = json.loads((root / "month-before.json").read_text())
    assert before["records"] == records() and before["costs"] == costs(), "Refresh evidence: scoped records changed"
    assert sql("SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1") == "134"
    assert sql(f"SELECT COUNT(*) FROM finance_connections WHERE organization_id='{org}' AND id='{shop}' AND status<>'REMOVED'") == "1"
    assert sql(f"SELECT COUNT(*) FROM finance_reporting_months WHERE {scope} AND order_id IN ({quoted(targets)})") == "0"
    values = ",".join(f"('{org}','{shop}','{order}','{statement}','{month}-01','USER_CONFIRMED_MONTH','用户核对官方月份后确认本批归入2026年10月；不推测具体完成时间','thread-user-20261028')" for order in targets)
    sql(f"""START TRANSACTION;
        SELECT id FROM finance_connections WHERE organization_id='{org}' AND id='{shop}' AND status<>'REMOVED' FOR UPDATE;
        INSERT INTO finance_reporting_months(organization_id,shop_id,order_id,statement_id,reporting_month,source,reason,confirmed_by) VALUES {values};
        UPDATE finance_records SET raw_json=JSON_SET(raw_json,
          '$._finance_original_settlement_date',CAST(record_date AS CHAR),
          '$._finance_reporting_month','{month}',
          '$._finance_reporting_source','用户确认归属月份；非接口完成时间'),
          record_date='{month}-01',updated_at=updated_at WHERE {target};
        COMMIT;""")
    verify()
elif mode == "verify":
    verify()
elif mode == "rollback":
    # Retain any newer provider fields and amounts; restore only our attribution.
    sql(f"""START TRANSACTION;
        SELECT id FROM finance_connections WHERE organization_id='{org}' AND id='{shop}' FOR UPDATE;
        UPDATE finance_records SET record_date=CAST(JSON_UNQUOTE(JSON_EXTRACT(raw_json,'$._finance_original_settlement_date')) AS DATE),
          raw_json=JSON_REMOVE(raw_json,'$._finance_original_settlement_date','$._finance_reporting_month','$._finance_reporting_source'),
          updated_at=updated_at WHERE {target} AND JSON_UNQUOTE(JSON_EXTRACT(raw_json,'$._finance_reporting_month'))='{month}';
        DELETE FROM finance_reporting_months WHERE {scope} AND statement_id='{statement}' AND order_id IN ({quoted(targets)}) AND reporting_month='{month}-01';
        COMMIT;""")
    print("Only the approved reporting correction was removed; provider amounts and fields retained.")
else:
    raise ValueError("Unknown mode")
