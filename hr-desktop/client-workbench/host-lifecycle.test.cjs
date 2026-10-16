const test = require('node:test');
const assert = require('node:assert/strict');
const {EventEmitter} = require('node:events');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const vm = require('node:vm');

async function fixture(t, failure) {
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
  });
  const network = {egress, configure: async () => {
    if (failure === 'network') throw Error('fixture relay failure');
    return egress;
  }};
  const integrated = {setNetworkState() {}, setAccountState() {}, configure() {}};
  class RPC extends EventEmitter {
    constructor() {super();this.url = 'http://127.0.0.1:1';}
    async start() {counts.rpcStarts++;return this;}
    accountState() {return 'signed-out';}
    async close() {counts.rpcCloses++;}
  }
  class Engine {
    async init() {counts.engineInits++;}
    async close() {counts.engineCloses++;}
  }
  class Bridge {
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
  return {host: module.exports, counts, app, egress, releaseSession};
}

test('relay initialization failure closes the opened RPC and cannot return a partial host', async t => {
  const {host, counts} = await fixture(t, 'network');
  await assert.rejects(host.start({}), /fixture relay failure/);
  await assert.rejects(host.start({}), /fixture relay failure/);
  assert.equal(counts.rpcStarts, 2);
  assert.equal(counts.rpcCloses, 2);
  assert.equal(counts.relayCloses, 2);
  assert.equal(counts.engineInits, 0);
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
