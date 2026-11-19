"""Seed an empty, workspace-owned development database from aggregate shape only."""
from datetime import date
import hashlib
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
PROJECT = "heyi-dev-" + hashlib.sha256(str(ROOT).encode()).hexdigest()[:10]
MYSQL = PROJECT + "-mysql-1"
label = subprocess.check_output([
    "docker", "inspect", "--format",
    '{{index .Config.Labels "com.docker.compose.project.working_dir"}}',
    MYSQL,
], text=True).strip()
if label != str(ROOT):
    raise SystemExit("The disposable development MySQL instance does not belong to this worktree")

mysql = ["docker", "exec", "-i", MYSQL, "sh", "-ec",
         'test "$MYSQL_DATABASE" = ai_commerce_dev; MYSQL_PWD="$MYSQL_PASSWORD" exec mysql --batch --skip-column-names -u "$MYSQL_USER" "$MYSQL_DATABASE"']
args = sys.argv[1:]
if args not in ([], ["--details"]):
    raise SystemExit("Usage: seed-finance-bi-scale.py [--details]")
details = bool(args)
month = os.environ.get("FINANCE_BI_MONTH", "")
try:
    start_month = date.fromisoformat(month + "-01") if month else None
except ValueError:
    raise SystemExit("FINANCE_BI_MONTH must use YYYY-MM") from None
if start_month and not date(2000, 1, 1) <= start_month <= date.today().replace(day=1):
    raise SystemExit("Choose a fixture month from January 2000 through the current month")
month_sql = f"DATE('{start_month.isoformat()}')" if start_month else "CAST(DATE_FORMAT(UTC_DATE()-INTERVAL 1 MONTH,'%Y-%m-01') AS DATE)"
table = "finance_label_imports" if details else "finance_records"
empty = subprocess.run(mysql, input=f"SELECT EXISTS(SELECT 1 FROM {table} LIMIT 1);",
                       text=True, check=True, capture_output=True).stdout.strip()
if empty != "0":
    raise SystemExit("Use an empty target table in the development fixture")

session = f"""
SET @org=(SELECT id FROM organizations WHERE code='heyi');
SET @owner=(SELECT id FROM app_users WHERE organization_id=@org ORDER BY created_at LIMIT 1);
SET @start={month_sql};
SET @days=DAY(LAST_DAY(@start));
CREATE TEMPORARY TABLE seq(n INT PRIMARY KEY) ENGINE=InnoDB
WITH digits(n) AS (SELECT 0 UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9)
SELECT a.n+10*b.n+100*c.n+1000*d.n+10000*e.n+100000*f.n n
FROM digits a CROSS JOIN digits b CROSS JOIN digits c CROSS JOIN digits d CROSS JOIN digits e CROSS JOIN digits f;
"""
setup = """
INSERT INTO finance_groups(id,organization_id,name)
SELECT CONCAT('scale-group-',n),@org,CONCAT('Synthetic group ',n) FROM seq WHERE n<8;
INSERT INTO finance_connections(id,organization_id,user_id,platform,external_shop_id,name,status,enabled,
 currency,timezone,token_group_id,access_token_encrypted,scopes_json,group_id)
SELECT CONCAT('scale-',LEFT(SHA2(n,256),20)),@org,@owner,IF(n<58,'TIKTOK_SHOP','SHOPEE'),
 CONCAT('scale-',n),CONCAT('Synthetic shop ',n),'ACTIVE',FALSE,ELT(1+MOD(n,3),'PHP','PHP','THB'),
 'Asia/Shanghai',CONCAT('scale-token-',n),'unused',JSON_ARRAY(),CONCAT('scale-group-',MOD(n,8))
FROM seq WHERE n<87;
INSERT INTO finance_fx_historical_rates(provider,rate_date,currency,rate_cny,valid_through_date,fetched_at,source_sha256)
SELECT 'ECB',@start+INTERVAL (n-30) DAY,code,rate,@start+INTERVAL (n-30) DAY,UTC_TIMESTAMP(6),REPEAT('0',64)
FROM seq CROSS JOIN (SELECT 'CNY' code,1 rate UNION ALL SELECT 'PHP',0.123 UNION ALL SELECT 'THB',0.2
 UNION ALL SELECT 'MYR',1.6 UNION ALL SELECT 'USD',7.2) currencies
WHERE n<=30+DATEDIFF(UTC_DATE(),@start) ON DUPLICATE KEY UPDATE valid_through_date=GREATEST(valid_through_date,VALUES(valid_through_date));
"""
if details:
    sql = """
INSERT INTO finance_label_imports(id,organization_id,source_name,content_hash,storage_name,stored_bytes,status,
 period_start,period_end,row_count,matched_count,created_by)
SELECT CONCAT('scale-label-',n),@org,CONCAT('Synthetic invoice ',n,'.xlsx'),SHA2(CONCAT('scale-label-',n),256),
 'synthetic',0,'READY',@start,LAST_DAY(@start),40000,40000,@owner FROM seq WHERE n<6;
INSERT INTO finance_label_state(organization_id,active_generation,requested_revision,applied_revision,source_revision)
VALUES(@org,'scale-label-generation',1,1,1);
"""
    with subprocess.Popen(mysql, stdin=subprocess.PIPE, text=True) as app:
        app.stdin.write(session+sql)
        for batch in range(6):
            app.stdin.write(f"""
INSERT INTO finance_label_invoice_rows(import_id,source_row,organization_id,event_key,row_key,bucket_key,order_id,shop_hint,
 customer,warehouse,document_no,transaction_date,amount_cny,matched_shop_id,match_status,match_checked)
SELECT 'scale-label-{batch}',1+MOD(n,40000),@org,SHA2(n,256),SHA2(n,256),SHA2(n,256),CONCAT('scale-order-',n),'',
 'synthetic','test','',@start+INTERVAL MOD(FLOOR(n/87),@days) DAY,2.123456,
 CONCAT('scale-',LEFT(SHA2(MOD(n,87),256),20)),'MATCHED',TRUE
FROM seq WHERE n>={batch*40000} AND n<{(batch+1)*40000};
""")
        app.stdin.write("""
INSERT INTO finance_label_order_totals(organization_id,generation,shop_id,order_id,amount_cny,pending_count,shipment_count)
SELECT @org,'scale-label-generation',CONCAT('scale-',LEFT(SHA2(MOD(n,87),256),20)),CONCAT('scale-order-',n),2.123456,0,1
FROM seq WHERE n<130000;
INSERT INTO finance_qianyi_returns(organization_id,shop_id,return_number,account_key,order_id,receipt_date,source_updated_at,source_hash,data_json,cost_status)
SELECT @org,CONCAT('scale-',LEFT(SHA2(MOD(n,87),256),20)),CONCAT('scale-return-',n),REPEAT('f',64),CONCAT('scale-order-',n),
 @start+INTERVAL MOD(FLOOR(n/87),@days) DAY,UNIX_TIMESTAMP(@start)*1000,SHA2(n,256),
 JSON_OBJECT('returnStatus','FINISH','refundStatus','FINISH','orderRefundAmount','7.25','currency','PHP',
 'receiveTime',UNIX_TIMESTAMP(@start+INTERVAL MOD(FLOOR(n/87),@days) DAY)*1000),'READY'
FROM seq WHERE n<65000;
SELECT 'label_rows',COUNT(*) FROM finance_label_invoice_rows;
SELECT 'return_rows',COUNT(*) FROM finance_qianyi_returns;
""")
        app.stdin.close()
        if app.wait():
            raise SystemExit("Seeding details failed in the disposable database")
    raise SystemExit(0)

