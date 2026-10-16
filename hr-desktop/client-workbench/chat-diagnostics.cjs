const {app, BrowserWindow, webContents} = require('electron');
const fs = require('node:fs');
const {NativeChat} = require('./native-chat.cjs');

// Explicit local diagnostic mode. Inspect only the currently loaded page:
// no navigation, account data, message text, sends or network requests.
function inspectPage() {
  const input = document.querySelector('.boss-chat-editor-input');
  const editor = document.querySelector('.chat-conversation-operate')?.__vue__ ||
    input?.parentElement?.__vue__;
  const list = document.querySelector('.geek-list-wrapper')?.__vue__ ||
    document.querySelector('.chat-user')?.__vue__;
  const ancestors = [];
  for (let node = input, depth = 0; node && depth < 6; node = node.parentElement, depth++) {
    const name = node.__vue__?.$options?.name || node.__vue__?.$options?._componentTag || '';
    ancestors.push({depth, vue: !!node.__vue__,
      name: /^[a-z\d_-]{1,80}$/i.test(name) ? name : null,
      conversation: !!node.__vue__?.conversation$});
  }
  const names = new Set(), services = new Set(), seen = new Set(), queue = [editor?.$root,
    list?.$root,
    ...Array.from(document.querySelectorAll('.not-fit-wrap')).map(node => node.__vue__)];
  while (queue.length && seen.size < 6000) {
    const item = queue.shift();
    if (!item || seen.has(item)) continue;
    seen.add(item);
    queue.push(...(item.$children || []));
    if (['dialog-nofit', 'tooltip-nofit'].includes(item.$options?.name || item.$options?._componentTag) &&
        item.source !== 'global' && typeof item.getConversationService === 'function') {
      names.add(item.$options.name);services.add(item.getConversationService());
    }
  }
  const service = services.size === 1 ? [...services][0] : null;
  const conversation = editor?.conversation$;
  return {editor: !!editor, editorName: editor?.$options?.name || null, ancestors,
    inputFound: !!document.querySelector('.boss-chat-editor-input'),
    listName: list?.$options?.name || null,
    components: [...names], uniqueService: services.size === 1,
    sendMethod: typeof service?.sendMessage === 'function',
    deliveredCallback: typeof service?.onMessageDelivered === 'function',
    accountMatched: !!service?.bossId &&
      String(service.bossId) === String(window.iBossRoot?.user?.get('userId') || ''),
    candidateMatched: !!conversation?.friendId &&
      String(conversation.friendId) === String(service?.get?.('friendId') || ''),
    jobPresent: !!(conversation?.encryptJobId || conversation?.encJobId),
    positionPresent: !!conversation?.toPositionId, securityContextPresent: !!conversation?.securityId,
    nativeConversationPresent: !!service?.conversation,
    nativeSecurityMatched: !!conversation?.securityId &&
      String(conversation.securityId) === String(service?.conversation?.securityId || ''),
    loading: !!editor?.conversationLoading$};
}

async function run() {
  const output = process.env.HEYI_WORKBENCH_DIAGNOSTICS_OUTPUT;
  if (!output) return;
  await app.whenReady();
  const native = new NativeChat({nativeWindow: () => BrowserWindow.getAllWindows().find(win => win.name === 'main'),
    allContents: () => webContents.getAllWebContents()});
  const started = Date.now();
  async function inspect() {
    let result;
    try {
      const frame = native.frame();
      result = await frame.executeJavaScript(`(${inspectPage.toString()})()`);
    } catch (error) {result = {available: false, code: error.code || 'CHAT_NOT_READY'};}
    fs.writeFileSync(output, JSON.stringify({at: new Date().toISOString(), readOnly: true,
      candidateMessagesSent: 0, electron: process.versions.electron, ...result}, null, 2));
    if (!result.uniqueService && Date.now() - started < 600000) setTimeout(inspect, 2000);
  }
  void inspect();
}
module.exports = {run, inspectPage};
