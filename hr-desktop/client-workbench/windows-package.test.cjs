const test = require('node:test');
const assert = require('node:assert/strict');
const {profileEnvironment, packageEntries} = require('./windows-package.cjs');

test('native Windows AppData stays separate while existing assistant ledgers retain their location', () => {
  const inherited = {APPDATA: 'C:\\Users\\fixture\\AppData\\Roaming',
    LOCALAPPDATA: 'C:\\Users\\fixture\\AppData\\Local', USERPROFILE: 'C:\\Users\\fixture',
    HTTPS_PROXY: 'private-system-proxy', NODE_OPTIONS: 'private-options', ELECTRON_RUN_AS_NODE: '1'};
  const plan = profileEnvironment('C:\\Users\\fixture\\AppData\\Local\\HeyiWorkbench', inherited);
  assert.equal(plan.assistantRoot, inherited.APPDATA + '\\heyi-recruiter');
  assert.notEqual(plan.env.APPDATA, inherited.APPDATA);
  assert.equal(plan.env.USERPROFILE, inherited.USERPROFILE);
  assert.equal(plan.env.HTTPS_PROXY, undefined);
  assert.equal(plan.env.NODE_OPTIONS, undefined);
  assert.equal(plan.env.ELECTRON_RUN_AS_NODE, undefined);
  assert.equal(inherited.ELECTRON_RUN_AS_NODE, '1');
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
