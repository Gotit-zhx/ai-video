"""Build a separately branded local prototype from the user-supplied app.

Original native executables, authentication, transport and security checks are
preserved. Only bounded ASAR files and our own addon are repackaged. Do not
publish the derived third-party app to the source repository.
"""
import argparse
import copy
import hashlib
import json
from pathlib import Path, PurePosixPath
import plistlib
import shutil
import struct
import subprocess

HERE = Path(__file__).resolve().parent
DESKTOP = HERE.parent
MAIN_SHA256 = "1806b34a0da262fec08afa8596b37666dfb06824eaa878e94ba39a89c46854e7"
BLOCK_SIZE = 4 * 1024 * 1024


def digest(data):
    return hashlib.sha256(data).hexdigest()


def integrity(data):
    return {"algorithm": "SHA256", "hash": digest(data), "blockSize": BLOCK_SIZE,
            "blocks": [digest(data[i:i + BLOCK_SIZE]) for i in range(0, len(data), BLOCK_SIZE)]}


def header(archive):
    with archive.open("rb") as stream:
        size = stream.read(8)
        if len(size) != 8 or struct.unpack("<I", size[:4])[0] != 4:
            raise ValueError("Unsupported ASAR header")
        amount = struct.unpack("<I", size[4:])[0]
        if amount < 8 or amount > 16 * 1024 * 1024:
            raise ValueError("ASAR header outside bounded range")
        raw = stream.read(amount)
        length = struct.unpack("<I", raw[4:8])[0]
        return json.loads(raw[8:8 + length]), 8 + amount


def traverse(tree, prefix=""):
    for name, node in tree["files"].items():
        path = prefix + name
        if "files" in node:
            yield from traverse(node, path + "/")
        else:
            yield path, node


def valid_path(path):
    parsed = PurePosixPath(path)
    return not parsed.is_absolute() and ".." not in parsed.parts and "\\" not in path


def set_file(tree, name, node):
    components = name.split("/")
    for component in components[:-1]:
        tree = tree["files"].setdefault(component, {"files": {}})
    tree["files"][components[-1]] = node


def prune(tree):
    for name in list(tree["files"]):
        node = tree["files"][name]
        if "files" in node:
            prune(node)
            if not node["files"]:
                del tree["files"][name]


