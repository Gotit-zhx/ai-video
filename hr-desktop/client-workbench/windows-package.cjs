const fs = require('node:fs/promises');
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
  const handle = await fs.open(file, 'r');
  try {
    const hash = crypto.createHash('sha256');
    for await (const chunk of handle.createReadStream({autoClose: false})) hash.update(chunk);
    return hash.digest('hex');
  } finally {await handle.close();}
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
  const root = await fs.realpath(input);
  const manifestFile = await verifiedFile(root, 'heyi-package.json');
  if ((await fs.stat(manifestFile)).size > 3 * 1024 * 1024) throw Error('Package manifest too large');
  const manifest = JSON.parse(await fs.readFile(manifestFile, 'utf8'));
  const files = new Map(packageEntries(manifest));
  for (const [file, expected] of Object.entries(original.files)) {
    const relative = 'native-client/' + file;
    if (files.get(relative) !== expected ||
        await hashFile(await verifiedFile(root, relative)) !== expected) {
      throw Error('Pinned original Windows client differs');
    }
  }
  for (const required of ['native-client/resources/heyi/windows-workbench.cjs',
    'native-client/resources/heyi/windows-launcher.cjs',
    'native-client/resources/heyi-runtime/heyi-boss.exe']) {
    if (!files.has(required)) throw Error('Windows workbench package incomplete');
  }
  for (const [file, expected] of files) {
    if (await hashFile(await verifiedFile(root, file)) !== expected) throw Error('Windows workbench file differs');
  }
  return {root, executable: path.join(root, 'native-client', 'boss-zhipin.exe'),
    addon: path.join(root, 'native-client', 'resources', 'heyi', 'windows-workbench.cjs'),
    requestBuilder: original.requestBuilder};
}
function profileEnvironment(root, inherited = process.env) {
  const win = path.win32;
  if (!win.isAbsolute(root) || !win.isAbsolute(inherited.APPDATA || '') ||
      !win.isAbsolute(inherited.LOCALAPPDATA || '')) throw Error('Windows application data paths required');
  const env = Object.fromEntries(Object.entries(inherited).filter(([name]) =>
    !/^(?:NODE_OPTIONS|NODE_PATH|ELECTRON_RUN_AS_NODE|ELECTRON_ENABLE_LOGGING|(?:https?|all|ftp|no)_proxy)$/i.test(name)));
  env.APPDATA = win.join(root, 'app-data');
  env.LOCALAPPDATA = win.join(root, 'local-app-data');
  env.TEMP = env.TMP = win.join(root, 'temp');
  return {env, profileRoot: root, profile: win.join(root, 'profile'),
    assistantRoot: win.join(inherited.APPDATA, 'heyi-recruiter')};
}
module.exports = {verifyPackage, profileEnvironment, packageEntries};
