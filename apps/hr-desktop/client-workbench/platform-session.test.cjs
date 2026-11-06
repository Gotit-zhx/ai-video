const test = require('node:test');
const assert = require('node:assert/strict');
const {EventEmitter} = require('node:events');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');
const {PlatformSession} = require('./platform-session.cjs');

async function fixture(t) {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'heyi-session-test-'));
  t.after(() => fs.rm(directory, {recursive: true, force: true}));
  // Tests use authenticated encryption without an OS login/keychain prompt.
  const key = crypto.randomBytes(32);
  const safeStorage = {
    isEncryptionAvailable: () => true,
    encryptString(text) {
      const iv = crypto.randomBytes(12), cipher = crypto.createCipheriv('aes-256-gcm', key, iv);
      const data = Buffer.concat([cipher.update(text), cipher.final()]);
      return Buffer.concat([iv, cipher.getAuthTag(), data]);
    },
    decryptString(bytes) {
      const decipher = crypto.createDecipheriv('aes-256-gcm', key, bytes.subarray(0, 12));
      decipher.setAuthTag(bytes.subarray(12, 28));
      return Buffer.concat([decipher.update(bytes.subarray(28)), decipher.final()]).toString();
    },
  };
  function make(rows = [], now = 1000000) {
    let jar = rows, flushes = 0;
    const cookies = new EventEmitter();
    cookies.get = async () => [...jar];
    cookies.set = async cookie => {jar = [{...cookie, domain: new URL(cookie.url).hostname}];};
    cookies.flushStore = async () => {flushes++;};
    const session = new PlatformSession({session: {cookies}, safeStorage, directory, now: () => now});
    return {session, cookies, rows: () => jar, flushes: () => flushes,
      logout() {const old = jar[0];jar = [];cookies.emit('changed', {}, old, 'explicit', true);}};
  }
  const cookie = {name: 'HEYI_SESSION', value: 'test-cookie-do-not-log',
    domain: 'hoyee.org.cn', path: '/', secure: true, httpOnly: true, sameSite: 'lax', session: true};
  return {directory, make, cookie, safeStorage};
}
test('a replacement reuses the same encrypted valid session without saving a password', async t => {
  const f = await fixture(t), old = f.make([f.cookie]);
  await old.session.start();await old.session.close();
  const data = await fs.readFile(path.join(f.directory, 'platform-session.enc'));
  assert.equal(data.includes(Buffer.from(f.cookie.value)), false);
  assert.ok(old.flushes() > 0);
  const next = f.make();
  await next.session.start();
  assert.equal(next.rows()[0].value, f.cookie.value);
  assert.equal(next.rows()[0].expirationDate, undefined, 'does not invent a server cookie lifetime');
  await next.session.close();
});
test('explicit logout deletes the saved session and cannot be undone by restart', async t => {
  const f = await fixture(t), old = f.make([f.cookie]);
  await old.session.start();old.logout();await old.session.chain;await old.session.close();
  const next = f.make();await next.session.start();
  assert.equal(next.rows().length, 0);await next.session.close();
});
test('expired snapshots and foreign domains cannot restore an authentication cookie', async t => {
  const f = await fixture(t), old = f.make([f.cookie]);
  await old.session.start();await old.session.close();
  const expired = f.make([], 1000000 + 9 * 3600000);
  await expired.session.start();assert.equal(expired.rows().length, 0);await expired.session.close();
  const other = f.make([{...f.cookie, domain: 'other.invalid'}]);
  await other.session.start();await other.session.close();
  await assert.rejects(fs.access(path.join(f.directory, 'platform-session.enc')), {code: 'ENOENT'});
});
test('a current session takes precedence over a previously saved account', async t => {
  const f = await fixture(t), old = f.make([f.cookie]);
  await old.session.start();await old.session.close();
  const next = f.make([{...f.cookie, value: 'new-session'}]);
  await next.session.start();
  assert.equal(next.rows()[0].value, 'new-session');await next.session.close();
});
test('when OS encryption is unavailable no plaintext fallback is created', async t => {
  const f = await fixture(t);
  f.safeStorage.isEncryptionAvailable = () => false;
  const next = f.make([f.cookie]);
  assert.equal((await next.session.start()).available, false);
  await next.session.close();
  await assert.rejects(fs.access(path.join(f.directory, 'platform-session.enc')), {code: 'ENOENT'});
});
