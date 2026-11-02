"""Publish the finance default-all currency fix over the exact live frontend."""
import importlib.util
import os
import subprocess
import sys

spec = importlib.util.spec_from_file_location(
    "finance_default_all_base",
    "/opt/heyi/releases/finance-scope-currency-20261029/release-finance-scope-currency.py")
previous = importlib.util.module_from_spec(spec)
spec.loader.exec_module(previous)
previous.IMAGE = "ai-commerce-frontend:finance-default-all-20261029"
base_run = previous.core.run
base_verify = previous.verify


def run(*args, **kwargs):
    if args[:2] == ("docker", "run") and "--log-driver" not in args:
        args = args[:2] + ("--log-driver", "none") + args[2:]
    return base_run(*args, **kwargs)


def build():
    for name in previous.FILES:
        (previous.ROOT / "frontend" / name).chmod(0o644)
    mask = os.umask(0o022)
    try:
        previous.build()
    finally:
        os.umask(mask)


def verify():
    run("curl", "--noproxy", "*", "-fsS", "--retry", "10", "--retry-all-errors",
        "--retry-delay", "1", "--max-time", "3", "--resolve",
        "hoyee.org.cn:443:127.0.0.1", "https://hoyee.org.cn/index.html")
    base_verify()


previous.core.run = run
previous.verify = verify

if __name__ == "__main__":
    try:
        {"snapshot": previous.snapshot, "build": build,
         "deploy": previous.deploy, "verify": verify}[sys.argv[2]]()
    except subprocess.CalledProcessError:
        raise SystemExit("Finance default-all release failed; activation rolls back automatically.")
