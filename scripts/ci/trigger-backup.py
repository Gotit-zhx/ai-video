"""Explicit, single-attempt CircleCI fallback; credentials stay in the environment."""
import argparse
import json
import os
import urllib.error
import urllib.request


def trigger(branch, token, opener=urllib.request.urlopen):
    request = urllib.request.Request(
        "https://circleci.com/api/v2/project/gh/HaLoBAI/ai-video/pipeline",
        data=json.dumps({"branch": branch, "parameters": {"desktop_build": True}}).encode(),
        headers={"Circle-Token": token, "Content-Type": "application/json"},
        method="POST",
    )
    # Never retry a POST whose response was lost: a second VM may already cost
    # credits. Check the CircleCI dashboard before triggering again.
    with opener(request, timeout=30) as response:
        data = json.load(response)
    if not data.get("id"):
        raise ValueError("No pipeline ID returned; inspect CircleCI before retrying")
    return data["id"]


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--branch", required=True, help="Existing pushed branch to build")
    args = parser.parse_args()
    token = os.environ.get("CIRCLECI_TOKEN")
    if not token:
        parser.exit(2, "Set CIRCLECI_TOKEN locally after connecting the private repository on the Free plan.\n")
    try:
        pipeline = trigger(args.branch, token)
    except urllib.error.HTTPError as error:
        parser.exit(1, f"CircleCI rejected the request (HTTP {error.code}); inspect account access and quota.\n")
    except (urllib.error.URLError, TimeoutError, ValueError) as error:
        parser.exit(1, f"Request outcome is unconfirmed ({type(error).__name__}); check CircleCI before retrying.\n")
    print(f"Backup pipeline started: {pipeline}")
