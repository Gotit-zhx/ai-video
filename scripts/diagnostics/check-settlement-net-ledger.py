"""Compare unchanged source net amounts across a derived-data backfill, allowing new sync rows."""
import gzip
import json
import os
from pathlib import Path
import subprocess
import sys

os.umask(0o077)
root, mode = Path(sys.argv[1]), sys.argv[2]
target = root / "platform-net-before.tsv.gz"
query = """
SELECT /*+ MAX_EXECUTION_TIME(30000) */ id,shop_id,kind,currency,amount
FROM finance_records FORCE INDEX(idx_finance_company_dashboard)
WHERE organization_id='6a61f788-6685-4b64-82b1-eec97ed8cf90'
 AND kind IN ('SETTLEMENT','BILL') AND source='API'
ORDER BY id;
"""
result = subprocess.run(["docker", "exec", "-i", "ai-commerce-mysql", "sh", "-c",
    'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --default-character-set=utf8mb4 -uroot -NB "$MYSQL_DATABASE"'],
    input=query.encode(), capture_output=True, timeout=40)
if result.returncode:
    raise SystemExit("Platform net audit read failed")
if mode == "before":
    assert not target.exists(), "Do not overwrite the baseline"
    target.write_bytes(gzip.compress(result.stdout))
    print(json.dumps({"platformNetBaselineRecords": len(result.stdout.splitlines())}))
else:
    before = {line.split(b"\t", 1)[0]: line for line in gzip.decompress(target.read_bytes()).splitlines()}
    after = {line.split(b"\t", 1)[0]: line for line in result.stdout.splitlines()}
    changed = [key.decode() for key, value in before.items() if after.get(key) != value]
    report = {"originalRecords": len(before), "newSyncRecords": len(set(after) - set(before)),
              "changedOrRemovedOriginalAmounts": len(changed), "changedIds": changed[:100]}
    (root / "platform-net-verification.json").write_text(json.dumps(report))
    print(json.dumps(report))
    if changed:
        raise SystemExit("Provider ledger changes require inspection; do not report exact preservation yet")
