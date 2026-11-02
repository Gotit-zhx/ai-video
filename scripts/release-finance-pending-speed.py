"""Bounded pending-transaction batch and retry correctness patch, exact live overlay."""
import importlib.util
import os
import sys

os.umask(0o077)
spec=importlib.util.spec_from_file_location("storage","/opt/heyi/releases/finance-storage-recovery-20261102/release-finance-storage-recovery.py")
storage=importlib.util.module_from_spec(spec);spec.loader.exec_module(storage)
core,base,ROOT,APP,FRONT=storage.core,storage.base,storage.ROOT,storage.APP,storage.FRONT
IMAGE="ai-commerce-platform:finance-pending-speed-20261102"
storage.IMAGE=IMAGE;storage.FAMILIES={"FinanceMarketplace","FinanceSync"}

def verify():
    core.health(APP)
    assert core.inspect(APP)["Config"]["Image"]==IMAGE
    assert core.sha(core.run("docker","exec",APP,"cat","/app/app.jar"))==core.read_json("prepared.json")["jarHash"]
    for name in (APP,FRONT,"ai-commerce-mysql","ai-commerce-redis"):
        assert core.inspect(name)["HostConfig"]["LogConfig"]["Type"]=="none"
    print("Pending batch patch, service health and no-log configuration verified.")

def deploy():
    assert core.read_json("qa-passed.json")["tests"]==6
    assert core.sha(core.run("docker","exec",APP,"cat","/app/app.jar"))==core.read_json("prepared.json")["previousJar"]
    before={name:core.inspect(name) for name in (APP,FRONT)}
    rollback=IMAGE+"-before";core.run("docker","tag",before[APP]["Image"],rollback)
    try:
        core.run("docker","stop","--timeout","120",APP)
        storage.activate(before,APP,IMAGE);verify()
        assert core.configuration(core.inspect(APP))==core.configuration(before[APP])
        assert core.inspect(FRONT)["Id"]==before[FRONT]["Id"]
    except BaseException:
        storage.activate(before,APP,rollback);core.health(APP);raise
    core.write_json(ROOT/"activation.json",{"image":IMAGE,"rollbackImage":rollback,"tests":6,
        "configurationPreserved":True,"unrelatedJarEntriesPreserved":True})

def drain_deploy():
    storage.queue.deploy=deploy;storage.queue.drain_deploy()

if __name__=="__main__":
    {"build":storage.build,"drain-deploy":drain_deploy,"verify":verify}[sys.argv[2]]()
