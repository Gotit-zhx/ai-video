"""Read-only comparison of official order timestamps against stored snapshots.

Run on the application host. Credentials remain in memory; output contains only
allowlisted order IDs, statuses and timestamp fields, never buyer information.
"""
import base64
import hashlib
import hmac
import json
import re
import subprocess
import time
import urllib.request
import urllib.parse
from datetime import datetime
from zoneinfo import ZoneInfo
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

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
IDS = FIRST + SECOND
ORG = "6a61f788-6685-4b64-82b1-eec97ed8cf90"


def sql(statement):
    assert statement.lstrip().upper().startswith("SELECT ")
    process = subprocess.run(
        ["docker", "exec", "-i", "ai-commerce-mysql", "sh", "-c",
         'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --default-character-set=utf8mb4 -uroot -NBr "$MYSQL_DATABASE"'],
        input=statement, text=True, capture_output=True, timeout=35)
    if process.returncode:
        raise RuntimeError("Read-only SQL failed")
    return [json.loads(line) for line in process.stdout.splitlines() if line.strip()]


def fields(value, prefix=""):
    result = {}
    if isinstance(value, dict):
        for key, child in value.items():
            if re.search(r"token|cipher|secret|buyer|recipient|address|phone|email|beneficiary|bank|account", key, re.I):
                continue
            result.update(fields(child, prefix + "." + key if prefix else key))
    elif isinstance(value, list):
        for index, child in enumerate(value):
            result.update(fields(child, prefix + "[" + str(index) + "]"))
    elif re.search(r"time|date|complete|finish", prefix.rsplit(".", 1)[-1], re.I):
        result[prefix] = value
    return result


def main():
    env = dict(item.split("=", 1) for item in json.loads(
        subprocess.check_output(["docker", "inspect", "ai-commerce-app"]))[0]["Config"]["Env"])
    assert len(set(IDS)) == 42 and all(re.fullmatch(r"\d{18}", item) for item in IDS)
    quoted = ",".join("'" + item + "'" for item in IDS)
    rows = sql("SELECT /*+ MAX_EXECUTION_TIME(15000) */ JSON_OBJECT('shop_id',shop_id,'order_id',order_id,"
               "'raw',CAST(raw_json AS JSON)) FROM finance_records WHERE organization_id='" + ORG
               + "' AND kind='ORDER' AND source='API' AND platform='TIKTOK_SHOP' AND order_id IN (" + quoted + ");")
    if {row["order_id"] for row in rows} != set(IDS):
        raise RuntimeError("Some requested order snapshots are missing")
    results = []
    for shop in sorted({row["shop_id"] for row in rows}):
        assert re.fullmatch(r"[a-f0-9-]{36}", shop)
        connections = sql("SELECT JSON_OBJECT('user_id',user_id,'shop_cipher',shop_cipher,"
                          "'token',access_token_encrypted,'region',region,'timezone',timezone) "
                          "FROM finance_connections WHERE organization_id='" + ORG + "' AND id='" + shop
                          + "' AND status='ACTIVE';")
        assert len(connections) == 1
        connection = connections[0]
        payload = base64.b64decode(connection["token"][3:])
        aad = (ORG + ":finance-shop:" + connection["user_id"] + ":" + shop).encode()
        credential_key = env.get("APP_SHOP_CREDENTIAL_KEY", "local-development-shop-secret-change-me")
        token = AESGCM(hashlib.sha256(credential_key.encode()).digest()).decrypt(
            payload[:12], payload[12:], aad).decode()
        orders = {row["order_id"]: row["raw"] for row in rows if row["shop_id"] == shop}
        fetched = {}
        for version in ("202309", "202507"):
            fetched[version] = {}
            for offset in range(0, len(orders), 20):
                identifiers = list(orders)[offset:offset + 20]
                path = "/order/" + version + "/orders"
                query = {"app_key": env["APP_TIKTOK_SHOP_APP_KEY"], "shop_cipher": connection["shop_cipher"],
                         "timestamp": str(int(time.time())), "ids": ",".join(identifiers)}
                secret = env["APP_TIKTOK_SHOP_APP_SECRET"]
                signing = secret + path + "".join(k + query[k] for k in sorted(query)) + secret
                query["sign"] = hmac.new(secret.encode(), signing.encode(), hashlib.sha256).hexdigest()
                request = urllib.request.Request("https://open-api.tiktokglobalshop.com" + path + "?"
                                                 + urllib.parse.urlencode(query),
                                                 headers={"x-tts-access-token": token})
                with urllib.request.urlopen(request, timeout=30) as response:
                    envelope = json.load(response)
                if envelope.get("code") != 0:
                    raise RuntimeError("Official order query failed: code " + str(envelope.get("code")))
                batch = {str(order["id"]): order for order in envelope.get("data", {}).get("orders", [])}
                assert set(batch) == set(identifiers), "Unexpected order identities"
                fetched[version].update(batch)
        for identifier, stored in orders.items():
            latest = fetched["202507"][identifier]
            old_fields, new_fields, stored_fields = fields(fetched["202309"][identifier]), fields(latest), fields(stored)
            def local(value):
                return datetime.fromtimestamp(int(value), ZoneInfo(connection["timezone"])).isoformat() if value else None
            results.append({
                "order_id": identifier, "batch": 1 if identifier in FIRST else 2,
                "shop_id": shop, "timezone": connection["timezone"], "status": latest.get("status"),
                "created_local": local(latest.get("create_time")),
                "updated_local": local(latest.get("update_time")),
                "delivered_local": local(latest.get("delivery_time")),
                "api_versions_time_fields_equal": old_fields == new_fields,
                "stored_time_fields_equal": stored_fields == new_fields,
                "missing_stored_fields": sorted(set(new_fields) - set(stored_fields)),
                "different_stored_fields": [k for k in new_fields if k in stored_fields and new_fields[k] != stored_fields[k]],
                "completion_fields": {k: v for k, v in new_fields.items() if re.search(r"complet|finish", k, re.I)},
                "api_time_fields": new_fields,
            })
    print(json.dumps({
        "checked_at": datetime.now(ZoneInfo("Asia/Shanghai")).isoformat(),
        "order_count": len(results),
        "different_or_missing_snapshots": sum(not row["stored_time_fields_equal"] for row in results),
        "completion_field_count": sum(bool(row["completion_fields"]) for row in results),
        "rows": sorted(results, key=lambda row: (row["batch"], row["order_id"])),
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        # Do not emit exception text, request URLs, headers, credentials or raw payloads.
        print(json.dumps({"error_type": type(error).__name__, "status": "AUDIT_INCOMPLETE"}))
        raise SystemExit(1)
