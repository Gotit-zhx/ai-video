const assert = require('node:assert/strict');
const {BrowserWindow, session, webContents} = require('electron');
const {NativeResume} = require('./native-resume.cjs');
const {NativeRPC, RESUME_REQUEST_TEXT} = require('./native-rpc.cjs');

async function run() {
  const url = 'https://www.zhipin.com/web/client/index';
  const partition = 'heyi-offline-resume-fixture';
  const target = session.fromPartition(partition);
  const fixture = `<!doctype html><meta charset="utf-8">
    <div class="chat-user"></div><div id="editor"><div class="boss-chat-editor-input"></div></div>
    <button id="resume">索取简历</button>
    <script>
      window.fixtureCalls = {selected: 0, requested: 0, confirmed: 0};
      const editor = {$children: []};
      const resume = {$options: {name: 'ExchangeResume'}, type: 4,
        $el: document.querySelector('#resume'), handleExChange() {
          fixtureCalls.requested++;
          const panel = document.createElement('div');
          panel.className = 'ui-dialog';
          panel.innerHTML = '<p>向候选人请求附件简历</p><button>确定</button>';
          panel.querySelector('button').onclick = () => {fixtureCalls.confirmed++;panel.remove();};
          document.body.appendChild(panel);
        }};
      editor.$children.push(resume);
      document.querySelector('#editor').__vue__ = editor;
      document.querySelector('.chat-user').__vue__ = {$options: {name: 'geek-list'},
        geekClick(friend) {fixtureCalls.selected++;editor.conversation$ = {...friend};}};
    </script>`;
  await new Promise((resolve, reject) => target.protocol.interceptBufferProtocol(
    'https', (request, callback) => callback(request.url === url
      ? {statusCode: 200, data: Buffer.from(fixture), mimeType: 'text/html'}
      : {error: -10}), error => error ? reject(error) : resolve()));
  const win = new BrowserWindow({show: false, webPreferences: {
    webviewTag: true, nodeIntegration: false, contextIsolation: true, sandbox: true,
  }});
  win.name = 'main';
  let rpc;
  try {
    const attached = new Promise(resolve => win.webContents.once(
      'did-attach-webview', (_event, contents) => resolve(contents)));
    await win.loadURL('data:text/html,' + encodeURIComponent(
      `<webview partition="${partition}" src="${url}" style="width:800px;height:600px"></webview>`));
    const guest = await attached;
    if (guest.isLoading()) await new Promise(resolve => guest.once('did-finish-load', resolve));
    const native = new NativeResume({nativeWindow: () => win,
      allContents: () => webContents.getAllWebContents()});
    assert.equal(native.frame().url, url);
    let originalCalls;
    rpc = new NativeRPC({endpoints: [], adapter: {
      user: () => ({userId: '101'}), loggedIn: () => true,
      requestResume: async input => {
        const result = await native.request(input);
        originalCalls = await guest.executeJavaScript('window.fixtureCalls');
        assert.deepEqual(originalCalls, {selected: 1, requested: 1, confirmed: 1});
        rpc.observeNativeSend({messages: [{from: {uid: '101'}, to: {uid: '202'}, cmid: '404',
          body: {type: 1, text: RESUME_REQUEST_TEXT}}]});
        rpc.receive([{fromId: '101', toId: '202', isSelf: true, mid: '505',
          clientMid: '404', type: 'text', status: 1, time: Date.now(), text: RESUME_REQUEST_TEXT}]);
        return result;
      },
    }});
    const receipt = await rpc.requestResume('101',
      {friendId: '202', encryptJobId: 'job-fixture', securityId: 'fixture'});
    assert.equal(receipt.confirmedBy, 'client-message-receipt');
    return {nativeGuestFrame: true, resumeComponentFixture: true,
      resumeReceiptFixture: true, candidateMessagesSent: 0, actualBossNetworkUsed: false};
  } finally {
    await rpc?.close();
    win.destroy();
    target.protocol.uninterceptProtocol('https');
  }
}
module.exports = {run};
