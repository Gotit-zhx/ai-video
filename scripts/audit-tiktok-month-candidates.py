"""Read-only month-source audit; credentials and buyer data never leave the host."""
import base64
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime
import hashlib
import hmac
import json
import re
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
from zoneinfo import ZoneInfo

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

ORG = "6a61f788-6685-4b64-82b1-eec97ed8cf90"
SHOP = "fecf7801-0929-4076-bbaa-ee42f4dce87b"
REFERENCE = "586217421074433110"
FIRST = """585766520348574952 585766481305634280 585764977265706674 585764848011544310
585764757296089087 585764555994138355 585762432859801561 585761436475295624
585760635317815276 585759153529849771 585757874943591564 585756151120364841
585756204777834266 585755547295188131 585755480570627698""".split()
SECOND = """586208660231587825 586248667916502979 586321253913888042 586321099662722435
586251057227334831 586189886086219428 586248749252117860 586229882290996973
586167707968701523 586182066388174354 586259902499686279 586251991250536234
586255631761900988 586300596333020686 586208583904822634 586260107070178724
586325530678035580 586248933727962163 586321060062397609 586316466496374177
586319134020044042 586204257616430108 586323830825846056 586259679067866925
586250977074906433 586319961778652556 586217421074433110""".split()
SENSITIVE = re.compile(
    r"token|cipher|secret|buyer_id|recipient|address|phone|email|beneficiary|bank|account|tracking_number",
    re.I)
FIELDS = re.compile(r"time|date|deadline|period|cycle|status|type|reason|eligible|complete|finish|expire", re.I)
JOIN_FIELDS = {"order_id", "main_order_id", "adjustment_order_id", "statement_id"}


def sql(query):
    assert query.lstrip().upper().startswith("SELECT ")
    process = subprocess.run(
        ["docker", "exec", "-i", "ai-commerce-mysql", "sh", "-c",
         'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --default-character-set=utf8mb4 -uroot -NBr "$MYSQL_DATABASE"'],
        input=query, text=True, capture_output=True, timeout=30)
    if process.returncode:
        raise RuntimeError("Read-only month audit SQL failed")
    return [json.loads(line) for line in process.stdout.splitlines() if line.strip()]


def inspect_fields(value, prefix=""):
    """Return only timing/status/join fields, plus schema paths without values."""
    selected, paths = {}, []
    if isinstance(value, dict):
        for key, child in value.items():
            if SENSITIVE.search(key):
                continue
            path = prefix + "." + key if prefix else key
            paths.append(path)
            if isinstance(child, (dict, list)):
                a, b = inspect_fields(child, path)
                selected.update(a)
                paths.extend(b)
            elif FIELDS.search(key) or key in JOIN_FIELDS:
                if isinstance(child, (bool, int, float)) or child is None:
                    selected[path] = child
                elif isinstance(child, str) and len(child) <= 500:
                    selected[path] = child
    elif isinstance(value, list):
        for index, child in enumerate(value[:500]):
            a, b = inspect_fields(child, prefix + "[" + str(index) + "]")
            selected.update(a)
            paths.extend(b)
    return selected, paths