def patch_main(data):
    if digest(data) != MAIN_SHA256:
        raise ValueError("Original main.js differs from the reviewed 1.7.4 input")
    text = data.decode("utf-8")
    before = "fromAvatar:QT(e),mid:HT(e),type:ET(e)"
    if text.count(before) != 1:
        raise ValueError("Original message normalization hook differs")
    text = text.replace(before, "fromAvatar:QT(e),mid:HT(e),clientMid:e.cmid,type:ET(e)", 1)
    before = 'const fI="boss-desktop"'
    if text.count(before) != 1:
        raise ValueError("Original app-link namespace differs")
    # Avoid taking over the installed official client's system URL handler.
    text = text.replace(before, 'const fI="heyi-boss-workbench"', 1)
    # Preserve real HTTP status for our own caller. Original callers still
    # receive exactly the original body and all original handling runs first.
    before = 'pg("info",{config:t,status:a,statusCode:c,ret:u}),r(l)'
    if text.count(before) != 1:
        raise ValueError("Original HTTP response hook differs")
    text = text.replace(before, 'pg("info",{config:t,status:a,statusCode:c,ret:u}),'
                        'r(e.heyiWorkbenchMeta?{heyiNativeHttp:1,statusCode:c,body:l}:l)', 1)
    before = 'n.show(),global.AppLink.handleGlobalAppLink(),t("成功")'
    if text.count(before) != 1:
        raise ValueError("Original completed-login hook differs")
    text = text.replace(before,
                        'n.show(),global.AppLink.handleGlobalAppLink(),heyiHost.loginEstablished(),t("成功")', 1)
    before = 'll=()=>ul.getDNSProxy()'
    if text.count(before) != 1:
        raise ValueError("Original network service binding differs")
    text = text.replace(before, 'heyiNativeNetwork=require("./heyi/native-network.cjs").instance(),'
                        'heyiNetworkAttached=heyiNativeNetwork.attachNative(ul),'
                        'll=()=>ul.getDNSProxy()', 1)
    before = ('const t=ul.getProxyConfig(),{useProxy:r,model:n,proxyConfig:i}=t||{};'
              'r&&i&&n===Vc.PROXY&&i.httpProxy.username&&i.httpProxy.password?'
              'e.loadURL("https://www.zhipin.com/"):e.loadURL(o)')
    if text.count(before) != 1:
        raise ValueError("Original authenticated-proxy download initialization differs")
    # The original client warms its hidden download window on an HTTPS origin
    # for authenticated proxies. Our relay is always authenticated, regardless
    # of the user's old proxy preferences. Preserve that original path without
    # writing our ephemeral credentials to the vendor configuration.
    text = text.replace(before, 'e.loadURL("https://www.zhipin.com/")', 1)
    before = 'c.name=t,c.isShow=!1,c.display={}'
    if text.count(before) != 1:
        raise ValueError("Original native window registration differs")
    text = text.replace(before, 'c.name=t,heyiShell.attachWindow(c,t),c.isShow=!1,c.display={}', 1)
    before = 'wh=function(e,t=!1){const r=ph.get(e);'
    if text.count(before) != 1:
        raise ValueError("Original native window transition differs")
    text = text.replace(before, 'wh=function(e,t=!1){'
                        'heyiShell.beforeNativeClose(e,ph.get("main"));const r=ph.get(e);', 1)
    before = 'const r=new lC(uC.TEXT).getProtoMsg(e);cC.send(r),t()'
    if text.count(before) != 1:
        raise ValueError("Original native text-message observation hook differs")
    text = text.replace(before, 'const r=new lC(uC.TEXT).getProtoMsg(e);'
                        'try{heyiHost.observeNativeSend(require("./heyi/native-protocol.cjs").decodeNativeProtocol(r))}catch{}'
                        'cC.send(r),t()', 1)
    text += """
// Heyi local modification. Original authentication and handlers remain intact.
const heyiShell = require("./heyi/integrated-shell.cjs").instance();
const heyiHost = require("./heyi/host.cjs");
const heyiOriginalReceive = sC.receiveMessage.bind(sC);
sC.receiveMessage = function(messages) {
  const result = heyiOriginalReceive(messages);
  try {heyiHost.receive(messages);} catch {}
  return result;
};
const heyiOriginalLogout = nC;
nC = function(event) {
  heyiHost.sessionLost(event);
  return heyiOriginalLogout(event);
};
const heyiOriginalSdkLogout = global._sdk_logout;
global._sdk_logout = function(...args) {
  heyiHost.sessionLost(args[0]);
  return heyiOriginalSdkLogout.apply(this, args);
};
heyiHost.start({
  user: () => zc.userInfo,
  loggedIn: () => global.isLogin === true,
  show: () => heyiShell.showNative(),
  snapshot: async () => {
    const cookies = await e.session.defaultSession.cookies.get({});
    const token = await Ep.get("wt2");
    if (token && !cookies.some(c => c.name === "wt2" && c.value === token)) {
      cookies.push({name: "wt2", value: token, domain: "www.zhipin.com"});
    }
    return {accountId: String(zc.userInfo?.userId || ""),
      cookies, userAgent: Wc("client")};
  },
  request: input => fg({...input, heyiWorkbenchMeta: true}),
  download: (input, checkAccount, signal) =>
    require("./heyi/native-download.cjs").downloadResume({
      net: e.net, session: e.session.defaultSession, url: input.url, checkAccount, signal,
      credentials: async () => ({token: await Ep.get("wt2"),
        zpToken: await Ep.getZpToken(), userAgent: Wc("client")}),
    }),
  sendText: input => cC.send(new lC(uC.TEXT).getProtoMsg(input)),
  requestResume: input => new (require("./heyi/native-resume.cjs").NativeResume)({
    nativeWindow: () => ph.get("main"),
    allContents: () => e.webContents.getAllWebContents(),
  }).request(input),
}).catch(error => {
  require("node:fs").writeFileSync(
    require("node:path").join(e.app.getPath("userData"), "heyi-startup-status.json"),
    JSON.stringify({ok: false, code: error.code || "WORKBENCH_START_FAILED",
      message: "赫逸工作台未启动；原客户端登录窗口保留"}));
  e.dialog.showErrorBox("赫逸工作台", error.message || "工作台启动未完成");
});
"""
    return text.encode("utf-8")


