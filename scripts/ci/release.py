#!/usr/bin/env python3
"""Deterministic release metadata and fail-closed Gitflow/artifact checks."""
import argparse
import hashlib
import json
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
REPOSITORY = "Gotit-zhx/ai-video"
VERSION = re.compile(r"v[0-9]+(?:\.[0-9]+){2,3}\Z")
DIGEST = re.compile(r"sha256:[a-f0-9]{64}\Z")


def run(*args):
    return subprocess.check_output(args, cwd=ROOT).decode().strip()


def sha(data):
    return hashlib.sha256(data).hexdigest()


def canonical(data):
    return json.dumps(data, sort_keys=True, separators=(",", ":")).encode()


def component(path):
    if path.startswith('src/main/'):
        return 'app'
    if path.startswith(('services/','apps/','config/')) and not ('/docs/' in path or Path(path).name in {'README.md','AGENTS.md'}):
        return 'unsupported'
    if path.endswith(".md") or path.startswith(("docs/","frontend/test/","frontend/tests/","ops/heyi-ops/tests/")):
        return "source"
    if path.startswith(("ops/heyi-ops/", "ops/finance-ad-proxy/")) or path in {"scripts/heyi-mysql-backup.py","scripts/ci/release.py"} or path.startswith("ops/admin-console/heyi-mysql-backup."):
        return "operations"
    if path.startswith(("src/main/",)) or path in ("pom.xml", "Dockerfile", "maven-settings.xml", ".dockerignore", "scripts/ci/VerifyDependencies.java", "scripts/ci/dependencies.sha256"):
        return "app"
    if path.startswith("frontend/") and not path.startswith(("frontend/test/", "frontend/tests/")):
        return "frontend"
    if path.startswith(("services/", "apps/", "config/")) or path.startswith("compose"):
        return "unsupported"
    if path.startswith(("docs/", "scripts/", "ops/", ".github/", "src/test/", "experiments/", ".circleci/")) or path.endswith(".md") or path in (".gitignore", ".gitattributes"):
        return "source"
    return "unsupported"


def documentation_only(paths):
    return all(component(p)=='source' and (p.startswith('docs/') or p.endswith('.md')) for p in paths)


def inventory(ref="HEAD"):
    entries = run("git", "ls-tree", "-r", ref).splitlines()
    groups = {k: {} for k in ("app", "frontend", "unsupported", "source", "operations", "migrations")}
    for entry in entries:
        mode, kind, rest = entry.split(" ", 2)
        oid, path = rest.split("\t", 1)
        if kind != "blob" or mode == "120000":
            raise ValueError("Submodules and symlinks require explicit packaging review")
        groups[component(path)][path] = {"mode": mode, "blob": oid}
        if path.startswith(("src/main/resources/db/migration/", "src/main/java/db/migration/")):
            groups["migrations"][path] = sha(subprocess.check_output(["git", "show", f"{ref}:{path}"], cwd=ROOT))
    return {"commit": run("git", "rev-parse", f"{ref}^{{commit}}"),
            "tree": run("git", "rev-parse", f"{ref}^{{tree}}"),
            "inputs": {k: sha(canonical(v)) for k, v in groups.items()},
            "migrations": groups["migrations"]}


def changed(base, ref="HEAD"):
    return sorted({component(p) for p in run("git", "diff", "--name-only", base, ref).splitlines()})


