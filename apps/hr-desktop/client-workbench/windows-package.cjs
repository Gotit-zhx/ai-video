// Integrity checks need the archive's real bytes. Electron's ordinary fs treats
// app.asar as a virtual directory, including in its Node-mode controller.
const rawFS = process.versions.electron ? require('original-fs') : require('node:fs');
const fs = rawFS.promises;
const path = require('node:path');
const crypto = require('node:crypto');
const original = require('./windows-original.json');

function relativeFile(value) {
  return typeof value === 'string' && value.length < 1024 &&
    value.split('/').every(part => part && part !== '.' && part !== '..') &&
    !/[:\\\0]/.test(value) && !path.posix.isAbsolute(value);
}
function packageEntries(manifest) {
  const files = Object.entries(manifest?.files || {});
  if (manifest?.schemaVersion !== 1 || files.length < 1 || files.length > 15000 ||
      files.some(([file, hash]) => !relativeFile(file) || !/^[a-f0-9]{64}$/.test(hash))) {
    throw Error('Invalid Windows workbench manifest');
  }
  return files;
}
async function hashFile(file) {
  const hash = crypto.createHash('sha256');
  for await (const chunk of rawFS.createReadStream(file)) hash.update(chunk);
  return hash.digest('hex');
}
async function verifiedFile(root, relative) {
  if (!relativeFile(relative)) throw Error('Invalid package filename');
  const file = path.join(root, ...relative.split('/')), resolved = await fs.realpath(file);
  const inside = path.relative(root, resolved);
  if (!inside || inside === '..' || inside.startsWith('..' + path.sep) || path.isAbsolute(inside) ||
      !(await fs.stat(resolved)).isFile()) throw Error('Package file outside installation');
  return resolved;
}
async function verifyPackage(input) {
  let stage = 'root', fileKind, fileIndex;
  try {
  const root = await fs.realpath(input);
  stage = 'manifest';
  const manifestFile = await verifiedFile(root, 'heyi-package.json');
  if ((await fs.stat(manifestFile)).size > 3 * 1024 * 1024) throw Error('Package manifest too large');
  const manifest = JSON.parse(await fs.readFile(manifestFile, 'utf8'));
  const files = new Map(packageEntries(manifest));
  stage = 'pinned-original';
  for (const [file, expected] of Object.entries(original.files)) {
    fileKind = file === 'boss-zhipin.exe' ? 'original-exe' :
      file === 'resources/app.asar' ? 'original-asar' : 'original-manifest';
    const relative = 'native-client/' + file;
    if (files.get(relative) !== expected ||
        await hashFile(await verifiedFile(root, relative)) !== expected) {
      throw Error('Pinned original Windows client differs');
    }
  }
  stage = 'required-files';fileKind = undefined;
  for (const required of ['native-client/resources/heyi/windows-workbench.cjs',
    'native-client/resources/heyi/windows-launcher.cjs',
    'native-client/resources/heyi-runtime/heyi-boss.exe']) {
    if (!files.has(required)) throw Error('Windows workbench package incomplete');
  }
  stage = 'all-file-hashes';fileIndex = 0;
  for (const [file, expected] of files) {
    fileIndex++;
    if (await hashFile(await verifiedFile(root, file)) !== expected) throw Error('Windows workbench file differs');
  }
  return {root, executable: path.join(root, 'native-client', 'boss-zhipin.exe'),
    addon: path.join(root, 'native-client', 'resources', 'heyi', 'windows-workbench.cjs'),
    requestBuilder: original.requestBuilder};
  } catch (error) {
    const code = ['ENOENT', 'EACCES', 'EPERM', 'EISDIR', 'EINVAL'].includes(error?.code) ? error.code :
      error?.name === 'TypeError' ? 'type-error' : 'verification-failed';
    throw Object.assign(Error('Windows workbench verification incomplete'), {
      packageDiagnostic: {stage, fileKind, fileIndex, code},
    });
  }
}
function profileEnvironment(root, inherited = process.env) {
  const win = path.win32;
  const value = name => Object.entries(inherited).find(([key]) => key.toUpperCase() === name)?.[1];
  if (!win.isAbsolute(root) || !win.isAbsolute(value('APPDATA') || '') ||
      !win.isAbsolute(value('LOCALAPPDATA') || '')) throw Error('Windows application data paths required');
  const env = Object.fromEntries(Object.entries(inherited).filter(([name]) =>
    !/^(?:APPDATA|LOCALAPPDATA|USERPROFILE|TEMP|TMP|NODE_OPTIONS|NODE_PATH|ELECTRON_RUN_AS_NODE|ELECTRON_ENABLE_LOGGING|(?:https?|all|ftp|no)_proxy)$/i.test(name)));
  env.APPDATA = win.join(root, 'app-data');
  env.LOCALAPPDATA = win.join(root, 'local-app-data');
  env.USERPROFILE = win.join(root, 'user');
  env.TEMP = env.TMP = win.join(root, 'temp');
  return {env, profileRoot: root, profile: win.join(root, 'profile'),
    assistantRoot: win.join(value('APPDATA'), 'heyi-recruiter')};
}
module.exports = {verifyPackage, profileEnvironment, packageEntries, hashFile};
