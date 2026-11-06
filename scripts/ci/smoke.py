#!/usr/bin/env python3
"""Run the exact candidate digests against disposable MySQL/Redis with no egress."""
import argparse
import hashlib
import http.cookiejar
import json
import os
import secrets
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path
import release


def smoke(file):
    manifest=json.loads(file.read_text());runtime=json.loads((release.ROOT/"scripts/ci/runtime.json").read_text())
    project="heyi-ci-smoke-"+secrets.token_hex(5)
    with tempfile.TemporaryDirectory(prefix="heyi-ci-smoke-") as temporary:
        root=Path(temporary);os.chmod(root,0o755)
        cert=root/"certs/live/hoyee.org.cn";cert.mkdir(parents=True)
        subprocess.run(["openssl","req","-x509","-newkey","rsa:2048","-nodes","-days","1","-subj","/CN=hoyee.org.cn",
                        "-keyout",str(cert/"privkey.pem"),"-out",str(cert/"fullchain.pem")],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        control=root/"control";control.mkdir();control.chmod(0o755)
        token=secrets.token_hex(24);(control/"status-token").write_text(token);(control/"status-token").chmod(0o644)
        password=secrets.token_hex(24)
        spec={"name":project,"networks":{"default":{"internal":True}},"services":{
            "mysql":{"image":runtime["mysqlImage"],"environment":{"MYSQL_ROOT_PASSWORD":password,"MYSQL_DATABASE":"heyi_ci","MYSQL_USER":"heyi_ci","MYSQL_PASSWORD":password},"tmpfs":["/var/lib/mysql:rw,size=3g"],"healthcheck":{"test":["CMD","mysqladmin","ping","-h","localhost"],"interval":"2s","timeout":"2s","retries":60}},
            "redis":{"image":runtime["redisImage"]},
            "app":{"image":manifest["images"]["app"]["repository"]+"@"+manifest["images"]["app"]["manifestDigest"],"platform":"linux/amd64",
                "ports":["127.0.0.1::8080"],"volumes":[str(control)+":/run/heyi-ops:ro"],
                "depends_on":{"mysql":{"condition":"service_healthy"},"redis":{"condition":"service_started"}},
                "environment":{"DB_URL":"jdbc:mysql://mysql:3306/heyi_ci?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=UTC","DB_USERNAME":"heyi_ci","DB_PASSWORD":password,
                  "REDIS_HOST":"redis","REDIS_PASSWORD":"","APP_ADMIN_PASSWORD":password,"APP_DISPATCH_ENABLED":"false","APP_OSS_ENABLED":"false",
                  "APP_SHOP_LIVE_PUBLISHING_ENABLED":"false","APP_SESSION_COOKIE_SECURE":"false","APP_MODEL_SECRET_KEY":secrets.token_hex(32),"APP_SHOP_CREDENTIAL_KEY":secrets.token_hex(32)}},
            "frontend":{"image":manifest["images"]["frontend"]["repository"]+"@"+manifest["images"]["frontend"]["manifestDigest"],"platform":"linux/amd64",
                "ports":["127.0.0.1::80"],"volumes":[str(root/"certs")+":/etc/letsencrypt:ro"],"depends_on":["app"]}}}
        for service in spec["services"].values():service["logging"]={"driver":"none"}
        compose=root/"compose.json";compose.write_text(json.dumps(spec));compose.chmod(0o600)
        def dc(*args):return subprocess.check_output(["docker","compose","-f",str(compose),*args],stderr=subprocess.STDOUT).decode().strip()
        def request(base,path,body=None,headers=None,opener=None):
            req=urllib.request.Request(base+path,data=json.dumps(body).encode() if body is not None else None,headers={"Content-Type":"application/json",**(headers or {})})
            try:
                with (opener or urllib.request.build_opener()).open(req,timeout=10) as response:return response.status,response.read(),dict(response.headers)
            except urllib.error.HTTPError as response:return response.code,response.read(),dict(response.headers)
        try:
            dc("up","-d","--wait","--wait-timeout","180")
            base="http://"+dc("port","app","8080");front="http://"+dc("port","frontend","80")
            for _ in range(120):
                try:
                    code,body,_=request(base,"/actuator/health")
                    if code==200 and json.loads(body)["status"]=="UP":break
                except Exception:pass
                time.sleep(2)
            else:raise RuntimeError("Clean full image failed startup/health")
            assert request(base,"/api/v1/finance/shops")[0]==401
            opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
            assert request(base,"/api/v1/auth/login",{"organizationCode":"heyi","phone":"13800000000","password":password},opener=opener)[0]==200
            assert request(base,"/api/v1/finance/shops",opener=opener)[0]==200
            for path,digest in manifest["images"]["frontend"]["smokeAssets"].items():
                code,body,_=request(front,"/"+path);assert code==200 and release.sha(body)==digest,path
            marker=control/"maintenance.json";marker.write_text('{"owner":"ci"}')
            for path in ["/api/v1/finance/shops","/api/v1/auth/login","/callback/test"]:
                code,body,headers=request(base,path,{});assert code==503 and headers["Retry-After"]=="30" and json.loads(body)["success"] is False
            assert request(base,"/actuator/health")[0]==200
            code,body,_=request(base,"/_heyi/ops/status",headers={"X-Heyi-Ops-Token":token});assert code==200 and json.loads(body)["maintenance"] is True
            marker.unlink();assert request(base,"/api/v1/finance/shops")[0]==401
            manifest["validation"]["containerSmoke"]="passed"
            file.write_text(json.dumps(manifest,sort_keys=True,indent=2)+"\n")
            print("Clean candidate images: migrations, health, auth, synthetic read, static files and maintenance passed")
        finally:
            dc("down","--volumes","--remove-orphans")


if __name__=="__main__":
    p=argparse.ArgumentParser();p.add_argument("manifest",type=Path);smoke(p.parse_args().manifest)
