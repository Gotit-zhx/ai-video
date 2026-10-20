const test = require('node:test');
const assert = require('node:assert/strict');
const {EventEmitter} = require('node:events');
const {ownWindows, checkStartupAccount} = require('./windows-window-owner.cjs');

function fixture(t) {
  const app = new EventEmitter(), all = [], adopted = [];
  const integrated = {
    attachWindow(win) {adopted.push(win);this.window ||= win;},
    selectWindow(win) {this.window = win;}, enablePaste() {},
  };
  const owner = ownWindows({app, integrated,
    BrowserWindow: {getAllWindows: () => [...all]},
    webContents: {getAllWebContents: () => []}});
  t.after(() => owner.close());
  function create(name) {
    const win = Object.assign(new EventEmitter(), {
      name, webContents: new EventEmitter(), isDestroyed: () => false,
      isVisible: () => false, getBrowserViews: () => [],
    });
    all.push(win);app.emit('browser-window-created', {}, win);
    return win;
  }
  return {owner, create, integrated, adopted};
}
test('a name assigned after creation/load is adopted on native show', async t => {
  const {owner, create, integrated, adopted} = fixture(t);
  const waiting = owner.ready();
  const login = create();
  await new Promise(resolve => setImmediate(resolve));
  login.webContents.emit('did-finish-load');
  assert.equal(adopted.length, 0);
  login.name = 'login';
  login.emit('show');
  await waiting;
  assert.equal(integrated.window, login);
  assert.equal(adopted.length, 1);
  login.emit('ready-to-show');login.emit('show');
  assert.equal(adopted.length, 1);
});
test('a later main window is adopted even when its first show bypasses the wrapper', async t => {
  const {create, integrated} = fixture(t);
  create('login');
  const main = create();
  await new Promise(resolve => setImmediate(resolve));
  main.name = 'main';main.emit('show');
  assert.equal(integrated.window, main);
});
test('no native window is a bounded startup failure, not a successful invisible app', async t => {
  const {owner} = fixture(t);
  await assert.rejects(owner.ready(10), /window is not ready/);
});
test('UI startup does not bind an empty identity or dismiss an account risk hold', async () => {
  let calls = 0;
  assert.equal(await checkStartupAccount({user: () => ({}),
    verifyAccount() {calls++;}}), null);
  assert.equal(calls, 0);
  for (const code of ['AUTH_REQUIRED', 'SESSION_PREPARING', 'SESSION_MISMATCH', 'ACCOUNT_RISK']) {
    const result = await checkStartupAccount({user: () => ({userId: '101'}),
      async verifyAccount(account) {
        assert.equal(account, '101');throw Object.assign(Error('fixture'), {code});
      }});
    assert.equal(result, ['SESSION_MISMATCH', 'ACCOUNT_RISK'].includes(code) ? code : null);
  }
});
