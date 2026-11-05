"""Queue historical GIFT orders without changing source records or synchronization cursors."""
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path("/opt/heyi/releases/finance-gift-zero-20261105")
IMAGE = "ai-commerce-platform:finance-gift-zero-20261105"


def start(resume=False):
    receipt = ROOT / "gift-repair-verification.json"
    if resume:
        saved = json.loads(receipt.read_text())
        assert saved.get("stopped") and saved.get("verificationVersion") == 2 and saved.get("cursorId")
    else:
        assert not receipt.exists(), "Inspect the previous receipt before resuming"
    app, db = [json.loads(subprocess.check_output(["docker", "inspect", name]))[0]
               for name in ("ai-commerce-app", "ai-commerce-mysql")]
    assert app["State"]["Running"] and app["Config"]["Image"] == IMAGE
    network = next(iter(set(app["NetworkSettings"]["Networks"]) & set(db["NetworkSettings"]["Networks"])))
    settings = dict(v.split("=", 1) for v in app["Config"]["Env"])
    os.umask(0o077)
    private = ROOT / "repair-private.env"
    try:
        private.write_text("".join(k + "=" + settings[k] + "\n" for k in ("DB_URL", "DB_USERNAME", "DB_PASSWORD")))
        result = subprocess.run([
            "docker", "run", "-d", "--rm", "--user", "0:0", "--log-driver", "none",
            "--name", "finance-gift-cost-repair", "--network", network,
            "--memory", "512m", "--cpus", "0.5", "--env-file", str(private),
            "-v", str(ROOT) + ":/qa", "--entrypoint", "java", IMAGE, "-Xmx320m",
            "-Dloader.path=/qa/operations", "-Dloader.main=com.yingdao.aicommerce.finance.FinanceGiftCostRepair",
            "-cp", "/app/app.jar", "org.springframework.boot.loader.launch.PropertiesLauncher",
            "/qa/gift-repair-verification.json"
        ], capture_output=True, text=True, timeout=20)
        if result.returncode:
            raise RuntimeError(result.stderr[-1000:])
        print("Historical GIFT scan started; source data and synchronization cursors remain intact.")
    finally:
        private.unlink(missing_ok=True)


if __name__ == "__main__":
    assert len(sys.argv) == 1 or sys.argv[1:] == ["resume"]
    start(len(sys.argv) == 2)
