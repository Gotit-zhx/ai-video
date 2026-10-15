"""Stage a separate Windows workbench without modifying vendor resources.

Run on Windows with its own built worker. Outputs must stay outside Git.
The derived application is a local test build, never a public source artifact.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

from build_mac import own_files, verify_runtime

HERE = Path(__file__).resolve().parent
DESKTOP = HERE.parent
PIN = json.loads((HERE / "windows-original.json").read_text(encoding="utf-8"))


def sha(file):
    digest = hashlib.sha256()
    with file.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def outside_git(directory):
    return not any((parent / ".git").exists() for parent in (directory, *directory.parents))


def stage(source, output, runtime, sdk):
    source, output, runtime = source.resolve(), output.absolute(), runtime.resolve()
    if sys.platform != "win32":
        raise ValueError("Build the Windows workbench on Windows")
    if output.exists() or not outside_git(output) or output == source or source in output.parents:
        raise ValueError("Choose a fresh output outside Git and the original application")
    for relative, expected in PIN["files"].items():
        if sha(source / relative) != expected:
            raise ValueError("Original Windows input differs")
    if not (runtime / "heyi-boss.exe").is_file() or (runtime / "heyi-boss.exe").read_bytes()[:2] != b"MZ":
        raise ValueError("Build the Windows worker first; do not copy a Mac runtime")
    verify_runtime(runtime, "win32")
    addon = own_files(sdk)
    for name in ["windows-workbench.cjs", "windows-launcher.cjs", "windows-package.cjs",
                 "windows-original.json", "windows-adapter.cjs", "native-session.cjs",
                 "native-bindings.cjs", "native-messages.cjs", "native-request-broker.cjs",
                 "inspector-driver.cjs", "process-outcome.cjs", "original-sdk-exit.cjs"]:
        addon["heyi/" + name] = (HERE / name).read_bytes()
    ws = (HERE / "node_modules/ws").resolve(strict=True)
    for file in ws.rglob("*"):
        if file.is_file() and not file.is_symlink():
            addon["heyi/node_modules/ws/" + file.relative_to(ws).as_posix()] = file.read_bytes()
    output.mkdir(parents=True)
    native = output / "native-client"
    shutil.copytree(source, native, symlinks=False)
    for relative, data in addon.items():
        target = native / "resources" / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
    shutil.copytree(runtime, native / "resources/heyi-runtime")
    verify_runtime(native / "resources/heyi-runtime", "win32")
    with tempfile.TemporaryDirectory(prefix="heyi-windows-entry-") as temporary:
        subprocess.run([sys.executable, "-m", "PyInstaller", "--noconfirm", "--clean",
                        "--onefile", "--windowed", "--name", "赫逸工作台",
                        "--distpath", str(output), "--workpath", str(Path(temporary) / "build"),
                        "--specpath", temporary, str(HERE / "windows_entry.py")],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    manifest = {"schemaVersion": 1, "experimental": True, "version": "0.1.24",
                "originalResourcesModified": False, "realAccountTested": False,
                "files": {file.relative_to(output).as_posix(): sha(file)
                          for file in sorted(output.rglob("*")) if file.is_file()}}
    for relative, expected in PIN["files"].items():
        if manifest["files"].get("native-client/" + relative) != expected:
            raise ValueError("Original resources changed while packaging")
    (output / "heyi-package.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2),
                                             encoding="utf-8")
    return {"ok": True, "output": str(output), "files": len(manifest["files"]),
            "originalResourcesModified": False, "realAccountTested": False}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--source-dir", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--runtime", type=Path, default=DESKTOP / "runtime/dist/heyi-boss")
    parser.add_argument("--sdk-endpoints", type=Path)
    options = parser.parse_args()
    sdk = options.sdk_endpoints or options.runtime / "_internal/boss_agent_cli/api/recruiter.yaml"
    print(json.dumps(stage(options.source_dir, options.output_dir, options.runtime, sdk),
                     ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
