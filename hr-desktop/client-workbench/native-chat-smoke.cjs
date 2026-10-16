const assert = require('node:assert/strict');
const {BrowserWindow, session, webContents} = require('electron');
const {NativeChat} = require('./native-chat.cjs');
const {NativeRPC} = require('./native-rpc.cjs');

async function run() {
  const url = 'https://www.zhipin.com/web/client/index', partition = 'heyi-offline-chat-fixture';
  const target = session.fromPartition(partition);
  let blockedRequests = 0;
  const html = `<!doctype html><meta charset="utf-8">
    <div class="geek-list-wrapper"></div>
    <div id="editor" class="chat-conversation-operate">
      <div class="conversation-editor"><div class="boss-chat-editor-input"></div></div>
    </div><script>
    window.fixtureCalls = 0;
    window.fixtureSelections = 0;
    window.iBossRoot = {user: {get: () => '101'}};
    const conversation = {friendId: '202',
      toPositionId: 'job-fixture', securityId: 'fixture-security'};
    const currentData = {friendId: '202', encryptJobId: 'job-fixture'};
    const service = {bossId: '101', conversation, get: key => currentData[key],
      onMessageDelivered() {},
      sendMessage(content, type, options) {
        fixtureCalls++;
        // Provisional, wrong ID, then genuine delivery. Only the last one can
        // finish the operation. All three are local test events.
        service.onMessageDelivered([{fromId: '101', toId: '202', mid: '505',
          cmid: options.clientMid, type, text: content, newSubmit: true}]);
        service.onMessageDelivered([{fromId: '101', toId: '202', mid: '505',
          cmid: '999', type, text: content}]);
        setTimeout(() => service.onMessageDelivered([{fromId: '101', toId: '202', mid: '505',
          cmid: options.clientMid, type, text: content}]), 20);
      }};
    const original = service.onMessageDelivered;
    window.observerRestored = () => service.onMessageDelivered === original;
    const editor = {conversation$: {},
      $refs: {editbox: document.querySelector('.boss-chat-editor-input')},
      $root: {$children: [{$options: {name: 'dialog-nofit'}, source: 'conversation',
        getConversationService: () => service}]}};
    document.querySelector('#editor').__vue__ = editor;
    document.querySelector('.geek-list-wrapper').__vue__ = {$options: {name: 'geek-list'},
      geekClick(friend) {
        if (friend.friendId !== '202') throw Error('Wrong offline candidate');
        fixtureSelections++;editor.conversation$ = conversation;
      }};
    </script>`;
  await new Promise((resolve, reject) => target.protocol.interceptBufferProtocol(
    'https', (request, callback) => {
      if (request.url === url) callback({statusCode: 200, data: Buffer.from(html), mimeType: 'text/html'});
      else {blockedRequests++;callback({error: -10});}
    }, error => error ? reject(error) : resolve()));
  const win = new BrowserWindow({show: false, webPreferences: {
    webviewTag: true, nodeIntegration: false, contextIsolation: true, sandbox: true,
  }});
  win.name = 'main';
  let rpc;
  try {
    const attached = new Promise(resolve => win.webContents.once('did-attach-webview', (_event, guest) => resolve(guest)));
    await win.loadURL('data:text/html,' + encodeURIComponent(
      `<webview partition="${partition}" src="${url}" style="width:800px;height:600px"></webview>`));
    const guest = await attached;
    if (guest.isLoading()) await new Promise(resolve => guest.once('did-finish-load', resolve));
    const native = new NativeChat({nativeWindow: () => win, allContents: () => webContents.getAllWebContents()});
    rpc = new NativeRPC({endpoints: [], adapter: {
      user: () => ({userId: '101'}), loggedIn: () => true,
      sendText: input => native.perform({...input, clientMid: input.options.clientMid}),
    }});
    const receipt = await rpc.sendText('101', {friendId: '202', encryptJobId: 'job-fixture'}, 'Offline fixture only');
    assert.equal(receipt.messageId, '505');
    assert.equal(await guest.executeJavaScript('window.fixtureCalls'), 1);
    assert.equal(await guest.executeJavaScript('window.fixtureSelections'), 1);
    assert.equal(await guest.executeJavaScript('window.observerRestored()'), true);
    return {ok: true, actualElectronWebview: true, genuineDeliveryFixture: true,
      singleInvocation: true, currentNestedEditorLayout: true,
      renamedContactList: true, canonicalPositionVerified: true, observerRestored: true, blockedRequests,
      candidateMessagesSent: 0, actualBossNetworkUsed: false};
  } finally {
    await rpc?.close();
    win.destroy();
    target.protocol.uninterceptProtocol('https');
  }
}
module.exports = {run};
