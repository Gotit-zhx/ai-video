"""Code-page-independent JSON for the frozen Python -> Electron pipe."""
import json
import sys


def write_result(result, stream=None):
    # PyInstaller may ignore PYTHONIOENCODING/PYTHONUTF8. ASCII escapes preserve
    # every Unicode value and bypass the Windows stdout text codec entirely.
    output = sys.stdout if stream is None else stream
    output.buffer.write(json.dumps(result, ensure_ascii=True).encode("ascii"))
    output.buffer.flush()
