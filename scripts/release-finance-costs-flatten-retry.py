"""Rebuild this release from current rollback root files, preserving image and container configuration."""
from pathlib import Path
import importlib.util
import json
import gzip
import shutil
import sys

ROOT=Path(sys.argv[1]).resolve()
if len(sys.argv)<3:sys.argv.append("flatten")
spec=importlib.util.spec_from_file_location("cost_release",str(ROOT/"release-finance-order-costs.py"))
release=importlib.util.module_from_spec(spec);spec.loader.exec_module(release)
core=release.core
assert not (ROOT/"activation.json").exists()
before=core.read_json("before-private.json")
prepared=core.read_json("prepared.json")
manifest=core.read_json("manifest.json")
current={name:core.inspect(name) for name in before}
for name,info in before.items():
    assert current[name]["Image"]==info["Image"],"Another release changed the running image"
    assert core.configuration(current[name])==core.configuration(info),"Configuration changed"
assert core.sha(core.run("docker","exec",release.APP,"cat","/app/app.jar"))==core.read_json("snapshot.json")["jar"]
for name,digest in manifest["expectedFiles"].items():
    assert digest==core.sha(core.run("docker","exec",release.FRONT,"cat","/usr/share/nginx/html/"+name))

def quoted(value):
    return json.dumps(value,ensure_ascii=True).replace("$",r"\$")

images={}
for name in (release.APP,release.FRONT):
    folder=ROOT/("flat-app" if name==release.APP else "flat-frontend")
    folder.mkdir(exist_ok=True)
    original=json.loads(core.run("docker","image","inspect",current[name]["Image"]))[0]
    config=original["Config"]
    assert not any(config.get(k) for k in ("Healthcheck","OnBuild","Volumes","Shell")),"Unsupported image metadata"
    core.run("docker","export","--output",str(folder/"rootfs.tar"),name)
    lines=["FROM scratch","ADD rootfs.tar /"]
    if name==release.APP:
        shutil.copy2(ROOT/"app-image/app.jar",folder/"app.jar")
        lines.append("COPY app.jar /app/app.jar")
        image="ai-commerce-platform:"+release.TAG+"-flat"
    else:
        for filename in release.FILES:
            value=(ROOT/"frontend"/filename).read_bytes()
            (folder/filename).write_bytes(value)
            (folder/(filename+".gz")).write_bytes(gzip.compress(value,mtime=0))
            lines.extend(["COPY "+filename+" /usr/share/nginx/html/"+filename,
                          "COPY "+filename+".gz /usr/share/nginx/html/"+filename+".gz"])
        image="ai-commerce-frontend:"+release.TAG+"-flat"
    for env in config.get("Env",[]):
        key,value=env.split("=",1);assert "\n" not in key and " " not in key
        lines.append("ENV "+key+"="+quoted(value))
    for key,value in (config.get("Labels") or {}).items():
        if key=="org.opencontainers.image.description":value=value.replace("\n"," ")
        lines.append("LABEL "+quoted(key)+"="+quoted(value))
    for port in config.get("ExposedPorts",{}):
        lines.append("EXPOSE "+port)
    for key,instruction in (("WorkingDir","WORKDIR"),("User","USER"),("StopSignal","STOPSIGNAL")):
        if config.get(key):
            assert "\n" not in config[key] and " " not in config[key]
            lines.append(instruction+" "+config[key])
    for key,instruction in (("Entrypoint","ENTRYPOINT"),("Cmd","CMD")):
        if config.get(key) is not None:
            lines.append(instruction+" "+json.dumps(config[key]))
    (folder/"Dockerfile").write_text("\n".join(lines)+"\n")
    core.run("docker","build","-t",image,str(folder))
    rebuilt=json.loads(core.run("docker","image","inspect",image))[0]
    for key in set(config)|set(rebuilt["Config"]):
        expected=config.get(key)
        if key=="Labels" and expected:
            expected={k:v.replace("\n"," ") if k=="org.opencontainers.image.description" else v for k,v in expected.items()}
        assert (expected or None)==(rebuilt["Config"].get(key) or None),"Changed image metadata: "+key
    assert len(rebuilt["RootFS"]["Layers"])<15
    images[name]=image
    print(json.dumps({"container":name,"oldLayers":len(original["RootFS"]["Layers"]),"newLayers":len(rebuilt["RootFS"]["Layers"]),"newImageBytes":rebuilt["Size"]}),flush=True)

shutil.copy2(ROOT/"before-private.json",ROOT/"before-first-attempt-private.json")
shutil.copy2(ROOT/"prepared.json",ROOT/"prepared-first-attempt.json")
core.write_json(ROOT/"before-private.json",current)
core.write_json(ROOT/"prepared.json",{**prepared,"images":images,"flattened":True})
print("Prepared flattened retry; running services and stored data unchanged.")
