#!/usr/bin/env python3
"""Run the exact candidate digests against disposable MySQL/Redis with no egress."""
import argparse
import json
import os
import secrets
import subprocess
import tempfile
from pathlib import Path
import release


def smoke(file, image_refs=None, legacy_gate=False):
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
            "mysql":{"image":runtime["mysqlImage"],"command":["--skip-log-bin"],"environment":{"MYSQL_ROOT_PASSWORD":password,"MYSQL_DATABASE":"heyi_ci","MYSQL_USER":"heyi_ci","MYSQL_PASSWORD":password},"tmpfs":["/var/lib/mysql:rw,size=3g"],"healthcheck":{"test":["CMD-SHELL","MYSQL_PWD=\"$$MYSQL_ROOT_PASSWORD\" mysql --protocol=TCP -h127.0.0.1 -uroot --batch --skip-column-names -e 'SELECT 1'"],"interval":"2s","timeout":"2s","retries":60}},
            "redis":{"image":runtime["redisImage"]},
            "app":{"image":(image_refs or {}).get("app") or manifest["images"]["app"]["repository"]+"@"+manifest["images"]["app"]["manifestDigest"],"platform":"linux/amd64",
                "volumes":[str(control)+":/run/heyi-ops:ro"],
                "depends_on":{"mysql":{"condition":"service_healthy"},"redis":{"condition":"service_started"}},
                "environment":{"DB_URL":"jdbc:mysql://mysql:3306/heyi_ci?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=UTC","DB_USERNAME":"heyi_ci","DB_PASSWORD":password,
                  "REDIS_HOST":"redis","REDIS_PASSWORD":"","APP_ADMIN_PASSWORD":password,"APP_DISPATCH_ENABLED":"false","APP_OSS_ENABLED":"false",
                  "APP_SHOP_LIVE_PUBLISHING_ENABLED":"false","APP_SESSION_COOKIE_SECURE":"false","APP_MODEL_SECRET_KEY":secrets.token_hex(32),"APP_SHOP_CREDENTIAL_KEY":secrets.token_hex(32)}},
            "frontend":{"image":(image_refs or {}).get("frontend") or manifest["images"]["frontend"]["repository"]+"@"+manifest["images"]["frontend"]["manifestDigest"],"platform":"linux/amd64",
                "volumes":[str(root/"certs")+":/etc/letsencrypt:ro",str(control)+":/run/heyi-ops:ro"],"depends_on":["app"]}}}
        spec["services"]["probe"]={"image":runtime["pythonImage"],"profiles":["probe"],
            "command":["python3","/qa/probe.py"],"environment":{"HEYI_SMOKE_PASSWORD":password},
            "volumes":[str(control)+":/run/heyi-ops",str(file.resolve())+":/qa/release.json:ro",
                       str(Path(__file__).with_name("probe.py").resolve())+":/qa/probe.py:ro"]}
        # Bounded synthetic startup diagnostics exist only until fixture teardown.
        for service in spec["services"].values():service["logging"]={"driver":"local","options":{"max-size":"1m","max-file":"1","compress":"false"}}
        compose=root/"compose.json";compose.write_text(json.dumps(spec));compose.chmod(0o600)
        def dc(*args):return subprocess.check_output(["docker","compose","-f",str(compose),*args],stderr=subprocess.STDOUT,timeout=540).decode().strip()
        try:
            dc("up","-d","--wait","--wait-timeout","300")
            dc("run","--rm","--no-deps","probe")
            frontend_container=dc("ps","-q","frontend")
            edge=subprocess.check_output(["docker","exec",frontend_container,"wget","-qO-","http://127.0.0.1/_heyi/nginx-status"],text=True)
            assert "Writing:" in edge
            if legacy_gate:
                from adoption_fixture import exercise
                exercise(dc)
            manifest["validation"]["containerSmoke"]="passed"
            file.write_text(json.dumps(manifest,sort_keys=True,indent=2)+"\n")
            print("Clean candidate images: migrations, health, auth, synthetic read, static files and maintenance passed")
        except (subprocess.CalledProcessError,subprocess.TimeoutExpired) as error:
            diagnostic=(error.output or b'').decode(errors='replace')
            try:diagnostic+='\n'+dc('logs','--no-color','--tail','120','app','frontend','mysql')
            except Exception:pass
            for secret in [password,token,*[v for k,v in spec['services']['app']['environment'].items() if 'KEY' in k or 'PASSWORD' in k]]:
                if secret:diagnostic=diagnostic.replace(secret,'[synthetic-secret-redacted]')
            output=release.ROOT/'target/ci-reports/container';output.mkdir(parents=True,exist_ok=True)
            (output/'diagnostics.log').write_text(diagnostic[-50000:])
            raise RuntimeError('Synthetic container smoke failed; inspect local target/ci-reports/container/diagnostics.log') from None
        finally:
            dc("down","--volumes","--remove-orphans")


if __name__=="__main__":
    p=argparse.ArgumentParser();p.add_argument("manifest",type=Path);p.add_argument('--legacy-gate',action='store_true')
    a=p.parse_args();smoke(a.manifest,legacy_gate=a.legacy_gate)
