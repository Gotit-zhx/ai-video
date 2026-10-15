"""Verify one completed isolated image through private OSS and signed readback."""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import subprocess
import urllib.request


def command(*args):
    result = subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode:
        raise RuntimeError("Storage verification command failed")
    return result.stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--case", required=True)
    args = parser.parse_args()
    assert args.case.replace("-", "").isalnum()
    root = Path("/opt/heyi/releases/video-pipeline-20261015/live-proof") / args.case
    proof = json.loads((root / "summary.json").read_text())
    assert proof["status"] == "COMPLETED" and proof["posts"] == 1
    source = root / Path(proof["file"]).name
    data = source.read_bytes()
    sha = hashlib.sha256(data).hexdigest()
    assert sha == proof["sha256"] and len(data) == proof["bytes"]
    assert proof["mimeType"] == "image/png"
    assert data[:8] == b"\x89PNG\r\n\x1a\n" and data[12:16] == b"IHDR"
    dimensions = struct.unpack(">II", data[16:24])
    assert proof["size"] == str(dimensions[0]) + "x" + str(dimensions[1]), "Actual image dimensions differ"
    info = json.loads(command("docker", "inspect", "ai-commerce-app"))[0]
    env = dict(value.split("=", 1) for value in info["Config"]["Env"])
    raw = command("docker", "exec", "ai-commerce-app", "cat", env["APP_DAYAN_OSS_CREDENTIALS_FILE"]).decode()
    credentials = {}
    for line in raw.splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            key, value = line.split("=", 1)
            credentials[key.strip()] = value.strip()
    import oss2
    bucket = oss2.Bucket(oss2.Auth(credentials["accessKeyId"], credentials["accessKeySecret"]),
                         "https://oss.cloud.dayan.cloud", env["APP_DAYAN_OSS_BUCKET"],
                         connect_timeout=15, is_path_style=True)
    bucket.session.session.trust_env = False
    key = "heyi/diagnostics/video-pipeline-20261015/" + args.case + "/" + sha + source.suffix
    bucket.put_object(key, data, headers={"Content-Type": proof["mimeType"]})
    obj = bucket.get_object(key)
    try:
        actual = obj.read(len(data) + 1)
    finally:
        obj.close()
    assert hashlib.sha256(actual).hexdigest() == sha
    url = bucket.sign_url("GET", key, 120)
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open(url, timeout=30) as response:
        signed_data = response.read(len(data) + 1)
        assert response.status == 200
    assert hashlib.sha256(signed_data).hexdigest() == sha
    result = {"case": args.case, "status": "VERIFIED", "dimensions": dimensions,
              "bytes": len(data), "sha256": sha, "bucket": bucket.bucket_name, "key": key,
              "privateReadMatches": True, "signedReadMatches": True, "paidSubmissions": 0}
    target = root / "storage.json"
    target.write_text(json.dumps(result, indent=2))
    target.chmod(0o600)
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(json.dumps({"status": "FAILED", "failureType": type(error).__name__}))
        raise SystemExit(1)
