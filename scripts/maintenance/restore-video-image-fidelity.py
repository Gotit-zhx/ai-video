"""Restore only the unverified fidelity omission; preserve live configuration."""
import hashlib
import json
from pathlib import Path
import subprocess
import time


def run(*args):
    return subprocess.check_output(args, stderr=subprocess.STDOUT)


def inspect(name):
    return json.loads(run("docker", "inspect", name))[0]


def main():
    name = "heyi-pi-runtime"
    root = Path("/opt/heyi/releases/video-fidelity-restore-20261015")
    root.mkdir(mode=0o700, exist_ok=True)
    assert not (root / "activation.json").exists(), "Already restored"
    before = inspect(name)
    live = run("docker", "exec", name, "cat", "/app/src/server.js")
    source = (root / "server.js").read_bytes()
    old = b'''if (inputFidelity && !/^gpt-image-2(?:-\\d{4}-\\d{2}-\\d{2})?$/.test(config.modelId)) {
        form.append("input_fidelity", inputFidelity);
      }'''
    new = b'if (inputFidelity) form.append("input_fidelity", inputFidelity);'
    assert live.count(old) == 1 and live.replace(old, new) == source, "Unexpected live source drift"
    pending = run("docker", "exec", name, "node", "-e", """
const fs=require('fs');let pending=0;
for(const file of fs.readdirSync('/data/image-operations')){
if(!file.endsWith('.json'))continue;
const r=JSON.parse(fs.readFileSync('/data/image-operations/'+file));
if(r.status==='RUNNING')pending++;
}
console.log(pending);
""")
    assert int(pending) == 0, "Image operation still running"
    preserved = {c["Name"]: c["Id"] for c in json.loads(run(
        "docker", "inspect", *run("docker", "ps", "-q").decode().split())) if c["Id"] != before["Id"]}
    (root / "rollback-server.js").write_bytes(live)
    (root / "Dockerfile").write_text(
        "FROM " + before["Image"] + "\nCOPY server.js /app/src/server.js\n")
    image = "heyi-pi-runtime:video-fidelity-restored-20261015"
    run("docker", "build", "-t", image, str(root))
    run("docker", "run", "--rm", "--network", "none", "--entrypoint", "node", image, "--check", "/app/src/server.js")
    labels = before["Config"]["Labels"]
    service = labels["com.docker.compose.service"]
    command = ["docker", "compose", "--project-directory", labels["com.docker.compose.project.working_dir"],
               "-p", labels["com.docker.compose.project"]]
    for file in labels["com.docker.compose.project.config_files"].split(","):
        command += ["-f", file]

    def activate(target):
        override = root / "compose.json"
        override.write_text(json.dumps({"services": {service: {"image": target}}}))
        run(*command, "-f", str(override), "up", "-d", "--no-deps", "--no-build", "--pull", "never", service)

    try:
        assert inspect(name)["Id"] == before["Id"]
        activate(image)
        for attempt in range(60):
            try:
                run("docker", "exec", name, "node", "-e",
                    "fetch('http://127.0.0.1:8090/health').then(r=>{if(!r.ok)process.exit(1)}).catch(()=>process.exit(1))")
                break
            except subprocess.CalledProcessError:
                time.sleep(1)
        else:
            raise RuntimeError("Runtime health failed")
        after = inspect(name)
        assert sorted(after["Config"]["Env"]) == sorted(before["Config"]["Env"])
        mounts = lambda c: sorted((m["Source"], m["Destination"], m["RW"]) for m in c["Mounts"])
        assert mounts(after) == mounts(before)
        assert run("docker", "exec", name, "cat", "/app/src/server.js") == source
        assert all(inspect(n)["Id"] == identifier for n, identifier in preserved.items())
    except BaseException:
        activate(before["Config"]["Image"])
        raise
    result = {"image": image, "rollbackImage": before["Config"]["Image"],
              "serverSha256": hashlib.sha256(source).hexdigest(),
              "onlyChange": "Restore caller-specified input_fidelity", "paidCalls": 0,
              "configurationPreserved": True, "unrelatedContainersPreserved": True}
    (root / "activation.json").write_text(json.dumps(result, indent=2))
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
