"""Pass user-authorized Qianyi credentials through stdin; keep all credential material out of output."""
import json
import os
from pathlib import Path
import subprocess
import sys

os.umask(0o077)
root=Path(sys.argv[1]).resolve()
organization=sys.argv[2]
body=sys.stdin.buffer.read(8193)
if len(body)>8192:
    raise SystemExit("Credential input exceeded limit")
application=json.loads(subprocess.check_output(["docker","inspect","ai-commerce-app"]))[0]
database=json.loads(subprocess.check_output(["docker","inspect","ai-commerce-mysql"]))[0]
environment=dict(value.split("=",1) for value in application["Config"]["Env"])
required=("DB_URL","DB_USERNAME","DB_PASSWORD")
if not all(key in environment for key in required):
    raise SystemExit("Required runtime credential configuration is missing")
network=next(iter(set(application["NetworkSettings"]["Networks"])&set(database["NetworkSettings"]["Networks"])))
private=root/"qianyi-setup-private.env"
try:
    keys=required+tuple(key for key in ("APP_SHOP_CREDENTIAL_KEY","APP_SHOP_AUTH_CREDENTIAL_KEY") if key in environment)
    private.write_text("".join(key+"="+environment[key]+"\n" for key in keys))
    run=subprocess.run([
        "docker","run","--rm","-i","--log-driver","none","--user","0:0","--network",network,
        "--memory","384m","--cpus","1","--env-file",str(private),"-v",str(root)+":/release:ro",
        "-v","/tmp/finance-reporting-months-qa-20261028/lib:/deps:ro","--entrypoint","java",
        application["Config"]["Image"],"-Xmx192m","-cp","/release/runner:/release/migration-classes:/deps/*",
        "com.yingdao.aicommerce.finance.FinanceQianyiSetup",organization],input=body,timeout=240)
    if run.returncode:raise SystemExit("Credential setup did not finish; inspect saved binding status before retry")
finally:
    private.unlink(missing_ok=True)
