"""Upload only desktop installers, verifying private and anonymous downloads.

Run on production with its existing read-only Dayan credential mount. The
manifest is resumable; files and bucket-wide ACLs are never deleted or changed.
"""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import re
import signal
import subprocess
import urllib.parse
import urllib.request

import oss2


def digest(path):
    sha, md5 = hashlib.sha256(), hashlib.md5()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            sha.update(chunk)
            md5.update(chunk)
    return sha.hexdigest(), md5


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", action="append", required=True)
    parser.add_argument("--manifest", required=True)
    parser.add_argument("--proof", action="append", required=True, help="Verified packaging manifest")
    args = parser.parse_args()
    manifest = Path(args.manifest)
    manifest.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    records = json.loads(manifest.read_text()) if manifest.exists() else {}
    proofs = {}
    for source in args.proof:
        value = json.loads(Path(source).read_text())
        for name, artifact in value["artifacts"].items():
            if name in proofs and proofs[name] != artifact:
                raise ValueError("Conflicting packaging proofs")
            proofs[name] = artifact
    def stop(signum, frame):
        raise RuntimeError("Installer upload interrupted")
    signal.signal(signal.SIGTERM, stop)
    info = json.loads(subprocess.check_output(["docker", "inspect", "ai-commerce-app"]))[0]
    env = dict(value.split("=", 1) for value in info["Config"]["Env"])
    raw = subprocess.check_output(["docker", "exec", "ai-commerce-app", "cat",
                                   env["APP_DAYAN_OSS_CREDENTIALS_FILE"]]).decode()
    credentials = dict(line.split("=", 1) for line in raw.splitlines()
                       if "=" in line and not line.lstrip().startswith("#"))
    credentials = {key.strip(): value.strip() for key, value in credentials.items()}
    bucket = oss2.Bucket(oss2.Auth(credentials["accessKeyId"], credentials["accessKeySecret"]),
                         "https://oss.cloud.dayan.cloud", env["APP_DAYAN_OSS_BUCKET"],
                         is_path_style=True, connect_timeout=60)
    bucket.session.session.trust_env = False
    for source in args.source:
        path = Path(source)
        if path.is_symlink() or not path.is_file() or not re.fullmatch(
                r"Heyi-Recruiter-(?:[\d.]+-)?(?:mac-arm64\.dmg|windows-x64-Setup\.exe)", path.name):
            raise ValueError("Expected a regular Mac or Windows recruiter installer")
        before = path.stat()
        sha, md5 = digest(path)
        proof = proofs.get(path.name)
        if not proof or proof["size"] != before.st_size or proof["sha256"] != sha:
            raise ValueError("Installer does not match its verified packaging proof")
        key = "desktop-installers/" + sha + "/" + path.name
        print(path.name + ": checking object", flush=True)
        try:
            head = bucket.head_object(key)
            exists = head.content_length == before.st_size
        except oss2.exceptions.NoSuchKey:
            exists = False
        except oss2.exceptions.NotFound:
            exists = False
        if not exists:
            upload = bucket.init_multipart_upload(key, headers={
                "Content-Type": "application/octet-stream",
                "Content-Disposition": 'attachment; filename="' + path.name + '"',
                "Cache-Control": "public, max-age=31536000, immutable",
                # Only these public installers receive anonymous read access.
                "x-oss-object-acl": "public-read",
            }).upload_id
            parts = []
            try:
                with path.open("rb") as stream:
                    for number, chunk in enumerate(iter(lambda: stream.read(8 * 1024 * 1024), b""), 1):
                        part = bucket.upload_part(key, upload, number, chunk, headers={
                            "Content-MD5": base64.b64encode(hashlib.md5(chunk).digest()).decode()})
                        assert part.etag.lower().strip('"') == hashlib.md5(chunk).hexdigest()
                        parts.append(oss2.models.PartInfo(number, part.etag))
                        print(path.name + ": part " + str(number) + " uploaded and MD5 verified", flush=True)
                bucket.complete_multipart_upload(key, upload, parts)
            except Exception:
                bucket.abort_multipart_upload(key, upload)
                raise
        head = bucket.head_object(key)
        assert head.content_length == before.st_size
        if not exists:
            etag = hashlib.md5(b"".join(bytes.fromhex(part.etag.strip('"')) for part in parts)).hexdigest() + "-" + str(len(parts))
            assert head.etag.lower().strip('"') == etag
        print(path.name + ": downloading anonymous copy for full SHA-256 verification", flush=True)
        signed = urllib.parse.urlsplit(bucket.sign_url("GET", key, 60))
        public = urllib.parse.urlunsplit((signed.scheme, signed.netloc, signed.path, "", ""))
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        downloaded, count = hashlib.sha256(), 0
        with opener.open(public, timeout=90) as stream:
            assert stream.headers.get_content_type() == "application/octet-stream"
            assert "attachment" in stream.headers.get("Content-Disposition", "")
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                downloaded.update(chunk)
                count += len(chunk)
        assert count == before.st_size and downloaded.hexdigest() == sha
        assert path.stat().st_size == before.st_size and path.stat().st_mtime_ns == before.st_mtime_ns
        records[str(path.resolve())] = {"sha256": sha, "size": count, "key": key,
                                       "url": public, "verified": True}
        temporary = manifest.with_suffix(".tmp")
        temporary.write_text(json.dumps(records, indent=2))
        temporary.chmod(0o600)
        temporary.replace(manifest)
        print(path.name + ": uploaded and anonymous SHA-256 verified", flush=True)


if __name__ == "__main__":
    main()