def own_files(sdk_path):
    result = {}
    for name in ["bootstrap.cjs", "compat.cjs", "host.cjs", "native-rpc.cjs",
                 "native-download.cjs", "shared-state.cjs", "smoke.cjs", "ui-check.cjs",
                 "native-network.cjs", "integrated-shell.cjs", "shell-preload.cjs",
                 "shell.html", "shell-renderer.cjs", "native-resume.cjs", "native-resume-smoke.cjs",
                 "platform-connection-smoke.cjs", "native-egress-smoke.cjs",
                 "native-http.cjs", "native-http-smoke.cjs", "native-protocol.cjs"]:
        result["heyi/" + name] = (HERE / name).read_bytes()
    for source in (DESKTOP / "src").glob("*.cjs"):
        result["heyi/src/" + source.name] = source.read_bytes()
    result["heyi/package.json"] = (HERE / "package.json").read_bytes()
    # Resolve isolated dependencies; never follow another app's profile paths.
    for name in ["undici", "@fastify/busboy"]:
        package = HERE / "node_modules" / name
        if not package.exists():
            package = HERE / "node_modules/undici/node_modules" / name
        if not package.exists():
            package = (HERE / "node_modules/undici").resolve().parent.parent / "node_modules" / name
        package = package.resolve(strict=True)
        for source in package.rglob("*"):
            if source.is_file() and not source.is_symlink():
                result["heyi/node_modules/" + name + "/" + source.relative_to(package).as_posix()] = source.read_bytes()
    import re
    sdk = Path(sdk_path).read_text(encoding="utf-8")
    paths = sorted(set(re.findall(r"https://www\.zhipin\.com(/wapi/[^\"'\s]+)", sdk)))
    # SDK files often compose endpoint constants from BASE_URL + string literals.
    paths += sorted(set(re.findall(r"[\"'](/wapi/[^\"'\s]+)[\"']", sdk)) - set(paths))
    if not paths:
        raise ValueError("No SDK endpoints found; do not broaden the native allowlist")
    # managed_jobs.py intentionally reads the complete management list, rather
    # than the SDK's chatted-job subset. It is an existing desktop operation
    # and must survive packaging even though recruiter.yaml does not list it.
    paths += ["/wapi/zpjob/job/data/list"]
    result["heyi/endpoints.json"] = json.dumps(sorted(set(paths))).encode()
    return result


