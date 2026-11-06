const test = require('node:test');
const assert = require('node:assert/strict');
const {EventEmitter} = require('node:events');
const {NativeNetwork} = require('./native-network.cjs');

function fixture({failStart = false, failConfigure = false} = {}) {
  const app = new EventEmitter();
  app.whenReady = () => Promise.resolve();
  const target = {};
  const session = {defaultSession: target};
  const egress = new EventEmitter();
  let starts = 0, configurations = 0, closes = 0;
  egress.start = async () => {
    starts++;
    if (failStart) throw Error('fixture unavailable');
    egress.info = {port: 32123, secret: 'offline-relay-fixture'};
  };
  egress.configure = async supplied => {
    configurations++;
    if (failConfigure) throw Error('fixture configure unavailable');
    assert.equal(typeof supplied, 'object');
  };
  egress.authenticate = (...args) => args;
  egress.close = async () => {closes++;};
  const network = new NativeNetwork({app, session, egress});
  return {network, app, target, egress,
    counters: () => ({starts, configurations, closes})};
}

test('native external requests stop before NIC relay readiness', () => {
  const {network} = fixture();
  assert.throws(() => network.proxy(), /尚未就绪/);
});

test('native and system sessions share one relay without repeated configuration', async () => {
  const {network, target, counters} = fixture();
  const platform = {};
  await Promise.all([network.configure(target), network.configure(target), network.configure(platform)]);
  assert.deepEqual(counters(), {starts: 1, configurations: 2, closes: 0});
  assert.equal(network.proxy().proxy, 'http://127.0.0.1:32123');
});

test('vendor proxy controls cannot reenable a system proxy or persist relay credentials', async () => {
  const {network, counters} = fixture();
  let writes = 0;
  const service = {
    setProxy() {throw Error('must not use original network settings');},
    stopProxy() {throw Error('must not clear fixed NIC proxy');},
    storageService: {save() {writes++;}},
    getHttpProxyAgent() {return {route: this.getHttpProxy()};},
  };
  network.attachNative(service);
  await service.setProxy({useProxy: true, model: 'proxy', proxyConfig: {type: 'system'}});
  await service.stopProxy();
  assert.equal(service.getHttpProxyAgent().route.proxy, 'http://127.0.0.1:32123');
  assert.equal(service.getHttpProxyAgent().route.username, 'heyi');
  assert.equal(service.getDNSProxy(), undefined);
  assert.equal(service.getIPProxy('https://www.zhipin.com'), undefined);
  assert.equal(writes, 0);
  assert.equal(counters().configurations, 1);
});

test('relay start failure has no direct or system fallback and no repeated startup', async () => {
  const {network, target, counters} = fixture({failStart: true});
  await assert.rejects(network.configure(target), /fixture unavailable/);
  await assert.rejects(network.configure(target), /fixture unavailable/);
  assert.throws(() => network.proxy(), /尚未就绪/);
  assert.deepEqual(counters(), {starts: 1, configurations: 0, closes: 0});
});

test('session configuration failure is retained until restart', async () => {
  const {network, target, counters} = fixture({failConfigure: true});
  await assert.rejects(network.configure(target), /configure unavailable/);
  await assert.rejects(network.configure(target), /configure unavailable/);
  assert.equal(counters().configurations, 1);
});

test('relay loss stops future native requests', async () => {
  const {network, target, egress} = fixture();
  await network.configure(target);
  egress.emit('stopped');
  assert.throws(() => network.proxy(), /尚未就绪/);
  await assert.rejects(network.configure(target), /已停止/);
});

test('quit closes the one shared relay', async () => {
  const {network, target, app, counters} = fixture();
  await network.configure(target);
  app.emit('before-quit');
  assert.equal(counters().closes, 1);
  assert.throws(() => network.proxy(), /尚未就绪/);
});
