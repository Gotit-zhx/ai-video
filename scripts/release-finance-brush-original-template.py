"""Use the user's original brush-cost workbook layout without changing imports."""
import importlib.util
import sys

spec = importlib.util.spec_from_file_location(
    "template_release",
    "/opt/heyi/releases/finance-brush-template-20261105/release-finance-brush-template.py")
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)
release.IMAGE = "ai-commerce-frontend:finance-brush-original-template-20261105"
release.PREVIOUS = "ai-commerce-frontend:finance-brush-template-20261105"


def deploy():
    qa = release.core.read_json("qa-passed.json")
    assert qa["originalColumns"] == 11 and qa["originalRowsVerified"] == 25
    expected = release.core.read_json("baseline-expected.json")
    assert release.core.sha(release.live_file(release.TEMPLATE)) == expected[release.TEMPLATE]
    release.deploy()


if __name__ == "__main__":
    {"deploy": deploy, "verify": release.verify}[sys.argv[2]]()
