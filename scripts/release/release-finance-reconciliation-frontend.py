"""Publish the independent, paginated reconciliation view with exact live-file guards."""
import importlib.util
from pathlib import Path
import subprocess
import sys

root = Path(sys.argv[1]).resolve()
spec = importlib.util.spec_from_file_location("static_release", "/opt/heyi/releases/incremental-refresh-20261021/release-incremental-frontend.py")
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)
release.IMAGE = "ai-commerce-frontend:finance-reconciliation-20261021"
try:
    {"build": release.build, "deploy": release.deploy, "verify": release.verify}[sys.argv[2]]()
except subprocess.CalledProcessError as error:
    (root / "failure.private.log").write_bytes(error.output or b"")
    (root / "failure.private.log").chmod(0o600)
    raise SystemExit("Reconciliation view release failed; private diagnostics retained.")
