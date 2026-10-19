"""Upload verified Ai boss artifacts privately; emit no credentials or signed URL."""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import re
import urllib.error
import urllib.parse
import urllib.request


def checksum(path):
    sha = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            sha.update(chunk)
    return sha.hexdigest()


def verified_artifact(source, proof, platform):
    value = json.loads(proof.read_text())
    commit = value.get("commit", "")
    if not re.fullmatch(r"[a-f0-9]{40}", commit) or value.get("packagedChecksPassed") is not True:
        raise ValueError("Exact source and completed packaged checks required")
    if platform == "windows":
        record = value.get("installer", {})
        if (value.get("overallProbePassed") is not True
                or not record.get("checks") or not all(v is True for v in record["checks"].values())):
            raise ValueError("Complete Windows installation verification required")
        name = f"Ai-boss-Windows-Setup-{commit[:12]}.exe"
        size, sha = record.get("bytes"), record.get("sha256")
        if record.get("name") != name:
            raise ValueError("Unexpected Windows artifact")
    else:
        if any(value.get(k) is not True for k in
               ("imageVerified", "mountedAppSignatureVerified", "mountedPayloadMatchesSmokeApp")):
            raise ValueError("Verified mounted Mac image required")
        name = f"Ai-boss-mac-arm64-{commit[:12]}.dmg"
        size, sha = value.get("bytes"), value.get("sha256")
        if value.get("installer") != name:
            raise ValueError("Unexpected Mac artifact")
    if (source.is_symlink() or not source.is_file() or source.name != name
            or source.stat().st_size != size or checksum(source) != sha):
        raise ValueError("Installer differs from verified artifact")
    return commit, {"name": name, "size": size, "sha256": sha,
                    "key": f"private-desktop-installers/{sha}/{name}"}


def upload(bucket, source, record):
    import oss2
    key = record["key"]
    try:
        head = bucket.head_object(key)
        exists = head.content_length == record["size"]
    except (oss2.exceptions.NoSuchKey, oss2.exceptions.NotFound):
        exists = False
    if not exists:
        upload_id = bucket.init_multipart_upload(key, headers={
            "Content-Type": "application/octet-stream",
            "Content-Disposition": 'attachment; filename="' + record["name"] + '"',
            "Cache-Control": "private, no-store",
            "x-oss-object-acl": "private",
        }).upload_id
        parts = []
        try:
            with source.open("rb") as stream:
                for number, chunk in enumerate(iter(lambda: stream.read(8 * 1024 * 1024), b""), 1):
                    md5 = hashlib.md5(chunk).digest()
                    part = bucket.upload_part(key, upload_id, number, chunk,
                                              headers={"Content-MD5": base64.b64encode(md5).decode()})
                    if part.etag.lower().strip('"') != md5.hex():
                        raise ValueError("Uploaded part checksum differs")
                    parts.append(oss2.models.PartInfo(number, part.etag))
                    print(f"{record['name']}: verified part {number}", flush=True)
            bucket.complete_multipart_upload(key, upload_id, parts)
        except BaseException:
            bucket.abort_multipart_upload(key, upload_id)
            raise
    return verify_object(bucket, record)


def verify_object(bucket, record):
    key = record["key"]
    if bucket.head_object(key).content_length != record["size"]:
        raise ValueError("Object size differs")
    # Dayan does not implement GetObjectACL. Verify access itself: anonymous
    # GET and HEAD must fail, and the same signed download used by the site
    # must return the complete verified installer, including on resumed runs.
    signed = bucket.sign_url("GET", key, 1800, params={
        "response-content-disposition": 'attachment; filename="' + record["name"] + '"',
        "response-cache-control": "private, no-store",
    })
    parsed = urllib.parse.urlsplit(signed)
    unsigned = urllib.parse.urlunsplit((parsed.scheme, parsed.netloc, parsed.path, "", ""))
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    for method in ("HEAD", "GET"):
        try:
            with opener.open(urllib.request.Request(unsigned, method=method), timeout=30):
                raise ValueError("Anonymous installer access must be denied")
        except urllib.error.HTTPError as error:
            status = error.code
            error.close()
            if status not in (401, 403, 404):
                raise ValueError("Unexpected anonymous storage status") from None
    sha, size = hashlib.sha256(), 0
    with opener.open(signed, timeout=60) as result:
        if (result.status != 200 or result.headers.get_filename() != record["name"]
                or int(result.headers.get("Content-Length", -1)) != record["size"]):
            raise ValueError("Signed installer response differs")
        for chunk in iter(lambda: result.read(1024 * 1024), b""):
            sha.update(chunk)
            size += len(chunk)
    if sha.hexdigest() != record["sha256"] or size != record["size"]:
        raise ValueError("Stored installer checksum differs")
    return {**record, "private": True, "verified": True}


def main():
    import oss2
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--proof", type=Path, required=True)
    parser.add_argument("--platform", choices=("mac", "windows"), required=True)
    parser.add_argument("--credentials", type=Path, required=True)
    parser.add_argument("--bucket", required=True)
    parser.add_argument("--output", type=Path, required=True)
    options = parser.parse_args()
    commit, record = verified_artifact(options.source, options.proof, options.platform)
    values = dict(line.split("=", 1) for line in options.credentials.read_text().splitlines()
                  if "=" in line and not line.lstrip().startswith("#"))
    values = {key.strip(): value.strip() for key, value in values.items()}
    bucket = oss2.Bucket(oss2.Auth(values["accessKeyId"], values["accessKeySecret"]),
                         "https://oss.cloud.dayan.cloud", options.bucket,
                         is_path_style=True, connect_timeout=60)
    bucket.session.session.trust_env = False
    record = upload(bucket, options.source, record)
    if checksum(options.source) != record["sha256"]:
        raise ValueError("Source changed while uploading")
    report = {"schemaVersion": 1, "commit": commit, "platform": options.platform, "artifact": record}
    temporary = options.output.with_suffix(".tmp")
    temporary.write_text(json.dumps(report, indent=2))
    temporary.chmod(0o600)
    temporary.replace(options.output)
    print(json.dumps({"platform": options.platform, "size": record["size"], "sha256": record["sha256"],
                      "private": True, "verified": True}), flush=True)


if __name__ == "__main__":
    main()
