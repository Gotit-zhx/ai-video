const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const os = require('node:os');
const {launchInspecting,evaluateRunning} = require('./inspector-driver.cjs');
const {inspectNativeRoles} = require('./native-role-inventory.cjs');

async function fixture(t, source) {
  const root = await fs.mkdtemp(path.join(os.tmpdir(),'heyi-native-role-'));
  const file = path.join(root,'fixture.cjs');
  await fs.writeFile(file,source+'\nglobalThis.fixtureReady=true;setInterval(()=>{},1000);');
  const connection = await launchInspecting(process.execPath,[file]);
  t.after(async()=>{
    connection.client.close();connection.child.kill();
    await fs.rm(root,{recursive:true,force:true});
  });
  const deadline=Date.now()+5000;
  while(!await evaluateRunning(connection,'globalThis.fixtureReady===true')){
    if(Date.now()>=deadline)throw Error('fixture startup failed');
    await new Promise(resolve=>setTimeout(resolve,10));
  }
  return connection;
}

test('native role diagnostics require an explicitly isolated anonymous process', async()=>{
  let calls=0;
  await assert.rejects(inspectNativeRoles({client:{command(){calls++}}}),/Anonymous isolated/);
  assert.equal(calls,0);
});

test('discovers singleton shapes without reading their getters or invoking methods', async t=>{
  const connection=await fixture(t,`
globalThis.fixtureCalls=0;
class Identity{get userInfo(){fixtureCalls++;throw Error('private data')}
 get userId(){fixtureCalls++;throw Error('private data')}
 get avatar(){fixtureCalls++;throw Error('private data')}}
class Network{getProxyConfig(){fixtureCalls++}getHttpProxy(){fixtureCalls++}
 getDNSProxy(){fixtureCalls++}setProxy(){fixtureCalls++}stopProxy(){fixtureCalls++}}
class Cookies{get(){fixtureCalls++}getZpToken(){fixtureCalls++}
 setLoginCookies(){fixtureCalls++}clearLoginCookies(){fixtureCalls++}}
class Receiver{receiveMessage(){fixtureCalls++}addMessageById(){fixtureCalls++}
 getMessageById(){fixtureCalls++}}
class Sender{send(){fixtureCalls++}connect(){fixtureCalls++}
 onMessage(){fixtureCalls++}sendPresence(){fixtureCalls++}}
class Factory{getProtoMsg(){fixtureCalls++}generateMsgProto(){fixtureCalls++}}
globalThis.fixtureKeepAlive=[new Identity,new Network,new Cookies,new Receiver,new Sender,Factory];
`);
  const report=await inspectNativeRoles(connection,{anonymousIsolated:true});
  assert.equal(report.singletonsAvailable,true);
  for(const role of ['identity','network','cookies','receiver','sender']){
    assert.equal(report.roles[role].uniqueSingleton,true,role);
    assert.deepEqual(report.roles[role].instanceCounts,[1],role);
  }
  assert.deepEqual(report.roles.messageFactory.instanceCounts,[0]);
  assert.equal(await evaluateRunning(connection,'fixtureCalls'),0);
  assert.equal(report.originalMethodsInvoked,0);
  assert.equal(JSON.stringify(report).includes('objectId'),false);
});

test('multiple instances remain ambiguous, and missing roles remain unavailable', async t=>{
  const connection=await fixture(t,`
class Receiver{receiveMessage(){}addMessageById(){}getMessageById(){}}
globalThis.fixtureKeepAlive=[new Receiver,new Receiver];
`);
  const report=await inspectNativeRoles(connection,{anonymousIsolated:true});
  assert.deepEqual(report.roles.receiver.instanceCounts,[2]);
  assert.equal(report.roles.receiver.uniqueSingleton,false);
  assert.equal(report.roles.identity.prototypeMatches,0);
  assert.equal(report.singletonsAvailable,false);
});
