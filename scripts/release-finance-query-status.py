"""Frontend-only financial query status release; keep the running backend unchanged."""
import gzip
import importlib.util
import os
from pathlib import Path
import sys

os.umask(0o077)
spec=importlib.util.spec_from_file_location("storage","/opt/heyi/releases/finance-storage-recovery-20261102/release-finance-storage-recovery.py")
storage=importlib.util.module_from_spec(spec);spec.loader.exec_module(storage)
core,ROOT,APP,FRONT=storage.core,storage.ROOT,storage.APP,storage.FRONT
IMAGE="ai-commerce-frontend:finance-query-status-20261102"
FILES={"index.html","finance-workspace.js","finance-loading.css"}

def baseline():
    for name in FILES:
        assert core.run("docker","exec",FRONT,"cat","/usr/share/nginx/html/"+name)==(ROOT/"baseline-frontend"/name).read_bytes()
    return {name:core.inspect(name) for name in (APP,FRONT)}

def build():
    before=baseline();frontend=ROOT/"frontend";hashes={}
    assert {p.name for p in frontend.iterdir()}==FILES
    for name in FILES:
        value=(frontend/name).read_bytes();hashes[name]=core.sha(value)
        (frontend/(name+".gz")).write_bytes(gzip.compress(value,mtime=0))
        (frontend/name).chmod(0o644);(frontend/(name+".gz")).chmod(0o644)
    (frontend/"Dockerfile").write_text("FROM "+before[FRONT]["Config"]["Image"]+"\n"+
        "\n".join("COPY "+name+" "+name+".gz /usr/share/nginx/html/" for name in sorted(FILES))+
        "\nRUN rm -f "+" ".join("/usr/share/nginx/html/"+name+".br" for name in sorted(FILES))+"\n")
    core.run("docker","build","--pull=false","-t",IMAGE,str(frontend))
    core.write_json(ROOT/"prepared.json",{"files":hashes,"appId":before[APP]["Id"],"appImage":before[APP]["Image"]})
    print("Three frontend assets built; backend unchanged.")

def verify():
    prepared=core.read_json("prepared.json")
    assert core.inspect(FRONT)["Config"]["Image"]==IMAGE
    app=core.inspect(APP)
    assert app["Id"]==prepared["appId"] and app["Image"]==prepared["appImage"]
    assert app["State"]["Running"]
    for name,digest in prepared["files"].items():
        assert core.sha(core.run("docker","exec",FRONT,"cat","/usr/share/nginx/html/"+name))==digest
        for encoding in ("identity","gzip"):
            value=core.run("curl","--noproxy","*","-fsS","--retry","3","--retry-all-errors","--max-time","10",
                "--resolve","hoyee.org.cn:443:127.0.0.1","-H","Accept-Encoding: "+encoding,"https://hoyee.org.cn/"+name)
            if encoding=="gzip" and value.startswith(b"\x1f\x8b"):value=gzip.decompress(value)
            assert core.sha(value)==digest
    for name in (APP,FRONT,"ai-commerce-mysql","ai-commerce-redis"):
        assert core.inspect(name)["HostConfig"]["LogConfig"]["Type"]=="none"
    print("Live assets and gzip verified; backend container unchanged; logging remains disabled.")

def deploy():
    assert core.read_json("qa-passed.json")["scopeCurrency"] is True
    before=baseline();rollback=IMAGE+"-before"
    core.run("docker","tag",before[FRONT]["Image"],rollback)
    try:
        storage.activate(before,FRONT,IMAGE);verify()
        assert core.configuration(core.inspect(FRONT))==core.configuration(before[FRONT])
    except BaseException:
        storage.activate(before,FRONT,rollback);raise
    core.write_json(ROOT/"activation.json",{"frontendImage":IMAGE,"rollbackImage":rollback,
        "backendUnchanged":True,"configurationPreserved":True,"persistentLogs":False})

if __name__=="__main__":
    {"build":build,"deploy":deploy,"verify":verify}[sys.argv[2]]()
