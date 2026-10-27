"""Publish real maintenance progress; no financial values or credentials are exposed."""
import json
import os
from pathlib import Path
import subprocess
import time
import hashlib
import uuid


class SettlementProgress:
    def __init__(self, root, app):
        self.root = Path(root)
        env = dict(item.split("=", 1) for item in app["Config"]["Env"])
        self.password = next((v for k, v in env.items() if "REDIS" in k and "PASSWORD" in k), "")
        result = subprocess.run(
            ["docker", "exec", "-i", "ai-commerce-mysql", "sh", "-c",
             'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot "$MYSQL_DATABASE" -NB'],
            input=b"""SET SESSION max_execution_time=10000;
                SELECT JSON_OBJECT('shopId',c.id,'organization',c.organization_id,
                'total',COALESCE(r.total,0)) FROM finance_connections c
                LEFT JOIN (SELECT organization_id,shop_id,COUNT(*) total
                  FROM finance_records FORCE INDEX(idx_finance_company_dashboard)
                  WHERE kind='BILL' OR (kind='SETTLEMENT' AND source='API')
                  GROUP BY organization_id,shop_id) r
                  ON r.organization_id=c.organization_id AND r.shop_id=c.id
                WHERE c.status<>'REMOVED';""",
            capture_output=True, timeout=30, check=True)
        self.shops = [json.loads(line) for line in result.stdout.decode().splitlines() if line]

    def redis(self, *arguments, payload=None):
        command = ["docker", "exec", "-i", "-e", "REDISCLI_AUTH",
                   "ai-commerce-redis", "redis-cli", "--no-auth-warning", "--raw"]
        if payload is not None:
            command.append("-x")
        return subprocess.run(command + list(arguments),
                              input=payload, capture_output=True, check=True, timeout=8,
                              env={**os.environ, "REDISCLI_AUTH": self.password})

    def publish(self, status):
        groups = {}
        for shop in self.shops:
            path = self.root / "backfill" / (shop["shopId"] + ".json")
            progress = json.loads(path.read_text()) if path.exists() else {}
            groups.setdefault(shop["organization"], []).append({
                "shopId": shop["shopId"], "total": shop["total"],
                "records": progress.get("records", 0), "bills": progress.get("bills", 0),
                "complete": progress.get("complete", False) and status != "FAILED"})
        for organization, shops in groups.items():
            payload = json.dumps({"status": status, "heartbeat": int(time.time() * 1000), "shops": shops}).encode()
            # -x appends the payload as the last argument, so use SETEX.
            retention = "120" if status == "RUNNING" else "604800" if status == "FAILED" else "86400"
            self.redis("SETEX", "heyi:finance:calculation:{" + organization + "}:settlements",
                       retention, payload=payload)
        return groups

    def invalidate_snapshots(self):
        for organization in {shop["organization"] for shop in self.shops}:
            owner = str(uuid.UUID(bytes=hashlib.md5(("finance:" + organization).encode()).digest(), version=3))
            self.redis("INCR", "heyi:finance:snapshot:v6-settled-goods:{" + organization + ":" + owner + "}:revision")
