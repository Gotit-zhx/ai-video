const test = require('node:test');
const assert = require('node:assert/strict');
const {EventEmitter} = require('node:events');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const vm = require('node:vm');
const {Recruiter} = require('../src/core.cjs');

async function fixture(t, failure, cloudReply = async () => ({})) {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'heyi-host-lifecycle-'));
  t.after(() => fs.rm(root, {recursive: true, force: true}));
  const counts = {rpcStarts: 0, rpcCloses: 0, relayCloses: 0,
    engineInits: 0, engineCloses: 0, bridgeCloses: 0,
    sessionStarts: 0, sessionCloses: 0, quits: 0};
  const app = new EventEmitter();
  app.whenReady = async () => {};
  app.getPath = name => path.join(root, name);
  app.quit = () => {counts.quits++;};
  let releaseSession;
  const sessionSaved = new Promise(resolve => {releaseSession = resolve;});
  class PlatformSession {
    async start() {counts.sessionStarts++;}
    async close() {
      counts.sessionCloses++;
      if (failure === 'slow-save') await sessionSaved;
    }
  }
  const egress = Object.assign(new EventEmitter(), {
    info: {adapter: {}}, close: async () => {counts.relayCloses++;},
    fetch: async (_session, url, options) => ({ok: true, status: 200,
      json: async () => ({success: true, data: await cloudReply(
        url.split('/').at(-1), JSON.parse(options.body))})}),
  });
  const network = {egress, configure: async () => {
    if (failure === 'network') throw Error('fixture relay failure');
    return egress;
  }};
  const integrated = {setNetworkState() {}, setAccountState() {}, configure() {}};
  class RPC extends EventEmitter {
    constructor() {super();this.url = 'http://127.0.0.1:1';}
    currentIdentity() {return this.account || '101';}
    async start() {counts.rpcStarts++;return this;}
    accountState() {return 'signed-out';}
    async close() {counts.rpcCloses++;}
  }
  class Engine extends Recruiter {
    async init() {counts.engineInits++;}
    async close() {this.clearActivity();counts.engineCloses++;}
  }
  class Bridge {
    constructor(options) {Object.assign(this, options);}
    async start() {if (failure === 'port') throw Error('fixture port occupied');}
    async close() {counts.bridgeCloses++;}
  }
  const dependencies = {
    electron: {app, Menu: {getApplicationMenu() {}, buildFromTemplate() {}, setApplicationMenu() {}},
      session: {fromPartition: () => ({})}},
    './native-rpc.cjs': {NativeRPC: RPC, identity: value => value},
    './src/core.cjs': {Recruiter: Engine, ORIGIN: 'https://hoyee.org.cn'},
    './src/native-runtime.cjs': {NativeRuntime: class {}},
    './native-network.cjs': {instance: () => network},
    './integrated-shell.cjs': {instance: () => integrated},
    './src/bridge.cjs': {LocalBridge: Bridge},
    './runtime-paths.cjs': {runtimeExecutable: () => '/own/offline-worker'},
    './platform-session.cjs': {PlatformSession},
    './endpoints.json': [],
  };
  const module = {exports: {}};
  vm.runInNewContext(await fs.readFile(path.join(__dirname, 'host.cjs'), 'utf8'), {
    module, process: {env: {}}, Promise, AbortSignal, setTimeout, clearTimeout,
    require: name => Object.hasOwn(dependencies, name) ? dependencies[name] : require(name),
  });
  return {host: module.exports, counts, app, egress, releaseSession, root};
}

test('initial native activity waits for the same-account HR restoration and retains warmup', async t => {
  const {host,root}=await fixture(t);
  await fs.mkdir(path.join(root,'userData','heyi'),{recursive:true});
  await fs.writeFile(path.join(root,'userData','heyi','native-binding.json'),JSON.stringify({id:'101'}));
  const running=await host.start({}),{engine,rpc}=running;
  t.after(()=>running.close());
  engine.paused=true;engine.state={state:'verification_pending'};
  const hint={friend:'202',mid:'300',at:Date.now()/1000,job:''};
  rpc.emit('activity',hint);rpc.emit('activity',{...hint,mid:'301'});
  assert.equal(engine.activity.size,0);
  engine.paused=false;engine.state={state:'complete'};
  engine.backgroundRetryAt=Date.now()+120000;
  engine.emit('change');
  assert.equal(engine.activity.get('202').mid,'301');
  assert.ok(engine.backgroundRetryAt>Date.now()+119000);
  engine.clearActivity();
  engine.paused=true;rpc.emit('activity',{...hint,mid:'302'});
  rpc.account='999';engine.paused=false;engine.emit('change');
  assert.equal(engine.activity.size,0,'Buffered activity must not cross accounts');
});
test('relay initialization failure closes the opened RPC and cannot return a partial host', async t => {
  const {host, counts, root} = await fixture(t, 'network');
  await assert.rejects(host.start({}), /fixture relay failure/);
  await assert.rejects(host.start({}), /fixture relay failure/);
  assert.equal(counts.rpcStarts, 2);
  assert.equal(counts.rpcCloses, 2);
  assert.equal(counts.relayCloses, 2);
  assert.equal(counts.engineInits, 0);
  const progress = JSON.parse(await fs.readFile(path.join(root, 'userData', 'heyi', 'startup-progress.json')));
  assert.equal(progress.stage, 'failed');
  assert.equal(progress.failedStage, 'direct-network');
  assert.equal(JSON.stringify(progress).includes('fixture relay failure'), false);
});

