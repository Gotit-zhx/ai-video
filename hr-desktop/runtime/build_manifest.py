"""Reject stale or changed workers before packaging either desktop client."""
import hashlib
import json
from pathlib import Path
import platform
import sys

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = "heyi-runtime-build.json"


def sha(file):
    digest = hashlib.sha256()
    with file.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def source_snapshot(repository=ROOT):
    repository = Path(repository).resolve()
    runtime = repository / "hr-desktop/runtime"
    files = set(runtime.glob("*.py"))
    files = {file for file in files if not file.name.startswith("test_")}
    files.update(repository / relative for relative in [
        "hr-desktop/runtime/constraints.txt",
        "hr-desktop/vendor/boss_agent_cli-2.0.0-py3-none-any.whl",
        "scripts/boss-agent/connector.py",
        "scripts/boss-agent/guarded_cli.py",
    ])
    if not (runtime / "runner.py").is_file():
        raise ValueError("Runtime source tree is incomplete")
    return {file.relative_to(repository).as_posix(): sha(file) for file in sorted(files)}


def file_snapshot(directory):
    directory = Path(directory).resolve()
    files = {}
    for file in sorted(directory.rglob("*")):
        relative = file.relative_to(directory).as_posix()
        if relative == MANIFEST:
            continue
        if file.is_symlink():
            try:
                file.resolve(strict=True).relative_to(directory)
            except (ValueError, OSError) as error:
                raise ValueError("Worker contains an external or broken symlink") from error
            files[relative] = {"link": file.readlink().as_posix()}
        elif file.is_file():
            files[relative] = {"sha256": sha(file), "size": file.stat().st_size}
    if not files:
        raise ValueError("Built worker is empty")
    return files


def write_manifest(directory, sources, repository=ROOT):
    directory = Path(directory)
    if sources != source_snapshot(repository):
        raise ValueError("Runtime sources changed during the build; rebuild")
    value = {
        "schemaVersion": 1,
        "platform": sys.platform,
        "architecture": platform.machine(),
        "python": platform.python_version(),
        "sources": sources,
        "files": file_snapshot(directory),
    }
    target = directory / MANIFEST
    with target.open("x", encoding="utf-8") as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2)
    return value


def verify_runtime(directory, expected_platform, repository=ROOT):
    directory = Path(directory)
    if (directory / MANIFEST).is_symlink():
        raise ValueError("Worker build manifest must be a regular file")
    try:
        value = json.loads((directory / MANIFEST).read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        raise ValueError("Worker has no valid build manifest; rebuild on the target OS") from error
    if not isinstance(value, dict) or value.get("schemaVersion") != 1:
        raise ValueError("Unsupported worker build manifest; rebuild")
    if value.get("platform") != expected_platform:
        raise ValueError("Worker was built for a different operating system")
    if value.get("sources") != source_snapshot(repository):
        raise ValueError("Worker sources are stale; rebuild before packaging")
    if value.get("files") != file_snapshot(directory):
        raise ValueError("Built worker files changed; rebuild before packaging")
    return value
