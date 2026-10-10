"""Exercise an existing candidate image in a disposable locked-down, network-none container."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import time


def run(command, **kwargs):
    result = subprocess.run(command, capture_output=True, **kwargs)
    if result.returncode:
        raise RuntimeError("Container smoke command failed: " + result.stderr.decode(errors="replace")[:500])
    return result.stdout


def main(args):
    destination = Path(args.output_dir)
    destination.mkdir(parents=True, exist_ok=True)
    environment = dict(os.environ, DOCUMENT_PREVIEW_TOKEN=secrets.token_urlsafe(48))
    container = None
    try:
        command = ["docker", "run", "-d", "--rm", "--read-only", "--network", "none",
                   "--tmpfs", "/tmp:rw,nosuid,nodev,noexec,size=512m,mode=1777", "--memory", "1g", "--memory-swap", "1g",
                   "--cpus", "1", "--pids-limit", "128", "--cap-drop", "ALL", "--security-opt", "no-new-privileges:true",
                   "--user", "10001:10001", "-e", "DOCUMENT_PREVIEW_TOKEN"]
        if args.platform:
            command += ["--platform", args.platform]
        command.append(args.image)
        container = run(command, env=environment, timeout=30).decode().strip()
        health = "import urllib.request,json; assert json.load(urllib.request.urlopen('http://127.0.0.1:8092/health',timeout=1)) == {'status':'UP'}"
        for _ in range(40):
            ready = subprocess.run(["docker", "exec", container, "python", "-c", health], capture_output=True, timeout=5)
            if ready.returncode == 0:
                break
            time.sleep(0.25)
        else:
            raise RuntimeError("Candidate did not become healthy")
        policy = "import uno,json; print(json.dumps({'macroNeverExecute':uno.getConstantByName('com.sun.star.document.MacroExecMode.NEVER_EXECUTE'),'linkNoUpdate':uno.getConstantByName('com.sun.star.document.UpdateDocMode.NO_UPDATE')}))"
        constants = json.loads(run(["docker", "exec", container, "/usr/bin/python3", "-c", policy], timeout=10))
        info = json.loads(run(["docker", "inspect", container], timeout=10))[0]
        safe_configuration = {"user": info["Config"]["User"], "readonlyRootfs": info["HostConfig"]["ReadonlyRootfs"],
            "memory": info["HostConfig"]["Memory"], "pidsLimit": info["HostConfig"]["PidsLimit"], "capDrop": info["HostConfig"]["CapDrop"],
            "securityOpt": info["HostConfig"]["SecurityOpt"], "networks": sorted(info["NetworkSettings"]["Networks"]),
            "ports": info["HostConfig"]["PortBindings"], "tmpfs": info["HostConfig"]["Tmpfs"]}
        report = {"image": info["Image"], "disposable": True, "productionWrites": False,
                  "configuration": safe_configuration, "unoConstants": constants, "conversions": []}
        for name in args.fixture:
            source = Path(name)
            extension = source.suffix.removeprefix(".")
            if extension not in ("ppt", "pptx"):
                raise RuntimeError("Only real PPT/PPTX fixtures are accepted")
            client = """import os,sys,urllib.request,urllib.error
data=sys.stdin.buffer.read()
request=urllib.request.Request('http://127.0.0.1:8092/convert?format=' + sys.argv[1],data=data,headers={'Authorization':'Bearer '+os.environ['DOCUMENT_PREVIEW_TOKEN'],'Content-Type':'application/octet-stream'})
try:
    with urllib.request.urlopen(request,timeout=100) as response:
        assert response.status==200 and response.headers.get_content_type()=='application/pdf'
        sys.stdout.buffer.write(response.read(50*1024*1024+1))
except urllib.error.HTTPError as error:
    print('Converter HTTP status '+str(error.code)+': '+error.read(200).decode(errors='replace'),file=sys.stderr)
    sys.exit(1)
"""
            started = time.monotonic()
            pdf = run(["docker", "exec", "-i", container, "python", "-c", client, extension], input=source.read_bytes(), timeout=105)
            if not pdf.startswith(b"%PDF-") or len(pdf) > 50 * 1024 * 1024:
                raise RuntimeError("Invalid PDF result")
            pages = len(re.findall(rb"/Type\s*/Page(?!s)\b", pdf))
            if pages != args.expected_pages:
                raise RuntimeError("Unexpected generated PDF page count: " + str(pages))
            output = destination / (source.stem + "-from-" + extension + ".pdf")
            output.write_bytes(pdf)
            clean = run(["docker", "exec", container, "python", "-c",
                         "import pathlib; assert not list(pathlib.Path('/tmp').glob('document-preview-*')); print('clean')"], timeout=10).decode().strip()
            report["conversions"].append({"format": extension, "inputSha256": hashlib.sha256(source.read_bytes()).hexdigest(),
                "outputSha256": hashlib.sha256(pdf).hexdigest(), "outputBytes": len(pdf), "pages": pages,
                "elapsedSeconds": round(time.monotonic()-started, 2), "temporaryFilesCleaned": clean == "clean"})
        (destination / "converter-smoke-verification.json").write_text(json.dumps(report, indent=2) + "\n")
        print(json.dumps(report))
    finally:
        if container:
            run(["docker", "rm", "-f", "-v", container], timeout=30)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", required=True)
    parser.add_argument("--fixture", action="append", required=True)
    parser.add_argument("--output-dir", required=True)
    parser.add_argument("--platform")
    parser.add_argument("--expected-pages", type=int, default=2)
    main(parser.parse_args())