test('occupied bridge port never initializes existing shared records and releases all opened resources', async t => {
  const {host, counts, egress} = await fixture(t, 'port');
  await assert.rejects(host.start({}), /端口已被占用/);
  assert.equal(counts.engineInits, 0);
  assert.equal(counts.engineCloses, 1);
  assert.equal(counts.bridgeCloses, 1);
  assert.equal(counts.rpcCloses, 1);
  assert.equal(counts.relayCloses, 1);
  assert.equal(counts.sessionCloses, 1);
  assert.equal(egress.listenerCount('stopped'), 0);
});

test('normal application replacement waits for encrypted session persistence before quitting', async t => {
  const {host, counts, app, releaseSession} = await fixture(t, 'slow-save');
  const running = await host.start({});
  let prevented = false;
  app.emit('before-quit', {preventDefault() {prevented = true;}});
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(prevented, true);
  assert.equal(counts.sessionCloses, 1);
  assert.equal(counts.quits, 0);
  releaseSession();
  await running.close();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(counts.quits, 1);
  assert.equal(app.listenerCount('before-quit'), 0);
});

test('original forced-exit hook also waits for the same durable session save', async t => {
  const {host, counts, releaseSession} = await fixture(t, 'slow-save');
  await host.start({});
  let done = false;
  const closing = host.closeBeforeExit().then(() => {done = true;});
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(counts.sessionCloses, 1);
  assert.equal(done, false);
  releaseSession();
  await closing;
  assert.equal(done, true);
  assert.equal(counts.sessionCloses, 1);
});

test('concurrent startup awaits one complete host and repeated shutdown removes listeners only once', async t => {
  const {host, counts, app, egress} = await fixture(t);
  const [first, second] = await Promise.all([host.start({}), host.start({})]);
  assert.equal(first, second);
  assert.ok(first.engine && first.bridge && first.rpc && first.close);
  assert.equal(counts.rpcStarts, 1);
  assert.equal(counts.engineInits, 1);
  assert.equal(app.listenerCount('before-quit'), 1);
  await Promise.all([first.close(), first.close()]);
  assert.equal(counts.engineCloses, 1);
  assert.equal(counts.bridgeCloses, 1);
  assert.equal(counts.rpcCloses, 1);
  assert.equal(counts.relayCloses, 1);
  assert.equal(app.listenerCount('before-quit'), 0);
  assert.equal(egress.listenerCount('stopped'), 0);
});

test('a rejected second owner cannot replace the working owner lease', async t => {
  const users = {
    first: {id: 'owner-a', organizationCode: 'heyi', permissions: ['hr:access']},
    second: {id: 'owner-b', organizationCode: 'heyi', permissions: ['hr:access']},
  };
  const released = [];
  const {host} = await fixture(t, null, async (route, body) => {
    if (route === 'redeem') return {token: body.ticket, user: users[body.ticket],
      expiresAt: Date.now() / 1000 + 1800};
    if (route === 'lease') return {user: users[body.token]};
    if (route === 'release') {released.push(body.token);return {};}
    throw Error('Unexpected fixture route');
  });
  const running = await host.start({});
  t.after(() => running.close());
  await running.bridge.pair('first');
  const owner = await running.engine.checkOwner();
  await assert.rejects(async () => {
    await running.bridge.pair('second');
    await running.engine.checkOwner();
  }, /另一位用户/);
  assert.equal(await running.engine.checkOwner(), owner,
    'Rejected pairing must not poison authorization for the existing website');
  assert.deepEqual(released, ['second']);
  assert.equal(running.engine.owner, owner);
});
