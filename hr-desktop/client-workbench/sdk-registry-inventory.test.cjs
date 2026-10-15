const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const os = require('node:os');
const {launchInspecting, evaluateRunning} = require('./inspector-driver.cjs');
const {inspectSdkRegistry} = require('./sdk-registry-inventory.cjs');

async function fixture(t, source) {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'heyi-registry-inventory-'));
  const file = path.join(root, 'fixture.cjs');
  await fs.writeFile(file, source + '\nglobalThis.fixtureReady=true;setInterval(()=>{},1000);');
  const connection = await launchInspecting(process.execPath, [file]);
  t.after(async () => {
    connection.client.close();connection.child.kill();
    await fs.rm(root, {recursive: true, force: true});
  });
  const deadline = Date.now() + 5000;
  while (!(await evaluateRunning(connection, 'globalThis.fixtureReady===true'))) {
    if (Date.now() >= deadline) throw Error('Registry fixture did not start');
    await new Promise(resolve => setTimeout(resolve, 10));
  }
  return connection;
}

test('requires an explicitly anonymous isolated process before querying objects', async () => {
  let calls = 0;
  await assert.rejects(inspectSdkRegistry({client:{command(){calls++}}}),
    /Anonymous isolated runtime required/);
  assert.equal(calls, 0);
});

test('finds a non-global registry without calling its SDK, getters or proxy traps', async t => {
  const connection = await fixture(t, `
globalThis.fixtureCalls=0;globalThis.fixtureGetters=0;
const registry={
  _sdk_getLoginStatus:async()=>{fixtureCalls++;return false},
  _sdk_getNetworkStatus:async()=>{fixtureCalls++;return false},
  secretToken:'private-token-must-not-be-retained',
};
Object.defineProperty(registry,'_sdk_sensitiveGetter',{
  get(){fixtureGetters++;throw Error('getter must never run')}
});
const decoy=new Proxy({},{
  getOwnPropertyDescriptor(){fixtureGetters++;throw Error('proxy must never run')},
  ownKeys(){fixtureGetters++;throw Error('proxy must never run')}
});
globalThis.fixtureKeepAlive=()=>[registry,decoy];
`);
  const report = await inspectSdkRegistry(connection, {anonymousIsolated: true});
  assert.equal(report.ok, true);
  assert.equal(report.reason, 'unique-sdk-registry');
  assert.deepEqual(report.registries, [{sdkNames:[
    {name:'_sdk_getLoginStatus',type:'function'},
    {name:'_sdk_getNetworkStatus',type:'function'},
    {name:'_sdk_sensitiveGetter',type:'accessor'},
  ]}]);
  assert.equal(await evaluateRunning(connection, 'fixtureCalls'), 0);
  assert.equal(await evaluateRunning(connection, 'fixtureGetters'), 0);
  assert.equal(JSON.stringify(report).includes('private-token'), false);
  assert.equal(JSON.stringify(report).includes('objectId'), false);
  assert.equal(report.sdkMethodsInvoked, 0);
  assert.equal(report.rawValuesRetained, false);
});

test('missing or multiple registries cannot be mistaken for a usable mapping', async t => {
  const connection = await fixture(t, 'globalThis.fixtureKeepAlive=[];');
  assert.equal((await inspectSdkRegistry(connection, {anonymousIsolated:true})).ok, false);
  await evaluateRunning(connection, `fixtureKeepAlive.push(...Array.from({length:2},()=>({
    _sdk_getLoginStatus:async()=>false,_sdk_getNetworkStatus:async()=>false
  })));true`);
  const report = await inspectSdkRegistry(connection, {anonymousIsolated:true});
  assert.equal(report.ok, false);
  assert.equal(report.reason, 'sdk-registry-ambiguous');
  assert.equal(report.registries.length, 2);
});

test('inspection failure releases its own object group and keeps the worker running', async t => {
  const connection = await fixture(t, 'globalThis.fixtureKeepAlive=[];');
  const command = connection.client.command.bind(connection.client);
  let released = false;
  connection.client.command = (method, params) => {
    if (method === 'Runtime.callFunctionOn') return Promise.reject(Error('fixture failure'));
    if (method === 'Runtime.releaseObjectGroup') released = true;
    return command(method, params);
  };
  await assert.rejects(inspectSdkRegistry(connection, {anonymousIsolated:true}),
    error => error.inventoryDiagnostic?.stage === 'registry-metadata');
  assert.equal(released, true);
  assert.equal(await evaluateRunning(connection, '1+1'), 2);
});
