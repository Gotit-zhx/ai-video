#!/usr/bin/env python3
"""Deduplicate sanitized operational incidents and close confirmed recoveries."""
import datetime
import json
import os
import re
import subprocess
import urllib.request
from remote import Remote

REPO="Gotit-zhx/ai-video"
LABEL="heyi-ops-event"


def api(path,method="GET",body=None):
    result=subprocess.run(["gh","api","--method",method,"repos/"+REPO+"/"+path,*(["--input","-"] if body is not None else [])],
            input=json.dumps(body).encode() if body is not None else None,capture_output=True,check=True)
    return json.loads(result.stdout) if result.stdout.strip() else None


def reconcile(alerts,verified_scopes):
    valid=re.compile(r"[a-zA-Z0-9_-]{1,80}\Z")
    for a in alerts:
        if not valid.fullmatch(a["service"]) or not valid.fullmatch(a["type"]) or a["severity"] not in {"warning","critical"}:
            raise ValueError("Invalid alert identity")
    try:api("labels/"+LABEL)
    except subprocess.CalledProcessError:api("labels","POST",{"name":LABEL,"color":"b60205","description":"Managed operational incidents; sanitized summaries only"})
    issues=api("issues?state=open&labels="+LABEL+"&per_page=100")
    current={"<!-- heyi-ops:"+a["service"]+":"+a["type"]+" -->":a for a in alerts}
    indexed={i.get("body","").split("\n")[0]:i for i in issues if not i.get("pull_request")}
    now=datetime.datetime.now(datetime.timezone.utc).isoformat()
    for key,alert in current.items():
        title="[运维] "+alert["service"]+" / "+alert["type"]+" ("+alert["severity"]+")"
        if key in indexed:
            if indexed[key]["title"]!=title:api("issues/"+str(indexed[key]["number"]),"PATCH",{"title":title})
            continue
        body=key+"\n\n检测时间："+now+"。\n\n故障：`"+alert["service"]+" / "+alert["type"]+"`，等级：`"+alert["severity"]+"`。\n\n请运行 Operations → doctor，或在主机执行 `heyi-ops doctor`。完整业务数据和凭据不进入通知。\n"
        api("issues","POST",{"title":title,"body":body,"labels":[LABEL],"assignees":["Gotit-zhx"]})
    for key,issue in indexed.items():
        scope=key.removeprefix("<!-- heyi-ops:").split(":",1)[0]
        if key not in current and key.startswith("<!-- heyi-ops:") and scope in verified_scopes:
            api("issues/"+str(issue["number"])+"/comments","POST",{"body":"探测已确认恢复（"+now+"）。"})
            api("issues/"+str(issue["number"]),"PATCH",{"state":"closed","state_reason":"completed"})


def main():
    alerts=[];complete=False;verified={"external"}
    try:
        with urllib.request.urlopen("https://hoyee.org.cn/actuator/health",timeout=15) as response:
            if response.status!=200 or json.load(response).get("status")!="UP":raise RuntimeError("Not healthy")
    except Exception:alerts.append({"service":"external","type":"https-availability","severity":"critical"})
    try:
        with Remote() as remote:status=remote.call("doctor",timeout=60)
        alerts.extend(status["alerts"]);complete=True;verified.update(status["verifiedScopes"]);verified.add("monitoring")
    except Exception:alerts.append({"service":"monitoring","type":"host-probe-failed","severity":"critical"})
    reconcile(alerts,verified)
    print(json.dumps({"alerts":alerts,"hostProbeComplete":complete},ensure_ascii=False))


if __name__=="__main__":main()
