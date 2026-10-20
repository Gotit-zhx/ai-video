const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const os = require('node:os');
const crypto = require('node:crypto');
const {profileEnvironment, packageEntries, hashFile, verifyPackage} = require('./windows-package.cjs');

test('native Windows AppData stays separate while existing assistant ledgers retain their location', () => {
  const inherited = {APPDATA: 'C:\\Users\\fixture\\AppData\\Roaming',
    LOCALAPPDATA: 'C:\\Users\\fixture\\AppData\\Local', USERPROFILE: 'C:\\Users\\fixture',
    HTTPS_PROXY: 'private-system-proxy', NODE_OPTIONS: 'private-options', ELECTRON_RUN_AS_NODE: '1'};
  const plan = profileEnvironment('C:\\Users\\fixture\\AppData\\Local\\HeyiWorkbench', inherited);
  assert.equal(plan.assistantRoot, inherited.APPDATA + '\\heyi-recruiter');
  assert.notEqual(plan.env.APPDATA, inherited.APPDATA);
  assert.equal(plan.env.USERPROFILE, plan.profileRoot + '\\user');
  assert.equal(plan.env.HTTPS_PROXY, undefined);
  assert.equal(plan.env.NODE_OPTIONS, undefined);
  assert.equal(plan.env.ELECTRON_RUN_AS_NODE, undefined);
  assert.equal(inherited.ELECTRON_RUN_AS_NODE, '1');
});
test('Windows path variables are replaced case-insensitively without retaining a second native profile', () => {
  const plan = profileEnvironment('C:\\isolated', {AppData: 'C:\\original\\Roaming',
    LocalAppData: 'C:\\original\\Local', UserProfile: 'C:\\original', Temp: 'C:\\original\\Temp'});
  assert.equal(plan.env.AppData, undefined);
  assert.equal(plan.env.UserProfile, undefined);
  assert.equal(plan.env.Temp, undefined);
  assert.equal(plan.env.APPDATA, 'C:\\isolated\\app-data');
  assert.equal(plan.env.USERPROFILE, 'C:\\isolated\\user');
  assert.equal(plan.assistantRoot, 'C:\\original\\Roaming\\heyi-recruiter');
});
test('package manifest rejects traversal, drive paths and malformed hashes before reading files', () => {
  const hash = 'a'.repeat(64);
  assert.deepEqual(packageEntries({schemaVersion: 1, files: {'own/file.cjs': hash}}),
    [['own/file.cjs', hash]]);
  for (const file of ['../private', '/private', 'C:/private', 'own\\..\\private', 'own//file']) {
    assert.throws(() => packageEntries({schemaVersion: 1, files: {[file]: hash}}), /manifest/);
  }
  assert.throws(() => packageEntries({schemaVersion: 1, files: {'own/file': 'invalid'}}), /manifest/);
});
test('package hashing reads complete binary bytes; invalid packages expose only fixed verification fields', async t => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'heyi-package-hash-'));
  t.after(() => fs.rm(root, {recursive: true, force: true}));
  const bytes = crypto.randomBytes(300000), file = path.join(root, 'own-fixture.bin');
  await fs.writeFile(file, bytes);
  assert.equal(await hashFile(file), crypto.createHash('sha256').update(bytes).digest('hex'));
  await assert.rejects(verifyPackage(root), error => {
    assert.equal(error.packageDiagnostic.stage, 'manifest');
    assert.equal(error.packageDiagnostic.code, 'ENOENT');
    assert.equal(JSON.stringify(error.packageDiagnostic).includes(root), false);
    return true;
  });
});
