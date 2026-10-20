const {app, session, safeStorage} = require('electron');
const fs = require('node:fs/promises');
const path = require('node:path');
const assert = require('node:assert/strict');
const {PlatformSession} = require('./platform-session.cjs');

async function run() {
  if (!process.env.HEYI_WORKBENCH_TEST_DATA || !process.env.HEYI_WORKBENCH_TEST_OUTPUT ||
      process.env.HEYI_WORKBENCH_TEST_PORT !== '0') throw Error('Disposable session fixture required');
  await app.whenReady();
  const phase = process.argv.includes('--heyi-session-write') ? 'write' : 'restore';
  const origin = 'https://heyi-session-fixture.invalid';
  const platform = session.fromPartition('session-encryption-fixture');
  platform.webRequest.onBeforeRequest((_request, callback) => callback({cancel: true}));
  assert.equal(safeStorage.isEncryptionAvailable(), true);
  const directory = path.join(app.getPath('userData'), 'session-fixture');
  const value = 'offline-platform-session-check';
  if (phase === 'write') {
    await platform.cookies.set({url: origin, name: 'HEYI_SESSION', value,
      path: '/', secure: true, httpOnly: true, sameSite: 'lax'});
  } else {
    assert.equal((await platform.cookies.get({url: origin})).length, 0);
  }
  const keeper = new PlatformSession({session: platform, safeStorage, directory, origin});
  await keeper.start();
  const rows = await platform.cookies.get({url: origin});
  assert.equal(rows.length, 1);
  assert.equal(rows[0].value, value);
  assert.equal(rows[0].session, true);
  await keeper.close();
  const saved = await fs.readFile(keeper.file);
  assert.equal(saved.includes(Buffer.from(value)), false);
  if (process.platform !== 'win32') assert.equal((await fs.stat(keeper.file)).mode & 0o777, 0o600);
  await fs.writeFile(process.env.HEYI_WORKBENCH_TEST_OUTPUT, JSON.stringify({
    ok: true, phase, actualOSKeychain: true, encryptedSnapshot: true,
    restoredInNewProcess: phase === 'restore', actualAccountsUsed: false, networkRequests: 0,
  }, null, 2));
  app.quit();
}
module.exports = {run};
