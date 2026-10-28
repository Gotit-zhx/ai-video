"""Allow choosing the current whole reporting month; replace only two frontend assets."""
import gzip
import importlib.util
import json
from pathlib import Path
import shutil
import sys
import time

root = Path(sys.argv[1]).resolve()
source = "/opt/heyi/releases/finance-settled-goods-20261026/release-finance-settled-goods.py"
spec = importlib.util.spec_from_file_location("calendar_base", source)
base = importlib.util.module_from_spec(spec)
spec.loader.exec_module(base)
core = base.core
front = "ai-commerce-frontend"
tag = "ai-commerce-frontend:finance-reporting-months-calendar-20261028"
names = (front, "ai-commerce-app", "ai-commerce-mysql", "ai-commerce-redis")
before = {name: core.inspect(name) for name in names}
assert before[front]["Config"]["Image"] == "ai-commerce-frontend:finance-reporting-months-20261028"
assert not (root / "before.json").exists()
core.write_json(root / "before.json", before)
expected = json.loads((root / "expected.json").read_text())
folder = root / "image"
folder.mkdir()
lines = ["FROM " + before[front]["Image"]]
for name in ("index.html", "finance-workspace.js"):
    value = (root / name).read_bytes()
    live = core.run("docker", "exec", front, "cat", "/usr/share/nginx/html/" + name)
    assert core.sha(live) == expected[name], "Frontend changed since preparation"
    shutil.copy2(root / name, folder / name)
    (folder / (name + ".gz")).write_bytes(gzip.compress(value, mtime=0))
    lines.append("COPY " + name + " " + name + ".gz /usr/share/nginx/html/")
(folder / "Dockerfile").write_text("\n".join(lines) + "\n")
core.run("docker", "build", "--pull=false", "-t", tag, str(folder))
for name in names:
    assert core.inspect(name)["Id"] == before[name]["Id"]
rollback = "ai-commerce-frontend:finance-reporting-months-calendar-before-20261028"
core.run("docker", "tag", before[front]["Image"], rollback)
try:
    core.activate(before, front, tag)
    for attempt in range(15):
        try:
            core.run("docker", "exec", front, "nginx", "-t")
            for name in ("index.html", "finance-workspace.js"):
                value = core.run("curl", "--noproxy", "*", "-fsS", "--max-time", "10",
                    "--resolve", "hoyee.org.cn:443:127.0.0.1",
                    "https://hoyee.org.cn/" + name)
                assert core.sha(value) == core.sha((root / name).read_bytes())
            break
        except Exception:
            if attempt == 14:
                raise
            time.sleep(1)
    assert core.configuration(core.inspect(front)) == core.configuration(before[front])
    assert all(core.inspect(name)["Id"] == before[name]["Id"] for name in names if name != front)
    core.write_json(root / "verified.json", {"image": tag, "onlyFrontendReplaced": True,
        "files": {name: core.sha((root / name).read_bytes()) for name in ("index.html", "finance-workspace.js")}})
    print("Whole-month calendar and contextual month link published; backend and databases unchanged.")
except BaseException:
    core.activate(before, front, rollback)
    raise