sizes = {"ORDER": 285_000, "SETTLEMENT": 300_000, "FEE": 950_000, "BILL": 26_000,
         "REFUND": 13_000, "PENDING": 16_000, "AD": 3_000, "LIVE": 5_000}
columns = """id,organization_id,user_id,shop_id,platform,kind,external_id,order_id,statement_id,category,status,
record_date,currency,amount,revenue,source,raw_json"""
with subprocess.Popen(mysql, stdin=subprocess.PIPE, text=True) as app:
    app.stdin.write(session+setup)
    for kind, size in sizes.items():
        source_kind = "ORDER" if kind == "LIVE" else kind
        when = "UTC_DATE()" if kind == "LIVE" else "@start+INTERVAL MOD(FLOOR(n/87),@days) DAY"
        for start in range(0, size, 25_000):
            app.stdin.write(f"""
INSERT INTO finance_records({columns})
SELECT SHA2(CONCAT('{kind}:',n),256),@org,@owner,CONCAT('scale-',LEFT(SHA2(MOD(n,87),256),20)),
 IF(MOD(n,87)<58,'TIKTOK_SHOP','SHOPEE'),'{source_kind}',
 IF('{kind}'='BILL',CONCAT('statement-',n),CONCAT('{kind}-',n)),
 CASE WHEN '{kind}'='BILL' THEN '' WHEN '{kind}'='FEE' THEN CONCAT('scale-order-',MOD(n,285000))
 ELSE CONCAT('scale-order-',n) END,
 IF('{kind}'='BILL',CONCAT('statement-',n),CONCAT('statement-',MOD(n,26000))),
 CASE WHEN '{kind}'='FEE' THEN CONCAT('fee-',MOD(FLOOR(n/87/31),42)) WHEN '{kind}'='SETTLEMENT' THEN 'ORDER' ELSE '' END,
 CASE WHEN '{kind}'='ORDER' AND MOD(n,27)=0 THEN 'CANCELLED'
      WHEN '{kind}'='REFUND' THEN 'REFUND_SUCCESS' WHEN '{kind}'='LIVE' THEN 'COMPLETED' ELSE 'SETTLED' END,
 {when},ELT(1+MOD(MOD(n,87),3),'PHP','PHP','THB'),
 IF(MOD(n,47)=0,NULL,IF('{kind}'='FEE',1+MOD(n,19),20+MOD(n,401))+0.123456),
 IF('{kind}' IN ('SETTLEMENT','BILL'),60+MOD(n,401)+0.123456,NULL),'API',
 JSON_OBJECT('create_time',UNIX_TIMESTAMP({when})+MOD(n,24)*3600,'income_amount',80,
 'order_status','COMPLETED','synthetic_payload',REPEAT('s',IF('{kind}' IN ('ORDER','SETTLEMENT'),600,170)))
FROM seq WHERE n>={start} AND n<{min(start+25_000,size)};
""")
    app.stdin.write("""
INSERT INTO finance_order_costs(order_record_id,organization_id,shop_id,order_id,record_date,status,amount_cny,
 known_amount_cny,line_count,matched_lines,source_hash,data_json)
SELECT SHA2(CONCAT('ORDER:',n),256),@org,CONCAT('scale-',LEFT(SHA2(MOD(n,87),256),20)),CONCAT('scale-order-',n),
 @start+INTERVAL MOD(FLOOR(n/87),@days) DAY,'READY',8.123456,8.123456,1,1,SHA2(n,256),JSON_OBJECT()
FROM seq WHERE n<130000;
SELECT kind,COUNT(*) FROM finance_records GROUP BY kind ORDER BY kind;
SELECT 'order_costs',COUNT(*) FROM finance_order_costs;
""")
    app.stdin.close()
    if app.wait():
        raise SystemExit("Seeding failed in the disposable database")
