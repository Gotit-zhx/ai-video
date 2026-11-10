#!/usr/bin/env python3
"""Package public files deterministically; validate local and dynamic references."""
import argparse
import gzip
import hashlib
import json
import re
import shutil
from pathlib import Path
from urllib.parse import unquote, urlsplit

FRONT = Path(__file__).resolve().parent
ROOT = FRONT.parent
DIRECTORIES = {"assets", "vendor", "downloads", "i18n"}
EXTENSIONS = {".html", ".js", ".css", ".svg", ".ico", ".png", ".webmanifest"}


def files():
    result = {}
    for path in sorted(FRONT.rglob("*")):
        rel = path.relative_to(FRONT)
        if not path.is_file() or path.is_symlink() or path.suffix==".md":
            continue
        if (len(rel.parts) == 1 and path.suffix in EXTENSIONS) or rel.parts[0] in DIRECTORIES:
            dest = str(rel)
            if dest.startswith("downloads/"): dest = "plugin-downloads/" + dest[len("downloads/"):]
            result[dest] = path
    return result


def validate(public):
    missing = set()
    for name, path in public.items():
        if path.suffix not in (".html", ".css", ".js") or name.startswith("vendor/"):
            continue
        text = path.read_text()
        # HTML/CSS references plus literal JS asset loads (including dynamic loaders).
        refs = re.findall(r'''(?:src|href)=["']([^"']+)["']|url\(["']?([^\s)'";]+)|["'](/(?:assets|vendor|i18n|plugin-downloads)/[^"'`$<>]+|/[A-Za-z0-9_.-]+\.(?:js|css)(?:\?[^"']*)?)["']''', text)
        for values in refs:
            value = next(v for v in values if v)
            parsed = urlsplit(value)
            if parsed.scheme or parsed.netloc or value.startswith(("#", "${")) or not parsed.path:
                continue
            if "${" in value or "<" in value or "{" in value:
                continue
            target = unquote(parsed.path)
            target = target.lstrip("/") if target.startswith("/") else str(Path(name).parent / target)
            if target in {"pdf.js", "pdf.worker.js"} and name == "hr-resume-preview.js":
                continue  # CDN basename detection, not a local asset URL.
            if not Path(target).suffix or Path(target).suffix not in {".js", ".css", ".html", ".svg", ".png", ".jpg", ".webp", ".json", ".xlsx", ".ico"}:
                continue
            if target not in public:
                missing.add((name, target))
    if missing:
        raise ValueError("Missing static references: " + json.dumps(sorted(missing), ensure_ascii=False))


def build(output, layered=False):
    public = files(); validate(public)
    if output.exists():
        raise ValueError("Output must be a new directory")
    output.mkdir(parents=True)
    manifest = {}
    for name, path in public.items():
        data = path.read_bytes(); layer = ("stable" if name.split("/")[0] in {"vendor","assets","plugin-downloads","i18n"} else "frequent")
        target = output / layer / name if layered else output / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data); target.chmod(0o644)
        item = {"sha256": hashlib.sha256(data).hexdigest(), "size": len(data)}
        if path.suffix in (".html", ".js", ".css", ".svg", ".json"):
            compressed = gzip.compress(data, mtime=0)
            target.with_name(target.name + ".gz").write_bytes(compressed)
            item["gzipSha256"] = hashlib.sha256(compressed).hexdigest()
        manifest[name] = item
    ((output / "frequent" if layered else output) / "asset-manifest.json").write_text(json.dumps(manifest, sort_keys=True, indent=2) + "\n")
    return manifest


if __name__ == "__main__":
    p = argparse.ArgumentParser(); p.add_argument("output", type=Path, nargs="?")
    p.add_argument("--layered", action="store_true")
    a = p.parse_args()
    if a.output: print(json.dumps({"files": len(build(a.output, a.layered))}))
    else: validate(files()); print("Static and literal dynamic references passed")
