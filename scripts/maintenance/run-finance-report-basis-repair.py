"""Run the reviewed bounded date repair with no persistent logs or credential files."""
import json
import os
from pathlib import Path
import subprocess
import sys
import uuid

ROOT = Path("/opt/heyi/releases/finance-report-basis-20261105")
NAME = "finance-report-basis-repair"
IMAGES = {"ai-commerce-platform:finance-report-basis-20261105",
          "ai-commerce-platform:finance-report-basis-resume-20261105",
          "ai-commerce-platform:finance-gift-zero-20261105"}
ORG = "6a61f788-6685-4b64-82b1-eec97ed8cf90"


def inspect(name):
    return json.loads(subprocess.check_output(["docker", "inspect", name]))[0]


def cache_revision(settings):
    # Java UUID.nameUUIDFromBytes uses raw MD5 with UUID-v3 version bits.
    import hashlib
    user = str(uuid.UUID(bytes=hashlib.md5(("finance:" + ORG).encode()).digest(), version=3))
    prefix = f"heyi:finance:snapshot:v15-shared-facts:{{{ORG}:{user}}}:"
    versions = {}
    for suffix in ("revision", "data-revision"):
        result = subprocess.run(
            ["docker", "exec", "-i", "ai-commerce-redis", "sh", "-c",
             'export REDISCLI_AUTH="$(cat)"; exec redis-cli --raw INCR "$1"',
             "sh", prefix + suffix],
            input=settings.get("REDIS_PASSWORD", "ai_commerce_dev"),
            text=True, capture_output=True, timeout=12)
        if result.returncode or not result.stdout.strip().isdigit():
            raise RuntimeError("Scoped cache invalidation failed")
        versions[suffix] = int(result.stdout.strip())
    return versions


def start():
    assert not (ROOT / "date-repair-verification.json").exists(), "Inspect the previous receipt before any resume"
    assert os.statvfs(ROOT).f_bavail * os.statvfs(ROOT).f_frsize > 4 * 1024**3
    app, db = inspect("ai-commerce-app"), inspect("ai-commerce-mysql")
    assert app["Config"]["Image"] in IMAGES and app["State"]["Running"]
    settings = dict(v.split("=", 1) for v in app["Config"]["Env"])
    network = next(iter(set(app["NetworkSettings"]["Networks"]) & set(db["NetworkSettings"]["Networks"])))
    os.umask(0o077)
    private = ROOT / "repair-private.env"
    try:
        private.write_text("".join(k + "=" + settings[k] + "\n" for k in ("DB_URL", "DB_USERNAME", "DB_PASSWORD")))
        cache_revision(settings)
        result = subprocess.run(
            ["docker", "run", "-d", "--rm", "--user", "0:0", "--log-driver", "none",
             "--name", NAME, "--network", network, "--memory", "768m", "--cpus", "1",
             "--env-file", str(private), "-v", str(ROOT) + ":/qa",
             "--entrypoint", "java", app["Config"]["Image"], "-Xmx512m",
             "-Dloader.path=/qa/operations", "-Dloader.main=com.yingdao.aicommerce.finance.FinanceReportBasisRepair",
             "-cp", "/app/app.jar", "org.springframework.boot.loader.launch.PropertiesLauncher",
             "/qa/date-repair-verification.json"],
            text=True, capture_output=True, timeout=20)
        if result.returncode:
            raise RuntimeError(result.stderr[-1000:])
        print("Bounded date repair started; private environment file removed.")
    finally:
        private.unlink(missing_ok=True)


def finalize():
    receipt = json.loads((ROOT / "date-repair-verification.json").read_text())
    assert receipt["complete"] and not receipt.get("stopped")
    assert receipt["sourceFieldsAndAmountsUnchanged"] and not receipt["syncCheckpointsWritten"]
    settings = dict(v.split("=", 1) for v in inspect("ai-commerce-app")["Config"]["Env"])
    receipt["cacheRevisionAfter"] = cache_revision(settings)
    target = ROOT / "date-repair-verification.json"
    temporary = target.with_suffix(".tmp")
    temporary.write_text(json.dumps(receipt))
    temporary.replace(target)
    print(json.dumps(receipt))


if __name__ == "__main__":
    {"start": start, "finalize": finalize}[sys.argv[1]]()