def main():
    ids = FIRST + SECOND
    assert len(set(ids)) == 42 and all(re.fullmatch(r"\d{18}", oid) for oid in ids)
    env = dict(item.split("=", 1) for item in json.loads(
        subprocess.check_output(["docker", "inspect", "ai-commerce-app"]))[0]["Config"]["Env"])
    connection, = sql("SELECT JSON_OBJECT('user_id',user_id,'shop_cipher',shop_cipher,"
                       "'token',access_token_encrypted,'timezone',timezone) FROM finance_connections "
                       "WHERE organization_id='" + ORG + "' AND id='" + SHOP + "' AND status='ACTIVE'")
    encrypted = base64.b64decode(connection["token"][3:])
    key = env.get("APP_SHOP_CREDENTIAL_KEY", "local-development-shop-secret-change-me")
    aad = (ORG + ":finance-shop:" + connection["user_id"] + ":" + SHOP).encode()
    token = AESGCM(hashlib.sha256(key.encode()).digest()).decrypt(
        encrypted[:12], encrypted[12:], aad).decode()

    def call(path, query=None, body=None):
        assert re.fullmatch(r"/finance/202501/orders/\d{18}/statement_transactions", path) or \
            re.fullmatch(r"/return_refund/202602/orders/\d{18}/aftersale_eligibility", path) or \
            path == "/return_refund/202603/aftersales/search"
        params = {"app_key": env["APP_TIKTOK_SHOP_APP_KEY"], "shop_cipher": connection["shop_cipher"],
                  "timestamp": str(int(time.time())), **(query or {})}
        payload = json.dumps(body, separators=(",", ":")) if body is not None else ""
        secret = env["APP_TIKTOK_SHOP_APP_SECRET"]
        signing = secret + path + "".join(k + str(params[k]) for k in sorted(params)) + payload + secret
        params["sign"] = hmac.new(secret.encode(), signing.encode(), hashlib.sha256).hexdigest()
        request = urllib.request.Request(
            "https://open-api.tiktokglobalshop.com" + path + "?" + urllib.parse.urlencode(params),
            data=payload.encode() if body is not None else None,
            headers={"x-tts-access-token": token, "Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(request, timeout=25) as response:
                envelope = json.load(response)
        except urllib.error.HTTPError as error:
            return {"http_status": error.code}
        data = envelope.get("data", {})
        selected, paths = inspect_fields(data)
        return {"code": envelope.get("code"), "request_id": envelope.get("request_id"),
                "fields": selected, "schema_paths": sorted(set(paths)), "data": data}

    def finance_order(oid):
        response = call("/finance/202501/orders/" + oid + "/statement_transactions")
        data = response.pop("data", {})
        if response.get("code") == 0 and str(data.get("order_id")) != oid:
            raise RuntimeError("Finance order identity mismatch")
        return {"order_id": oid, "batch": 1 if oid in FIRST else 2, **response,
                "settlement_amount": data.get("settlement_amount"),
                "currency": data.get("currency"),
                "total_count": data.get("total_count")}

    # Verify access on one order before scheduling the bounded batch.
    first = finance_order(REFERENCE)
    financial = [first]
    if first.get("code") == 0:
        with ThreadPoolExecutor(max_workers=2) as workers:
            financial.extend(workers.map(finance_order, [oid for oid in ids if oid != REFERENCE]))

    eligibility = []
    for oid in [REFERENCE, FIRST[0]]:
        reply = call("/return_refund/202602/orders/" + oid + "/aftersale_eligibility",
                     {"initiate_aftersale_user": "BUYER"})
        reply.pop("data", None)
        eligibility.append({"order_id": oid, **reply})
        if reply.get("code") != 0:
            break

    aftersales, seen_tokens, page_token = [], set(), ""
    for _ in range(5):
        reply = call("/return_refund/202603/aftersales/search", body={
            "filters": {"main_order_ids": SECOND},
            "whitelisted_data_fields": ["LINE_ITEMS", "SKU_RETURN_REQUESTS"],
            "pagination": {"page_size": 100, **({"page_token": page_token} if page_token else {})}})
        data = reply.pop("data", {})
        aftersales.append(reply)
        if reply.get("code") != 0:
            break
        page_token = data.get("next_page_token") or data.get("pagination", {}).get("next_page_token") or ""
        if not page_token:
            break
        if page_token in seen_tokens:
            raise RuntimeError("Repeated aftersales page token")
        seen_tokens.add(page_token)
    else:
        raise RuntimeError("Aftersales pagination exceeded bounded audit")

    quoted = ",".join("'" + oid + "'" for oid in ids)
    records = sql("SELECT /*+ MAX_EXECUTION_TIME(12000) */ JSON_OBJECT('order_id',order_id,'kind',kind,"
                  "'record_date',record_date,'statement_id',statement_id,'raw',CAST(raw_json AS JSON)) "
                  "FROM finance_records WHERE organization_id='" + ORG + "' AND shop_id='" + SHOP
                  + "' AND order_id IN (" + quoted + ") AND source='API' ORDER BY order_id,kind LIMIT 1000")
    assert len(records) < 1000
    for record in records:
        record["fields"], record["schema_paths"] = inspect_fields(record.pop("raw"))
    print(json.dumps({"checked_at": datetime.now(ZoneInfo("Asia/Shanghai")).isoformat(),
                      "timezone": connection["timezone"], "finance_by_order": financial,
                      "eligibility": eligibility, "aftersales": aftersales, "stored_records": records},
                     ensure_ascii=False, indent=2))


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(json.dumps({"status": "AUDIT_INCOMPLETE", "error_type": type(error).__name__}))
        raise SystemExit(1)
