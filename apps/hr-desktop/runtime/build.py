"""Build on the target OS. Never package a developer's auth/profile files."""
import hashlib
from pathlib import Path
import shutil
import subprocess
import sys
from build_manifest import MANIFEST, source_snapshot, write_manifest

HERE = Path(__file__).resolve().parent
SOURCE = HERE.parents[2] / "services" / "recruitment"
STAGE = HERE / "build" / "shared"
STAGE.mkdir(parents=True, exist_ok=True)
sources = source_snapshot()

def replace_once(text, before, after):
    assert text.count(before) == 1, "Shared policy changed; review desktop adaptation"
    return text.replace(before, after, 1)

connector = (SOURCE / "connector.py").read_text(encoding="utf-8")
connector = replace_once(connector, "import grp", "try:\n    import grp\nexcept ImportError:\n    grp = None")
connector = replace_once(connector, "class Server(socketserver.ThreadingUnixStreamServer):",
                         "class Server(getattr(socketserver, 'ThreadingUnixStreamServer', socketserver.ThreadingTCPServer)):")
guard = (SOURCE / "guarded_cli.py").read_text(encoding="utf-8")
guard = replace_once(guard, "import fcntl", "import portable_lock as fcntl")
guard = replace_once(guard, "parsed.port != 9222",
                     "parsed.port != urllib.parse.urlsplit(CDP_URL).port")
# The embedded BOSS browser exclusively uses Electron's default context.
# The platform website is in a different persistent context; never select it
# or export its cookies to the CLI.
guard = replace_once(guard, 'if results[0].get("browserContextIds"):',
                     'if results[0].get("browserContextIds") and not os.environ.get("HEYI_EMBEDDED_DEFAULT_CONTEXT"):')
for name, text in (("connector.py", connector), ("guarded_cli.py", guard)):
    (STAGE / name).write_text(text, encoding="utf-8")
if "--stage-only" in sys.argv:
    sys.exit(0)

# A failed rebuild must not leave a previous manifest claiming the output is current.
(HERE / "dist/heyi-boss" / MANIFEST).unlink(missing_ok=True)
subprocess.run([
    sys.executable, "-m", "PyInstaller", "--noconfirm", "--clean", "--onedir",
    "--name", "heyi-boss", "--paths", str(STAGE),
    "--collect-all", "boss_agent_cli", "--collect-all", "patchright",
    "--hidden-import", "websockets.sync.client",
    "--copy-metadata", "boss-agent-cli", "--hidden-import", "connector",
    "--hidden-import", "guarded_cli", "--hidden-import", "portable_lock",
    "--distpath", str(HERE / "dist"), "--workpath", str(HERE / "build" / "pyinstaller"),
    "--specpath", str(HERE), str(HERE / "runner.py")
], check=True)
write_manifest(HERE / "dist/heyi-boss", sources)
