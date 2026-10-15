const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const {launchInspecting, evaluateRunning} = require('./inspector-driver.cjs');
const {bindNativeModule} = require('./native-bindings.cjs');

async function fixture(t) {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'heyi-native-bindings-'));
  const file = path.join(root, 'fixture.cjs'), modulePath = path.join(root, 'addon.cjs');
  const httpModule = path.join(root, 'node_modules/@bzl/electron-node-fetch/dist/index.js');
  await fs.mkdir(path.dirname(httpModule), {recursive: true});
  await fs.writeFile(httpModule, 'exports.enfetch=class Enfetch{send(){fixtureCalls++;throw Error("private-http")}};');
  await fs.writeFile(modulePath, `
    exports.install=function(bindings,config){
      fixtureInstalls++;
      if(config.throw)throw Error('private-installer');
      return {ok:Object.entries(fixtureExpected).every(([key,value])=>bindings[key]===value)&&
        bindings.httpClients.length===1&&bindings.httpClients[0]===fixtureHttp&&
        Object.getPrototypeOf(bindings)===null,configValue:config.value};
    };
  `);
  await fs.writeFile(file, `
    globalThis.fixtureCalls=0;globalThis.fixtureInstalls=0;
    class Identity{get userInfo(){fixtureCalls++;throw Error('private-identity')}
      get userId(){fixtureCalls++;throw Error('private-identity')}
      get avatar(){fixtureCalls++;throw Error('private-identity')}}
    class Authentication{get(){fixtureCalls++}getAuthCookieHeader(){fixtureCalls++}
      setLoginCookies(){fixtureCalls++}clearLoginCookies(){fixtureCalls++}}
    class Network{getProxyConfig(){fixtureCalls++}getHttpProxy(){fixtureCalls++}
      getDNSProxy(){fixtureCalls++}setProxy(){fixtureCalls++}stopProxy(){fixtureCalls++}}
    class Receiver{receiveMessage(){fixtureCalls++}addMessageById(){fixtureCalls++}
      getMessageById(){fixtureCalls++}}
    class Sender{send(){fixtureCalls++}connect(){fixtureCalls++}
      onMessage(){fixtureCalls++}sendPresence(){fixtureCalls++}}
    class Factory{getProtoMsg(){fixtureCalls++}generateMsgProto(){fixtureCalls++}}
    globalThis.fixtureExpected={identity:new Identity,authentication:new Authentication,
      network:new Network,receiver:new Receiver,sender:new Sender,messageFactoryPrototype:Factory.prototype,
      sdk:{_sdk_getLoginStatus(){fixtureCalls++},_sdk_getNetworkStatus(){fixtureCalls++},
        _sdk_getUserInfo(){fixtureCalls++},_sdk_chatSocket_sendTextMessage(){fixtureCalls++}}};
    globalThis.fixtureHttp=new (require(${JSON.stringify(httpModule)}).enfetch);
    globalThis.fixtureReady=true;setInterval(()=>{},1000);
  `);
  const connection = await launchInspecting(process.execPath, [file]);
  const command = connection.client.command.bind(connection.client);
  let releases = 0;
  connection.client.command = (...args) => {
    if (args[0] === 'Runtime.releaseObjectGroup') releases++;
    return command(...args);
  };
  t.after(async () => {
    connection.client.close();connection.child.kill();
    await fs.rm(root, {recursive: true, force: true});
  });
  const deadline = Date.now() + 5000;
  while (!await evaluateRunning(connection, 'globalThis.fixtureReady===true')) {
    if (Date.now() >= deadline) throw Error('Fixture did not start');
    await new Promise(resolve => setTimeout(resolve, 10));
  }
  return {connection, modulePath, releases: () => releases};
}

test('binding requires a verified original and an absolute addon path before contacting the process', async () => {
  let calls = 0;
  const connection = {client: {command() {calls++;}}};
  for (const options of [{}, {modulePath: '/tmp/addon.cjs'},
    {modulePath: 'addon.cjs', pinnedOriginalVerified: true},
    {modulePath: '/tmp/addon.js', pinnedOriginalVerified: true}]) {
    await assert.rejects(bindNativeModule(connection, options), /Verified original/);
  }
  assert.equal(calls, 0);
});

test('captures actual target references without calling getters or business methods; releases handles', async t => {
  const {connection, modulePath, releases} = await fixture(t);
  const result = await bindNativeModule(connection, {
    modulePath, config: {value: 'offline'}, pinnedOriginalVerified: true,
  });
  assert.deepEqual(result, {ok: true, configValue: 'offline'});
  assert.equal(await evaluateRunning(connection, 'fixtureCalls'), 0);
  assert.equal(await evaluateRunning(connection, 'fixtureInstalls'), 1);
  assert.equal(JSON.stringify(result).includes('objectId'), false);
  assert.equal(releases(), 1);
});

test('ambiguous account instances stop before installer or getters', async t => {
  const {connection, modulePath, releases} = await fixture(t);
  await evaluateRunning(connection, 'globalThis.extraIdentity=new fixtureExpected.identity.constructor;true');
  await assert.rejects(bindNativeModule(connection, {modulePath, pinnedOriginalVerified: true}),
    error => error.nativeBindingDiagnostic.stage === 'capture-identity');
  assert.equal(await evaluateRunning(connection, 'fixtureCalls+fixtureInstalls'), 0);
  assert.equal(releases(), 1);
});

test('a second matching class stops prototype selection', async t => {
  const {connection, modulePath} = await fixture(t);
  await evaluateRunning(connection, `globalThis.DuplicateFactory=class{
    getProtoMsg(){throw Error('private')}generateMsgProto(){throw Error('private')}};true`);
  await assert.rejects(bindNativeModule(connection, {modulePath, pinnedOriginalVerified: true}),
    error => error.nativeBindingDiagnostic.stage === 'unique-role-prototypes');
  assert.equal(await evaluateRunning(connection, 'fixtureCalls+fixtureInstalls'), 0);
});

test('ambiguous SDK tables stop before addon installation', async t => {
  const {connection, modulePath} = await fixture(t);
  await evaluateRunning(connection, 'globalThis.extraSdk={...fixtureExpected.sdk};true');
  await assert.rejects(bindNativeModule(connection, {modulePath, pinnedOriginalVerified: true}),
    error => error.nativeBindingDiagnostic.stage === 'sdk-registry');
  assert.equal(await evaluateRunning(connection, 'fixtureCalls+fixtureInstalls'), 0);
});

test('too many HTTP clients are refused before installation', async t => {
  const {connection, modulePath} = await fixture(t);
  await evaluateRunning(connection,
    'globalThis.extraHttp=Array.from({length:8},()=>new fixtureHttp.constructor);true');
  await assert.rejects(bindNativeModule(connection, {modulePath, pinnedOriginalVerified: true}),
    error => error.nativeBindingDiagnostic.stage === 'original-http-client-prototype');
  assert.equal(await evaluateRunning(connection, 'fixtureCalls+fixtureInstalls'), 0);
});

test('installer failures are sanitized, not replayed, and still release handles', async t => {
  const {connection, modulePath, releases} = await fixture(t);
  await assert.rejects(bindNativeModule(connection, {
    modulePath, config: {throw: true}, pinnedOriginalVerified: true,
  }), error => {
    assert.equal(error.nativeBindingDiagnostic.stage, 'install-addon');
    assert.equal(String(error).includes('private-installer'), false);
    return true;
  });
  assert.equal(await evaluateRunning(connection, 'fixtureInstalls'), 1);
  assert.equal(await evaluateRunning(connection, 'fixtureCalls'), 0);
  assert.equal(releases(), 1);
});