def repack(source, target, addon):
    tree, data_start = header(source)
    tree = copy.deepcopy(tree)
    replacements = dict(addon)
    skipped = []
    size = source.stat().st_size
    original_offsets = {}
    with source.open("rb") as stream:
        for name, node in list(traverse(tree)):
            valid = valid_path(name)
            if "link" in node:
                valid = valid and valid_path(node["link"])
            elif node.get("unpacked"):
                file = Path(str(source) + ".unpacked") / name
                # Vendor code signing can change a native module's on-disk size
                # after its ASAR directory was written. Keep the real module.
                valid = valid and file.is_file()
            else:
                count, offset = node.get("size"), node.get("offset")
                try:
                    offset = int(offset)
                except (TypeError, ValueError):
                    offset = -1
                valid = valid and isinstance(count, int) and 0 <= count <= size and offset >= 0 \
                    and data_start + offset + count <= size
                if valid:
                    original_offsets[name] = offset
            if not valid:
                skipped.append(name)
                parent = tree
                parts = name.split("/")
                for part in parts[:-1]:
                    parent = parent["files"][part]
                del parent["files"][parts[-1]]
        for name in ["main.js", "package.json"]:
            node = dict(traverse(tree)).get(name)
            if not node or name not in original_offsets:
                raise ValueError("Required readable Mac entry not present")
            stream.seek(data_start + original_offsets[name])
            content = stream.read(node["size"])
            if name == "main.js":
                replacements[name] = patch_main(content)
            else:
                package = json.loads(content)
                package["main"] = "./heyi/bootstrap.cjs"
                package["heyiModification"] = {"version": "0.1.24", "experimental": True,
                                               "nativeAuthUnchanged": True}
                replacements[name] = json.dumps(package, ensure_ascii=False, indent=2).encode()
    for name, content in replacements.items():
        if not valid_path(name):
            raise ValueError("Invalid addon filename")
        set_file(tree, name, {"size": len(content), "offset": "0", "integrity": integrity(content)})
    prune(tree)
    offset = 0
    for name, node in traverse(tree):
        if "link" in node or node.get("unpacked"):
            continue
        node["offset"] = str(offset)
        offset += node["size"]
    raw = json.dumps(tree, ensure_ascii=False, separators=(",", ":")).encode()
    padded = raw + b"\0" * ((-len(raw)) % 4)
    # Chromium Pickle stores a UTF-8 JSON string with an aligned payload.
    packed = struct.pack("<II", 4 + len(padded), len(raw)) + padded
    temporary = target.with_suffix(".heyi-tmp")
    with temporary.open("wb") as output, source.open("rb") as stream:
        output.write(struct.pack("<II", 4, len(packed)))
        output.write(packed)
        for name, node in traverse(tree):
            if "link" in node or node.get("unpacked"):
                continue
            if name in replacements:
                output.write(replacements[name])
            else:
                stream.seek(data_start + original_offsets[name])
                remaining = node["size"]
                while remaining:
                    chunk = stream.read(min(1024 * 1024, remaining))
                    if not chunk:
                        raise ValueError("Original ASAR changed during build")
                    output.write(chunk)
                    remaining -= len(chunk)
    temporary.replace(target)
    # Validate every new byte range before the app can launch.
    verified, start = header(target)
    for name, node in traverse(verified):
        if "offset" in node and not node.get("unpacked"):
            if start + int(node["offset"]) + node["size"] > target.stat().st_size:
                raise ValueError("Rebuilt ASAR contains an invalid range")
    return {"keptEntries": sum(1 for _ in traverse(tree)), "skippedInvalidEntries": skipped,
            "bytes": target.stat().st_size}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--source-app", required=True, type=Path)
    parser.add_argument("--output-app", required=True, type=Path)
    parser.add_argument("--sdk-endpoints", required=True, type=Path)
    options = parser.parse_args()
    source, output = options.source_app.resolve(), options.output_app.absolute()
    if output.exists() or source == output:
        raise ValueError("Choose a fresh output app; the original is never overwritten")
    source_asar = source / "Contents/Resources/app.asar"
    original_sha = digest(source_asar.read_bytes())
    addon = own_files(options.sdk_endpoints)
    runtime = DESKTOP / "runtime/dist/heyi-boss"
    if not (runtime / "heyi-boss").is_file():
        raise ValueError("Build the target-OS runtime first")
    output.parent.mkdir(parents=True, exist_ok=True)
    shutil.copytree(source, output, symlinks=True)
    archive = output / "Contents/Resources/app.asar"
    details = repack(source_asar, archive, addon)
    shutil.copytree(runtime, output / "Contents/Resources/heyi-runtime", symlinks=True)
    plist_file = output / "Contents/Info.plist"
    plist = plistlib.loads(plist_file.read_bytes())
    # Electron 22 derives helper bundle names from CFBundleName. Preserve that
    # internal name so its existing native helper executables remain loadable.
    plist.update(CFBundleIdentifier="cn.hoyee.bossworkbench.experimental",
                 CFBundleDisplayName="赫逸工作台 · 改造测试版")
    for declaration in plist.get("CFBundleURLTypes", []):
        declaration["CFBundleURLSchemes"] = [
            "heyi-boss-workbench" if scheme == "boss-desktop" else scheme
            for scheme in declaration.get("CFBundleURLSchemes", [])]
    plist_file.write_bytes(plistlib.dumps(plist))
    (output / "Contents/Resources/heyi-modification.json").write_text(
        json.dumps({"originalAsarSha256": original_sha, "originalMainSha256": MAIN_SHA256,
                    "thirdPartySourceIncluded": True, "publicRedistribution": False,
                    "officialSignatureReplaced": True, "realAccountTested": False,
                    "nativeLoginUnchanged": True, "nativeBusinessAdaptation": "partial",
                    **details}, ensure_ascii=False, indent=2), encoding="utf-8")
    # A modification has our local ad-hoc signature, never the original vendor's.
    subprocess.run(["codesign", "--force", "--deep", "--sign", "-", str(output)], check=True,
                   stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    subprocess.run(["codesign", "--verify", "--deep", "--strict", str(output)], check=True)
    print(json.dumps({"app": str(output), **details}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
