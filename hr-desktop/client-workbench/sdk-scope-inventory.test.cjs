const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const {launchInspecting, evaluateRunning} = require('./inspector-driver.cjs');
const {inspectSdkScopes} = require('./sdk-scope-inventory.cjs');

async function fixture(t, source) {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'heyi-sdk-scope-'));
  const file = path.join(root, 'fixture.cjs');
  await fs.writeFile(file, source + '\nsetInterval(()=>{},1000);');
  const connection = await launchInspecting(process.execPath, [file]);
  t.after(async () => {
    connection.client.close();connection.child.kill();
    await fs.rm(root, {recursive: true, force: true});
  });
  const deadline = Date.now() + 5000;
  while (!(await evaluateRunning(connection, 'typeof globalThis.fixtureDispatcher === "function"'))) {
    if (Date.now() >= deadline) throw Error('Scope fixture did not start');
    await new Promise(resolve => setTimeout(resolve, 20));
  }
  return connection;
}

test('finds the real closed-over registry without SDK calls, getters or value export', async t => {
  const connection = await fixture(t, `
globalThis.fixtureReads = 0; globalThis.fixtureGetters = 0;
const fixtureRegistry = {
  _sdk_getLoginStatus: async()=>{globalThis.fixtureReads++;return false},
  _sdk_getNetworkStatus: async()=>{globalThis.fixtureReads++;return {isOnline:false}},
  _sdk_chatSocket_sendTextMessage: async()=>{throw Error('must not send')},
  secretToken: 'secret-value-must-not-appear',
};
Object.defineProperty(fixtureRegistry, '_sdk_fixtureGetter', {
  get(){globalThis.fixtureGetters++;throw Error('must not evaluate')},
});
const fixtureDispatch = name=>fixtureRegistry[name]();
class Transport {send(){globalThis.fixtureReads++} receiveMessage(){globalThis.fixtureReads++}}
const transport = new Transport();
globalThis.fixtureDispatcher = ()=>[fixtureDispatch, transport];
`);
  const report = await inspectSdkScopes(connection, 'globalThis.fixtureDispatcher');
  const registry = report.scopes.flatMap(scope => scope.registries || [])
    .find(item => item.binding === 'fixtureRegistry');
  assert.ok(registry);
  assert.deepEqual(registry.sdkNames.map(item => item.name), [
    '_sdk_getLoginStatus', '_sdk_getNetworkStatus',
    '_sdk_chatSocket_sendTextMessage', '_sdk_fixtureGetter',
  ]);
  assert.equal(registry.sdkNames.at(-1).type, 'accessor');
  assert.equal(await evaluateRunning(connection, 'globalThis.fixtureReads'), 0);
  assert.equal(await evaluateRunning(connection, 'globalThis.fixtureGetters'), 0);
  assert.equal(JSON.stringify(report).includes('secret-value-must-not-appear'), false);
  assert.equal(JSON.stringify(report).includes('objectId'), false);
  assert.equal(report.sdkMethodsInvoked, 0);
  assert.deepEqual(report.scopes.flatMap(scope => scope.objectBindings || [])
    .find(item => item.binding === 'transport').members,
  [{name:'receiveMessage',type:'function'},{name:'send',type:'function'}]);
});

test('a scope without a registry is reported without inventing SDK availability', async t => {
  const connection = await fixture(t, `
const plain = {value:42};
globalThis.fixtureDispatcher = ()=>plain;
`);
  const report = await inspectSdkScopes(connection, 'globalThis.fixtureDispatcher');
  assert.deepEqual(report.scopes.flatMap(scope => scope.registries || []), []);
  await assert.rejects(inspectSdkScopes(connection, 'undefined'), error =>
    error.inventoryDiagnostic?.code === 'dispatcher-function-unavailable');
});

test('bound original dispatcher follows its target without calling the function', async t => {
  const connection = await fixture(t, `
globalThis.fixtureReads = 0;
const registry = {
 _sdk_getLoginStatus:async()=>{globalThis.fixtureReads++;return false},
 _sdk_getNetworkStatus:async()=>{globalThis.fixtureReads++;return false},
};
function dispatch(){return registry}
globalThis.fixtureDispatcher = dispatch.bind({});
`);
  const report = await inspectSdkScopes(connection, 'globalThis.fixtureDispatcher');
  assert.ok(report.scopes.flatMap(scope => scope.registries || [])
    .some(item => item.binding === 'registry'));
  assert.equal(await evaluateRunning(connection, 'globalThis.fixtureReads'), 0);
  assert.ok(report.functionInternals.length >= 2);
});
