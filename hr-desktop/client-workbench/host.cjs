const {app, Menu, session} = require('electron');
const fs = require('node:fs/promises');
const path = require('node:path');
const crypto = require('node:crypto');
const {NativeRPC, identity} = require('./native-rpc.cjs');
const {Recruiter, ORIGIN} = require('./src/core.cjs');
const {NativeRuntime} = require('./src/native-runtime.cjs');
const {instance: networkInstance} = require('./native-network.cjs');
const {instance: shellInstance} = require('./integrated-shell.cjs');
const {LocalBridge} = require('./src/bridge.cjs');
const {storagePaths} = require('./shared-state.cjs');
const {runtimeExecutable} = require('./runtime-paths.cjs');

let active;
async function start(adapter) {
  if (active) return active;
  await app.whenReady();
  const {nativeRoot: root, assistantRoot, recruiterDirectory, deviceFile} = storagePaths(app);
  await fs.mkdir(root, {recursive: true, mode: 0o700});
  await fs.mkdir(assistantRoot, {recursive: true, mode: 0o700});
  const executable = runtimeExecutable();
  const rpc = await new NativeRPC({
    adapter, endpoints: require('./endpoints.json'),
  }).start();
  active = {rpc};
  let lease, checkedAt = 0, engine, boundAccount = '', ready = false;
  let device;
  try {device = JSON.parse(await fs.readFile(deviceFile, 'utf8')).id;} catch {}
  if (!/^[a-f0-9-]{36}$/.test(device || '')) {
    device = crypto.randomUUID();
    await fs.writeFile(deviceFile, JSON.stringify({id: device}), {mode: 0o600});
  }
  const bindingFile = path.join(root, 'native-binding.json');
  try {boundAccount = identity(JSON.parse(await fs.readFile(bindingFile, 'utf8')).id);} catch {}
  const platform = session.fromPartition('persist:heyi-workbench');
  const egress = await networkInstance().configure(platform);
  const integrated = shellInstance();
  integrated.setNetworkState('ready');
  integrated.setAccountState(rpc.accountState());
  rpc.on('session-status', state => integrated.setAccountState(state));
  egress.once('stopped', () => integrated.setNetworkState('stopped'));
  async function cloud(route, body) {
    const reply = await egress.fetch(platform, `${ORIGIN}/api/v1/hr/desktop/${route}`, {
      method: 'POST', body: JSON.stringify(body),
      headers: {'Content-Type': 'application/json', Origin: ORIGIN},
      redirect: 'error', signal: AbortSignal.timeout(route === 'passive-ingest' ? 90000 : 10000),
    });
    const result = await reply.json();
    if (!reply.ok || !result.success) {
      throw Object.assign(Error(result.message || '平台连接未完成'), {
        code: [401, 403, 409].includes(reply.status) ? 'AUTHORITY_INVALID' : 'PLATFORM_UNAVAILABLE',
      });
    }
    return result.data;
  }
  async function authorize() {
    if (!ready) throw Error('赫逸工作台正在读取已有记录，请稍后');
    if (!lease || lease.expiresAt * 1000 <= Date.now()) throw Error('请在赫逸工作台连接本机助手');
    if (Date.now() - checkedAt < 15000) return lease.user;
    const result = await cloud('lease', {token: lease.token, device});
    checkedAt = Date.now();
    return result.user;
  }
  async function release() {
    const previous = lease;
    lease = null;
    if (previous) try {await cloud('release', {token: previous.token, device});} catch {}
  }
  function openWorkbench() {
    integrated.showWorkbench();
  }
  const worker = new NativeRuntime(executable, egress.info.adapter, rpc, () => boundAccount);
  engine = new Recruiter({
    directory: recruiterDirectory, runtime: worker, authorize,
    passiveCloud: async (route, body) => {
      await authorize();
      return cloud(route, {...body, token: lease.token, device});
    },
    browser: {
      open: async () => {adapter.show();},
      restore: async () => {},
      chat: async () => {adapter.show();},
      prepare: async () => {},
      inspect: async () => {
        const id = rpc.currentIdentity();
        if (typeof adapter.verifyAccount === 'function') {
          try {await adapter.verifyAccount(id);}
          catch (error) {
            if (error.code === 'AUTH_REQUIRED') return {text: '', token: null};
            throw error;
          }
        }
        if (!id || !adapter.loggedIn()) return {text: '', token: null};
        if (boundAccount && boundAccount !== id) {
          await engine.hold({code: 'SESSION_MISMATCH'});
          return {text: '', token: null};
        }
        const snapshot = await rpc.refreshSession(id);
        boundAccount = id;
        await fs.writeFile(bindingFile, JSON.stringify({id}), {mode: 0o600});
        const cookies = {};
        for (const cookie of snapshot.cookies) {
          const domain = String(cookie.domain).replace(/^\./, '').toLowerCase();
          if (domain === 'zhipin.com' || domain.endsWith('.zhipin.com')) cookies[cookie.name] = cookie.value;
        }
        return {text: '', accountId: id, token: {
          cookies, stoken: cookies.__zp_stoken__ || '', user_agent: snapshot.userAgent,
        }};
      },
      qr: async () => {throw Error('请在原客户端完成扫码登录');},
      refresh: async () => {adapter.show();},
      connected: () => {},
      clear: async () => {
        // Disconnect the HR integration without logging out the original client.
        boundAccount = '';
        await fs.rm(bindingFile, {force: true});
      },
    },
  });
  const bridge = new LocalBridge({
    engine, authorize, release,
    pair: async ticket => {
      lease = await cloud('redeem', {ticket, device});
      checkedAt = 0;
      return lease;
    },
  });
  const offlinePort = process.env.HEYI_WORKBENCH_TEST_DATA &&
    process.env.HEYI_WORKBENCH_TEST_PORT === '0' ? 0 : undefined;
  try {await bridge.start(offlinePort);}
  catch (error) {
    // No shared engine state has been opened or written yet.
    await rpc.close();await egress.close();
    throw Object.assign(Error('本机助手端口已被占用，请完整退出旧助手后打开改造版'), {cause: error});
  }
  try {await engine.init(rpc.url);ready = true;}
  catch (error) {await bridge.close();await rpc.close();await egress.close();throw error;}
  // The existing website starts its connection check as soon as it renders.
  // Load it only after the loopback listener and owner state are both ready.
  // This avoids racing its initial health/pair requests against startup.
  integrated.configure({session: platform, url: ORIGIN + '/?lang=zh#hr-overview', origin: ORIGIN});
  rpc.on('activity', hint => {
    if (rpc.currentIdentity() === boundAccount) engine.notifyActivity([hint]);
  });
  rpc.on('session-lost', code => {
    void engine.hold({code});
  });
  egress.on('stopped', () => engine.pause('DIRECT_NETWORK_UNAVAILABLE'));
  const current = Menu.getApplicationMenu();
  const template = current?.items.map(item => item) || [];
  template.push({label: '赫逸工作台', submenu: [
    {label: '打开人力资源工作台', accelerator: 'CommandOrControl+Shift+H', click: openWorkbench},
    {label: '打开 BOSS 客户端', click: () => adapter.show()},
    {type: 'separator'},
    {label: '暂停赫逸任务', click: () => engine.pause('USER_PAUSED')},
  ]});
  Menu.setApplicationMenu(Menu.buildFromTemplate(template));
  app.on('before-quit', () => {void engine.close();void bridge.close();void rpc.close();void egress.close();});
  active = {...active, engine, bridge, egress, openWorkbench};
  return active;
}
module.exports = {start, receive: messages => active?.rpc.receive(messages),
  observeNativeSend: message => active?.rpc.observeNativeSend(message),
  loginEstablished: () => active?.rpc.loginEstablished(),
  sessionLost: event => {
    const code = ['AUTH_EXPIRED', 'SESSION_MISMATCH', 'ACCOUNT_RISK',
      'SECURITY_VERIFICATION_REQUIRED'].includes(event?.code) ? event.code :
      ({10: 'SESSION_MISMATCH', 70: 'ACCOUNT_RISK',
      1013: 'ACCOUNT_RISK', 1014: 'SECURITY_VERIFICATION_REQUIRED'})[Number(event?.code)]
      || 'AUTH_EXPIRED';
    active?.rpc.sessionLost(code);
  }};
