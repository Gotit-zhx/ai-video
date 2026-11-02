"""Repair only the private installer service; keep the retired recruitment collector stopped."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import pwd
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request

ROOT=Path(sys.argv[1]).resolve()
INSTALLER_HOME=Path("/opt/heyi/desktop-installers")
UNIT=Path("/etc/systemd/system/heyi-desktop-downloads.service")
os.umask(0o077)

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def object_checks():
    # This script runs using the new, isolated virtual environment.
    import oss2
    spec=importlib.util.spec_from_file_location("installer_candidate",ROOT/"desktop_installers.py")
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
    artifacts=module.read_manifest(INSTALLER_HOME/"current.json")
    values=dict(line.split("=",1) for line in Path("/opt/heyi/secrets/dayan-oss.properties").read_text().splitlines()
                if "=" in line and not line.lstrip().startswith("#"))
    values={k.strip():v.strip() for k,v in values.items()}
    bucket=oss2.Bucket(oss2.Auth(values["accessKeyId"],values["accessKeySecret"]),module.ENDPOINT,
                       "test-hoyee",is_path_style=True)
    bucket.session.session.trust_env=False
    probe=module.Downloads(INSTALLER_HOME/"current.json",bucket,authenticate=lambda _:200)
    opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
    results={}
    for route,platform in module.ROUTES.items():
        record=artifacts[platform]
        try:
            status,headers=probe.response(route,"isolated-check","HEAD")
            assert status==302
            with opener.open(urllib.request.Request(headers["Location"],method="HEAD"),timeout=20) as response:
                assert response.status==200 and int(response.headers["Content-Length"])==record["size"]
            _,headers=probe.response(route,"isolated-check","GET")
            part="bytes=0-4095" if platform=="windows" else "bytes=-512"
            with opener.open(urllib.request.Request(headers["Location"],headers={"Range":part}),timeout=20) as response:
                assert response.status==206,"Storage must support download resume"
                value=response.read(4096)
                assert value.startswith(b"MZ" if platform=="windows" else b"koly"),"Unexpected installer format"
            results[platform]={"size":record["size"],"head":200,"range":206,"formatVerified":True}
        except urllib.error.HTTPError as error:
            raise RuntimeError(platform+" object validation HTTP "+str(error.code)) from None
        except urllib.error.URLError:
            raise RuntimeError(platform+" object validation connection failed") from None
    return results

def run(*args):
    return subprocess.check_output(args,stderr=subprocess.STDOUT).decode().strip()

def main():
    assert not (ROOT/"activation.json").exists()
    assert (INSTALLER_HOME/"venv/bin/python").exists()
    manifest_hash=digest(INSTALLER_HOME/"current.json")
    previous=UNIT.read_bytes()
    (ROOT/"previous.service").write_bytes(previous)
    connector=run("systemctl","show","heyi-boss-connector","-p","ActiveState","--value")
    results=object_checks()
    group=pwd.getpwnam("heyi-boss").pw_gid
    folder=INSTALLER_HOME/"service";folder.mkdir(mode=0o750,exist_ok=True);os.chown(folder,0,group)
    folder.chmod(0o750)
    target=folder/"desktop_installers.py"
    shutil.copy2(ROOT/"desktop_installers.py",target);os.chown(target,0,group);target.chmod(0o640)
    run("runuser","-u","heyi-boss","--",str(INSTALLER_HOME/"venv/bin/python"),"-c",
        "import runpy,oss2; m=runpy.run_path('/opt/heyi/desktop-installers/service/desktop_installers.py'); "
        "m['read_manifest']('/opt/heyi/desktop-installers/current.json')")
    shutil.copy2(ROOT/"heyi-desktop-downloads.service",UNIT);UNIT.chmod(0o644)
    try:
        run("systemctl","daemon-reload")
        run("systemctl","restart","heyi-desktop-downloads")
        for attempt in range(30):
            if run("systemctl","show","heyi-desktop-downloads","-p","ActiveState","--value")=="active":
                time.sleep(.2)
                try:
                    status=run("curl","--noproxy","*","-sS","-o","/dev/null","-w","%{http_code}",
                               "--max-time","5","http://172.18.0.1:8767/downloads/Heyi-Recruiter-mac-arm64.dmg")
                except subprocess.CalledProcessError:
                    status=""
                if status=="401":break
            time.sleep(.2)
        else:
            raise RuntimeError("Independent download service not ready")
        for name in ("Heyi-Recruiter-mac-arm64.dmg","Heyi-Recruiter-windows-x64-Setup.exe"):
            for cookie in ("","SESSION=invalid-download-check"):
                status=run("curl","--noproxy","*","--resolve","hoyee.org.cn:443:127.0.0.1",
                    "-sS","-o","/dev/null","-w","%{http_code}","--max-time","8","-H","Cookie: "+cookie,
                    "https://hoyee.org.cn/downloads/"+name)
                assert status=="401","Missing/invalid sessions must remain unauthorized"
        assert digest(INSTALLER_HOME/"current.json")==manifest_hash
        assert run("systemctl","show","heyi-boss-connector","-p","ActiveState","--value")==connector
    except BaseException:
        UNIT.write_bytes(previous);UNIT.chmod(0o644)
        run("systemctl","daemon-reload");run("systemctl","restart","heyi-desktop-downloads")
        raise
    report={"installers":results,"service":"active","runtimeIndependent":True,"manifestUnchanged":True,
            "collectorStatePreserved":connector,"anonymousAndInvalidSessionsRejected":True,"persistentLogging":False}
    (ROOT/"activation.json").write_text(json.dumps(report,indent=2))
    print(json.dumps(report))

if __name__=="__main__":
    main()
