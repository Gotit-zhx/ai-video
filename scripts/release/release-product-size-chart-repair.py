"""Deploy the selected TikTok size chart and safe bulk repair UI over exact live assets."""
import importlib.util
import subprocess
import sys

spec = importlib.util.spec_from_file_location(
    "size_chart_base",
    "/opt/heyi/releases/finance-cost-currency-20261029/release-finance-cost-currency.py")
previous = importlib.util.module_from_spec(spec)
spec.loader.exec_module(previous)
release = previous.release
release.TAG = "product-size-chart-repair-20261029"
release.BASE_SCHEMA = "135"
release.MIGRATION = None
release.PREFIX = "BOOT-INF/classes/com/yingdao/aicommerce/product/application/provider/"
release.FAMILIES = {"TikTokProductSizeChart", "TikTokShopOfficialPublishingProvider"}
release.FILES = {"index.html", "app.js", "product-bulk-size-chart.js", "product-bulk-upload.js"}


base_verify = release.verify


def build():
    # Archives are private at rest, but nginx's unprivileged worker must read public assets.
    for name in release.FILES:
        (release.ROOT / "frontend" / name).chmod(0o644)
    release.build()


def deployment_verify():
    # Also verifies identity/gzip assets, health, schema and finance access.
    # The frontend image is nginx; older release helpers assumed a Node server.
    release.core.run("curl", "--noproxy", "*", "-fsS", "--retry", "10",
                     "--retry-all-errors", "--retry-delay", "1", "--max-time", "3",
                     "--resolve", "hoyee.org.cn:443:127.0.0.1", "https://hoyee.org.cn/")
    base_verify()
    for path in ("product-listing-templates/size-chart-image",
                 "product-captures/unknown/publish-workflows"):
        code = release.core.run(
            "curl", "--noproxy", "*", "-sS", "-o", "/dev/null", "-w", "%{http_code}",
            "--max-time", "10", "--resolve", "hoyee.org.cn:443:127.0.0.1",
            "https://hoyee.org.cn/api/v1/" + path).decode()
        assert code == "401", "Unauthenticated product access must remain denied"


def deploy():
    release.deploy()
    result = release.core.read_json("activation.json")
    result["schemaChanges"] = False
    result["changedClasses"] = sorted(release.FAMILIES)
    result["workflowReplay"] = False
    release.core.write_json(release.ROOT / "activation.json", result)


release.verify = deployment_verify
release.base.deploy = deploy

if __name__ == "__main__":
    try:
        {"snapshot": previous.snapshot, "build": build,
         "verify": deployment_verify, "drain-deploy": release.base.drain_deploy}[sys.argv[2]]()
    except subprocess.CalledProcessError:
        raise SystemExit("Size chart release failed; activation rolls back automatically.")
