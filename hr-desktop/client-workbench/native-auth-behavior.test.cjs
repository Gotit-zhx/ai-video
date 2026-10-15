const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const {launchInspecting, evaluateRunning} = require('./inspector-driver.cjs');
const {inspectAnonymousAuthentication} = require('./native-auth-behavior.cjs');

async function fixture(t, code) {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'heyi-anonymous-auth-'));
  const file = path.join(root, 'fixture.cjs');
  await fs.writeFile(file, `
    globalThis.isLogin=false;globalThis.fixtureCalls=0;
    class Authentication{
      getAuthCookieHeader(){fixtureCalls++;${code}}
      get(){}setLoginCookies(){}clearLoginCookies(){}
    }
    globalThis.keepAlive=[new Authentication];
    globalThis.fixtureReady=true;setInterval(()=>{},1000);
  `);
  const connection = await launchInspecting(process.execPath, [file]);
  t.after(async () => {
    connection.client.close();connection.child.kill();
    await fs.rm(root, {recursive: true, force: true});
  });
  const deadline = Date.now() + 5000;
  while (!await evaluateRunning(connection, 'globalThis.fixtureReady===true')) {
    if (Date.now() >= deadline) throw Error('Fixture did not start');
    await new Promise(resolve => setTimeout(resolve, 10));
  }
  return connection;
}

test('authentication behavior requires an explicitly isolated anonymous process', async () => {
  let calls = 0;
  await assert.rejects(inspectAnonymousAuthentication({client: {command() {calls++;}}}),
    /Anonymous isolated/);
  assert.equal(calls, 0);
});

test('one original helper read exports only its empty-state metadata', async t => {
  const connection = await fixture(t, 'return Promise.resolve("");');
  const report = await inspectAnonymousAuthentication(connection, {anonymousIsolated: true});
  assert.equal(report.ok, true);
  assert.equal(report.resultType, 'string');
  assert.equal(report.emptyString, true);
  assert.equal(report.originalMethodsInvoked, 1);
  assert.equal(await evaluateRunning(connection, 'fixtureCalls'), 1);
});

test('return values and original failures never escape the process', async t => {
  const connection = await fixture(t, 'return "wt2=fixture-private-token";');
  const report = await inspectAnonymousAuthentication(connection, {anonymousIsolated: true});
  assert.equal(report.hasWt2Field, true);
  assert.equal(JSON.stringify(report).includes('fixture-private-token'), false);
  assert.equal(JSON.stringify(report).includes('objectId'), false);
  await evaluateRunning(connection,
    'Object.getPrototypeOf(keepAlive[0]).getAuthCookieHeader=function(){throw Error("fixture-private-token")};true');
  await assert.rejects(inspectAnonymousAuthentication(connection, {anonymousIsolated: true}), error => {
    assert.equal(JSON.stringify(error).includes('fixture-private-token'), false);
    return error.authenticationDiagnostic.stage === 'anonymous-authentication-read';
  });
});

test('a signed-in or ambiguous process invokes no helper', async t => {
  const connection = await fixture(t, 'return "must-not-read";');
  await evaluateRunning(connection, 'isLogin=true;true');
  const signedIn = await inspectAnonymousAuthentication(connection, {anonymousIsolated: true});
  assert.equal(signedIn.ok, false);
  assert.equal(signedIn.originalMethodsInvoked, 0);
  await evaluateRunning(connection, 'isLogin=false;keepAlive.push(new (keepAlive[0].constructor));true');
  const ambiguous = await inspectAnonymousAuthentication(connection, {anonymousIsolated: true});
  assert.equal(ambiguous.ok, false);
  assert.equal(await evaluateRunning(connection, 'fixtureCalls'), 0);
});

test('a stalled helper is bounded and never retried', async t => {
  const connection = await fixture(t, 'return new Promise(()=>{});');
  await assert.rejects(inspectAnonymousAuthentication(connection, {anonymousIsolated: true}));
  assert.equal(await evaluateRunning(connection, 'fixtureCalls'), 1);
});
