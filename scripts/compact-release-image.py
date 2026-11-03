#!/usr/bin/env python3
"""Flatten an unstarted, volume-free release image and verify every file.

Never exports a running container. Does not deploy, remove images, or touch
business volumes. Image configuration and filesystem verification must pass
before the caller may activate the new image.
"""
import hashlib
import json
import subprocess
import sys
import tarfile
import uuid


def run(*args):
    return subprocess.check_output(args, stderr=subprocess.PIPE)


def inspect(image):
    return json.loads(run("docker", "image", "inspect", image))[0]


def quoted(value):
    # Dockerfile metadata parsing expands dollar signs, including quoted ones.
    return json.dumps(value, ensure_ascii=False).replace("$", "\\$")


def changes(config):
    supported = {"Env", "Labels", "Entrypoint", "Cmd", "User", "WorkingDir",
                 "ExposedPorts", "Volumes", "StopSignal"}
    unsupported = {k for k, v in config.items() if v and k not in supported}
    if unsupported or config.get("Volumes"):
        raise ValueError("Image has volumes or unsupported metadata; refusing compaction")
    result = []
    for item in config.get("Env", []):
        key, value = item.split("=", 1)
        result.append("ENV " + key + "=" + quoted(value))
    for key, value in (config.get("Labels") or {}).items():
        result.append("LABEL " + quoted(key) + "=" + quoted(value))
    for key in ("Entrypoint", "Cmd"):
        if config.get(key) is not None:
            result.append(key.upper() + " " + json.dumps(config[key]))
    if config.get("User"):
        result.append("USER " + config["User"])
    if config.get("WorkingDir"):
        result.append("WORKDIR " + config["WorkingDir"])
    if config.get("StopSignal"):
        result.append("STOPSIGNAL " + config["StopSignal"])
    for port in config.get("ExposedPorts") or {}:
        result.append("EXPOSE " + port)
    return result


def normalized_config(config):
    return {k: v for k, v in config.items() if v not in (None, "", [], {})}


def create(image):
    return run("docker", "create", "--network", "none", "--name",
               "release-compact-" + uuid.uuid4().hex[:12], image).decode().strip()


def manifest(container):
    process = subprocess.Popen(["docker", "export", container],
                               stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    result = {}
    try:
        with tarfile.open(fileobj=process.stdout, mode="r|") as archive:
            for member in archive:
                path = member.name.removeprefix("./").rstrip("/")
                # Docker creates these per-container; they are not image data.
                if path in {".dockerenv", "etc/hosts", "etc/hostname", "etc/resolv.conf"}:
                    continue
                digest = None
                if member.isfile():
                    digest = hashlib.sha256()
                    with archive.extractfile(member) as stream:
                        while chunk := stream.read(1024 * 1024):
                            digest.update(chunk)
                    digest = digest.hexdigest()
                result[path] = (member.type.decode(), member.mode, member.uid,
                                member.gid, member.size, member.linkname,
                                member.devmajor, member.devminor,
                                member.pax_headers, digest)
        if process.wait(timeout=30):
            raise RuntimeError("Filesystem export failed")
        return result
    finally:
        if process.poll() is None:
            process.terminate()
            process.wait(timeout=30)
        process.stdout.close()
        process.stderr.close()


def compact(source, target):
    old = inspect(source)
    metadata = changes(old["Config"])
    # Refuse to overwrite a tagged release.
    exists = subprocess.run(["docker", "image", "inspect", target],
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    if exists.returncode == 0:
        raise ValueError("Target image already exists")
    containers = []
    created = False
    try:
        original = create(old["Id"])
        containers.append(original)
        before = manifest(original)
        platform = old["Os"] + "/" + old["Architecture"]
        if old.get("Variant"):
            platform += "/" + old["Variant"]
        command = ["docker", "image", "import", "--platform", platform]
        for change in metadata:
            command += ["--change", change]
        command += ["-", target]
        exporter = subprocess.Popen(["docker", "export", original],
                                    stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        try:
            imported = subprocess.run(command, stdin=exporter.stdout,
                                      stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                      timeout=180)
            exporter.stdout.close()
            if imported.returncode or exporter.wait(timeout=30):
                raise RuntimeError("Image import failed")
            created = True
        finally:
            if exporter.poll() is None:
                exporter.terminate()
                exporter.wait(timeout=30)
            exporter.stderr.close()
        new = inspect(target)
        if normalized_config(old["Config"]) != normalized_config(new["Config"]):
            raise RuntimeError("Image configuration differs; activation prohibited")
        assert all(old.get(k) == new.get(k) for k in ("Architecture", "Os", "Variant"))
        replacement = create(target)
        containers.append(replacement)
        after = manifest(replacement)
        if before != after:
            changed = sum(before.get(k) != after.get(k) for k in before.keys() | after.keys())
            raise RuntimeError(f"Filesystem differs at {changed} paths; activation prohibited")
        return {"source": source, "sourceId": old["Id"], "target": target,
                "targetId": new["Id"], "oldBytes": old["Size"],
                "newBytes": new["Size"], "oldLayers": len(old["RootFS"]["Layers"]),
                "newLayers": len(new["RootFS"]["Layers"]), "verifiedPaths": len(before),
                "configurationEqual": True, "filesystemEqual": True}
    except BaseException:
        if created:
            for container in containers[1:]:
                run("docker", "rm", "-v", container)
            containers = containers[:1]
            run("docker", "image", "rm", target)
        raise
    finally:
        for container in containers:
            run("docker", "rm", "-v", container)


def prepare(source, target):
    """Choose a verified base without adding another unbounded overlay chain."""
    image = inspect(source)
    if len(image["RootFS"]["Layers"]) <= 8 and image["Size"] <= 600 * 1024 ** 2:
        return {"baseImage": image["Id"], "compacted": False}
    receipt = compact(source, target)
    return {"baseImage": receipt["targetId"], "compacted": True, **receipt}


if __name__ == "__main__":
    if sys.argv[1] == "--prepare":
        result = prepare(sys.argv[2], sys.argv[3])
    else:
        result = compact(sys.argv[1], sys.argv[2])
    print(json.dumps(result, ensure_ascii=False))