def validate_manifest(value, require_smoke=True):
    if value.get("schemaVersion") != 1 or value.get("repository") != REPOSITORY or not VERSION.fullmatch(value.get("version", "")):
        raise ValueError("Invalid manifest identity")
    source = value["source"]
    for key in ("commit", "tree"):
        if not re.fullmatch(r"[a-f0-9]{40}", source[key]):
            raise ValueError("Invalid Git identity")
    if set(value["images"]) != {"app", "frontend"}:
        raise ValueError("Exactly app and frontend images required")
    if value["validation"].get("result") != "passed" or value["validation"].get("unexpectedSkips") != 0:
        raise ValueError("Candidate did not pass mandatory validation")
    if require_smoke and value["validation"].get("containerSmoke") != "passed":
        raise ValueError("Candidate container smoke did not pass")
    if set(source["inputs"]) != {"app","frontend","unsupported","source","operations","migrations"} or not all(re.fullmatch(r"[a-f0-9]{64}",v) for v in source["inputs"].values()):
        raise ValueError("Invalid component fingerprints")
    if not re.fullmatch(r"refs/heads/(release|hotfix)/[A-Za-z0-9._-]+",source["ref"]):
        raise ValueError("Untrusted source ref")
    if not re.fullmatch(r"[a-f0-9]{64}", value["sourceArchiveSha256"]):
        raise ValueError("Invalid source checksum")
    if not all(type(value[k]) is int and 0<value[k]<=limit for k,limit in [("sourceArchiveBytes",512*1024**2),("sourceUnpackedBytes",1024**3)]):
        raise ValueError("Invalid source archive budget")
    for name, info in value["images"].items():
        expected = f"ghcr.io/{REPOSITORY.lower()}/{name}"
        if info["repository"] != expected or not all(DIGEST.fullmatch(info[k]) for k in ("manifestDigest", "configDigest")):
            raise ValueError("Unapproved image identity")
        if info.get("platform") != "linux/amd64" or not re.fullmatch(r"[a-f0-9]{40}", info["sourceCommit"]):
            raise ValueError("Invalid image source/platform")
        if not re.fullmatch(r"refs/heads/(release|hotfix)/[A-Za-z0-9._-]+",info["sourceRef"]):
            raise ValueError("Untrusted image source ref")
        if not all(type(info[k]) is int and 0<info[k]<4*1024**3 for k in ("compressedBytes","unpackedBytes")):
            raise ValueError("Invalid image space budget")
        if name=="frontend":
            if not re.fullmatch(r"[a-f0-9]{64}",info["assetManifestSha256"]) or not info["smokeAssets"]:
                raise ValueError("Missing public asset evidence")
            for path,digest in info["smokeAssets"].items():
                if not re.fullmatch(r"[a-zA-Z0-9_./-]{1,160}",path) or path.startswith("/") or ".." in path.split("/") or not re.fullmatch(r"[a-f0-9]{64}",digest):
                    raise ValueError("Invalid public asset path/digest")
    return value


def check_tag(tag, manifest, production="origin/main-tree"):
    validate_manifest(manifest)
    if tag != manifest["version"] or not VERSION.fullmatch(tag):
        raise ValueError("Unexpected formal tag")
    commit = run("git", "rev-parse", f"{tag}^{{commit}}")
    # Reject old queued deployments, tags off production and non-merge releases.
    if commit != run("git", "rev-parse", production):
        raise ValueError("Tag must identify the current production head")
    parents = run("git", "show", "-s", "--format=%P", commit).split()
    if len(parents) != 2 or parents[1] != manifest["source"]["commit"]:
        raise ValueError("Tag must identify a no-ff candidate merge")
    if run("git", "rev-parse", f"{tag}^{{tree}}") != manifest["source"]["tree"]:
        raise ValueError("Production and tested candidate trees differ")
    if inventory(tag)["inputs"] != manifest["source"]["inputs"]:
        raise ValueError("Input fingerprints differ")
    return commit


def check_pr(base, head):
    allowed = {"develop": ("feature/", "release/", "hotfix/"), "main-tree": ("release/", "hotfix/")}
    if base not in allowed or not head.startswith(allowed[base]):
        raise ValueError("PR branch mapping violates Gitflow")


def main():
    p = argparse.ArgumentParser()
    sub = p.add_subparsers(dest="action", required=True)
    q = sub.add_parser("inventory"); q.add_argument("--ref", default="HEAD")
    q = sub.add_parser("changes"); q.add_argument("base"); q.add_argument("--ref", default="HEAD")
    q = sub.add_parser("verify-tag"); q.add_argument("tag"); q.add_argument("manifest", type=Path); q.add_argument("--production", default="origin/main-tree")
    q = sub.add_parser("pr"); q.add_argument("base"); q.add_argument("head")
    a = p.parse_args()
    if a.action == "inventory": print(json.dumps(inventory(a.ref), sort_keys=True))
    elif a.action == "changes": print(json.dumps(changed(a.base, a.ref)))
    elif a.action == "verify-tag": print(check_tag(a.tag, json.loads(a.manifest.read_text()), a.production))
    elif a.action == "pr": check_pr(a.base, a.head)


if __name__ == "__main__":
    main()
