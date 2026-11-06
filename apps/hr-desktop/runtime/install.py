"""Install the reviewed SDK wheel and constrained build dependencies."""
import hashlib
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
wheel = ROOT / "vendor/boss_agent_cli-2.0.0-py3-none-any.whl"
expected = "2c1099e3c29a5d26c4fbe4c5f0d92a1315cc49cd7f87de406889fe4c43aa7665"
if hashlib.sha256(wheel.read_bytes()).hexdigest() != expected:
    raise SystemExit("Reviewed SDK wheel checksum mismatch")
subprocess.run([sys.executable, "-m", "pip", "install", "--timeout", "120",
                "-c", str(ROOT / "runtime/constraints.txt"), str(wheel),
                "pyinstaller==6.19.0"], check=True)
