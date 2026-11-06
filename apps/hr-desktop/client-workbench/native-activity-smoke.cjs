const assert = require('node:assert/strict');
const {BrowserWindow, session, webContents} = require('electron');
const {NativeActivity} = require('./native-activity.cjs');
const {NativeRPC} = require('./native-rpc.cjs');

async function run() {
  const url = 'https://www.zhipin.com/web/client/index', partition = 'heyi-offline-incoming-fixture';
  const target = session.fromPartition(partition);
  let blockedRequests = 0;
  const fixture = `<!doctype html><meta charset="utf-8"><script>
    window.originalCalls = 0;
    window.iBossRoot = {user: {get: () => '101'}, messageStore: {
      add(rows) {window.originalCalls++;return rows.length;}
    }};
    window.originalAdd = window.iBossRoot.messageStore.add;
  </script>`;
  await new Promise((resolve, reject) => target.protocol.interceptBufferProtocol(
    'https', (request, callback) => {
      if (request.url === url) return callback({statusCode: 200, data: Buffer.from(fixture), mimeType: 'text/html'});
      blockedRequests++;callback({error: -10});
    }, error => error ? reject(error) : resolve()));
  const win = new BrowserWindow({show: false, webPreferences: {
    webviewTag: true, nodeIntegration: false, contextIsolation: true, sandbox: true,
  }});
  win.name = 'main';
  let observer, rpc;
  try {
    const attached = new Promise(resolve => win.webContents.once(
      'did-attach-webview', (_event, contents) => resolve(contents)));
    await win.loadURL('data:text/html,' + encodeURIComponent(
      `<webview partition="${partition}" src="${url}" style="width:800px;height:600px"></webview>`));
    const guest = await attached;
    if (guest.isLoading()) await new Promise(resolve => guest.once('did-finish-load', resolve));
    rpc = new NativeRPC({endpoints: [], adapter: {user: () => ({userId: '101'}), loggedIn: () => true}});
    const hints = [];
    rpc.on('activity', hint => hints.push(hint));
    observer = new NativeActivity({nativeWindow: () => win,
      allContents: () => webContents.getAllWebContents(), receive: rows => rpc.receive(rows)});
    observer.start();
    const until = async predicate => {
      const deadline = Date.now()+3000;
      while (!await predicate()) {
        if (Date.now() > deadline) throw Error('Incoming page observer fixture did not settle');
        await new Promise(resolve => setTimeout(resolve, 20));
      }
    };
    await until(() => guest.executeJavaScript('!!window.__heyiIncomingActivity'));
    assert.equal(await guest.executeJavaScript(`window.iBossRoot.messageStore.add([
      {from: {uid:'202'},to:{uid:'101'},mid:'301',time:Date.now(),body:{type:1,text:'fixture incoming reply'}},
      {from: {uid:'202'},to:{uid:'101'},mid:'302',time:Date.now(),body:{type:4}},
      {from: {uid:'101'},to:{uid:'202'},mid:'303',time:Date.now(),body:{type:1}}
    ])`), 3);
    await until(() => hints.length === 2);
    assert.deepEqual(hints.map(h => [h.friend, h.mid]), [['202','301'],['202','302']]);
    await observer.close();
    assert.equal(await guest.executeJavaScript(
      'window.originalCalls === 1 && window.iBossRoot.messageStore.add === window.originalAdd && !window.__heyiIncomingActivity'), true);
    assert.equal(blockedRequests, 0);
    return {ok: true, actualElectronWebview: true, incomingReplyAndAttachmentHints: true,
      outgoingIgnored: true, nativeStorePreserved: true, observerRestored: true,
      additionalBossRequests: 0, candidateMessagesSent: 0, actualBossNetworkUsed: false};
  } finally {
    await observer?.close();await rpc?.close();win.destroy();
    target.protocol.uninterceptProtocol('https');
  }
}
module.exports = {run};
