"""Stage the HR pi SDK/progress patch against the exact current production files."""
import base64
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys

workspace = Path(__file__).resolve().parents[2]
stage = Path(sys.argv[1]).resolve()
stage.mkdir(mode=0o700)
ssh = ["ssh", "-i", str(Path.home() / ".ssh/id_ed25519_ai_video"),
       "-o", "IdentitiesOnly=yes", "-o", "BatchMode=yes", "-o", "ConnectTimeout=10",
       "-o", "ProxyCommand=none", "root@8.163.34.81"]
frontend = ["index.html", "hr-workspace.js", "hr-workspace.css", "hr-passive.js", "hr-sourcing.js", "hr-demands.js"]
connector = ["connector.py", "passive_pool.py", "hr_sourcing.py", "recruitment_demands.py", "resume_storage.py"]
remote = """
import base64,hashlib,json,pathlib,subprocess,tempfile,zipfile
files={}
for name in FRONTEND:
    files['frontend/'+name]=subprocess.check_output(['docker','exec','ai-commerce-frontend','cat','/usr/share/nginx/html/'+name])
for name in CONNECTOR:
    files['connector/'+name]=pathlib.Path('/opt/heyi/boss-agent/bin',name).read_bytes()
files['pi/server.js']=subprocess.check_output(['docker','exec','heyi-pi-runtime','cat','/app/src/server.js'])
with tempfile.TemporaryDirectory(prefix='hr-pi-baseline-') as folder:
    jar=pathlib.Path(folder,'app.jar')
    subprocess.check_output(['docker','cp','ai-commerce-app:/app/app.jar',str(jar)],stderr=subprocess.STDOUT)
    expected={'app.jar':hashlib.sha256(jar.read_bytes()).hexdigest()}
    with zipfile.ZipFile(jar) as archive:
        expected['RecruitmentAiController.class']=hashlib.sha256(archive.read('BOOT-INF/classes/com/yingdao/aicommerce/recruitment/RecruitmentAiController.class')).hexdigest()
for name,raw in files.items():
    expected['pi-server.js' if name=='pi/server.js' else name.split('/')[1]]=hashlib.sha256(raw).hexdigest()
print(json.dumps({'expected':expected,'files':{name:base64.b64encode(raw).decode() for name,raw in files.items()}}))
""".replace("FRONTEND", repr(frontend)).replace("CONNECTOR", repr(connector))
baseline = json.loads(subprocess.check_output([*ssh, "python3 -"], input=remote.encode()))
changes = {"frontend/index.html", "frontend/hr-workspace.css", "frontend/hr-demands.js",
           "connector/hr_sourcing.py", "connector/recruitment_demands.py", "pi/server.js"}
manifest = {}
for name, encoded in baseline["files"].items():
    live = base64.b64decode(encoded)
    local = workspace / name.replace("connector/", "scripts/boss-agent/").replace("pi/", "pi-runtime/src/")
    if name in changes:
        relative = str(local.relative_to(workspace))
        head = subprocess.check_output(["git", "show", "HEAD:" + relative], cwd=workspace)
        assert head == live, "Production differs from HEAD; review before staging: " + name
        data = local.read_bytes()
    else:
        data = live
    target = stage / "staged" / name
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(data)
    manifest[name] = hashlib.sha256(data).hexdigest()
for name, source in {
    "pi/recruitment-analysis.js": workspace / "pi-runtime/src/recruitment-analysis.js",
    "java/RecruitmentAiController.class": workspace / "target/classes/com/yingdao/aicommerce/recruitment/RecruitmentAiController.class",
}.items():
    target = stage / "staged" / name
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, target)
    manifest[name] = hashlib.sha256(target.read_bytes()).hexdigest()
(stage / "expected-baseline.json").write_text(json.dumps(baseline["expected"], indent=2))
(stage / "manifest.json").write_text(json.dumps(manifest, indent=2))
(stage / "code-commit").write_text(subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=workspace).decode())
for name in ("release-sourcing.py", "release-recruitment-ai.py"):
    shutil.copy2(workspace / "scripts/boss-agent" / name, stage / name)
print(json.dumps({"stage": str(stage), "files": len(manifest), "baselineReviewed": True}))
