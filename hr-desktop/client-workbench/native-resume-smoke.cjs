const assert = require('node:assert/strict');
const {BrowserWindow, session, webContents} = require('electron');
const {NativeChat} = require('./native-chat.cjs');
const {NativeRPC} = require('./native-rpc.cjs');

async function run() {
  const url = 'https://www.zhipin.com/web/client/index';
  const partition = 'heyi-offline-resume-fixture';
  const target = session.fromPartition(partition);
  const fixture = `<!doctype html><meta charset="utf-8">
    <div class="geek-list-wrapper"></div>
    <div id="editor" class="chat-conversation-operate"><div><div class="boss-chat-editor-input"></div></div></div>
    <button id="resume">索取简历</button>
    <script>
      window.fixtureCalls = {selected: 0, opened: 0, requested: 0, textMessages: 0};
      window.iBossRoot = {user: {get: () => '101'}};
      const conversation = {friendId: '202', toPositionId: 'job-fixture',
        securityId: 'fixture', requestResume: 0};
      let currentData = {};
      const service = {bossId: '101', conversation, get: key => currentData[key],
        sendMessage() {fixtureCalls.textMessages++;throw Error('Exchange is not a text message');},
        onMessageDelivered() {}};
      const editor = {conversation$: {}, $refs: {editbox: document.querySelector('.boss-chat-editor-input')}};
      // Native adapter shape: the constructor returns an existing XHR with
      // own methods, while its public prototype has no open/send functions.
      const OriginalXHR = XMLHttpRequest;
      window.XMLHttpRequest = function() {
        const xhr = new OriginalXHR();
        xhr.open = xhr.open.bind(xhr);xhr.send = xhr.send.bind(xhr);
        return xhr;
      };
      const post = route => new Promise((resolve, reject) => {
        const xhr = new XMLHttpRequest();xhr.open('POST', route);
        xhr.setRequestHeader('Content-Type', 'application/x-www-form-urlencoded');
        xhr.onloadend = () => {try {resolve(JSON.parse(xhr.responseText));} catch(error) {reject(error);}};
        xhr.send('type=4&securityId=fixture');
      });
      const resume = {$options: {name: 'ExchangeResume'}, type: 4,
        isCanNormalChart: 1, isExchangeResumeBlueMsg: false, showTip: false,
        handleCheckOpen() {
          fixtureCalls.opened++;
          post('/wapi/zpchat/exchange/test').then(check => {
            if(check.code === 0 && check.zpData.status === 0) this.showTip = true;
          });
        },
        $el: document.querySelector('#resume'), handleExChange() {
          fixtureCalls.requested++;
          // Matches the current native component: this is already the
          // confirmation action; it checks, requests and updates the state.
          post('/wapi/zpchat/exchange/test').then(check => {
            if(check.code !== 0 || check.zpData.status !== 0) return;
            return post('/wapi/zpchat/exchange/request').then(result => {
              if(result.code === 0 && result.zpData.status === 0 && result.zpData.type === 4)
                conversation.requestResume = 1;
            });
          });
        }};
      editor.$children = [{$options: {name: 'Toolbar'}, $children: [resume]}];
      editor.$root = {$children: [editor, {$options: {name: 'dialog-nofit'},
        source: 'conversation', getConversationService: () => service}]};
      document.querySelector('#editor').__vue__ = editor;
      document.querySelector('.geek-list-wrapper').__vue__ = {$options: {name: 'geek-list'},
        geekClick(friend) {fixtureCalls.selected++;currentData = {...friend};editor.conversation$ = conversation;}};
    </script>`;
  const exchanges = {check: 0, request: 0};
  await new Promise((resolve, reject) => target.protocol.interceptBufferProtocol(
    'https', (request, callback) => {
      if (request.url === url) return callback({statusCode: 200, data: Buffer.from(fixture), mimeType: 'text/html'});
      const route = new URL(request.url);
      if (route.origin === 'https://www.zhipin.com' && request.method === 'POST' &&
          ['/wapi/zpchat/exchange/test', '/wapi/zpchat/exchange/request'].includes(route.pathname)) {
        exchanges[route.pathname.endsWith('/test') ? 'check' : 'request']++;
        return callback({statusCode: 200, mimeType: 'application/json',
          data: Buffer.from(JSON.stringify({code: 0, zpData: {status: 0, type: 4}}))});
      }
      callback({error: -10});
    }, error => error ? reject(error) : resolve()));
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
    const native = new NativeChat({nativeWindow: () => win,
      allContents: () => webContents.getAllWebContents()});
    assert.equal(native.frame().url, url);
    let originalCalls;
    rpc = new NativeRPC({endpoints: [], adapter: {
      user: () => ({userId: '101'}), loggedIn: () => true,
      requestResume: async input => {
        const result = await native.perform({...input, resume: true});
        originalCalls = await guest.executeJavaScript('window.fixtureCalls');
        assert.deepEqual(originalCalls, {selected: 1, opened: 1, requested: 1, textMessages: 0});
        return result;
      },
    }});
    const receipt = await rpc.requestResume('101',
      {friendId: '202', encryptJobId: 'job-fixture', securityId: 'fixture'});
    assert.equal(receipt.confirmedBy, 'native-exchange-response');
    assert.equal(receipt.messageId, undefined);
    assert.deepEqual(exchanges, {check: 2, request: 1});
    return {nativeGuestFrame: true, resumeComponentFixture: true,
      nativeHTTPExchangeFixture: true, nativeOpeningCheck: true,
      noSyntheticTextReceipt: true, singleConfirmation: true,
      resumeReceiptFixture: true, candidateMessagesSent: 0, actualBossNetworkUsed: false};
  } finally {
    await rpc?.close();
    win.destroy();
    target.protocol.uninterceptProtocol('https');
  }
}
module.exports = {run};
