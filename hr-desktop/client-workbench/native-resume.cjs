const CHAT_PATHS = new Set(['/web/client/index', '/web/chat/index', '/web/boss/index']);
const fail = (code, message) => Object.assign(Error(message), {code});

function isChatURL(value) {
  try {
    const url = new URL(value);
    return url.origin === 'https://www.zhipin.com' &&
      !url.username && !url.password && CHAT_PATHS.has(url.pathname);
  } catch {return false;}
}

// These functions execute only in the original client's chat webview. They
// select the real conversation and invoke its own resume exchange component.
// No copied HTTP protocol, synthetic message, or arbitrary caller JS is used.
async function preparePage(args) {
  const stop = (code, message) => ({ok: false, code, message});
  const old = window.__heyiResumeAction;
  if (old) old.cancelled = true;
  const state = {token: args.token, cancelled: false, invoked: false};
  window.__heyiResumeAction = state;
  const alive = () => !state.cancelled && window.__heyiResumeAction === state;
  const list = document.querySelector('.geek-list-wrapper')?.__vue__ ||
    document.querySelector('.chat-user')?.__vue__;
  if (!list || list.$options?.name !== 'geek-list' || typeof list.geekClick !== 'function') {
    return stop('NATIVE_CHAT_UNAVAILABLE', '原客户端聊天列表尚未就绪');
  }
  const currentEditor = () => document.querySelector('.chat-conversation-operate')?.__vue__ ||
    document.querySelector('.boss-chat-editor-input')?.parentElement?.__vue__;
  const jobValues = conversation =>
    [conversation?.toPositionId, conversation?.encryptJobId, conversation?.encJobId]
      .filter(value => value !== undefined && value !== null && value !== '').map(String);
  const matchesJob = conversation => {
    const jobs = jobValues(conversation);
    return jobs.length > 0 && jobs.every(job => job === args.job);
  };
  const current = currentEditor()?.conversation$;
  if (String(current?.friendId || '') !== args.friendId) list.geekClick(args.friend);
  const end = Date.now() + 5000;
  while (alive() && Date.now() < end) {
    const editor = currentEditor();
    const conversation = editor?.conversation$;
    if (String(conversation?.friendId || '') === args.friendId && !editor.conversationLoading$) {
      if (jobValues(conversation).length && !matchesJob(conversation)) {
        return stop('JOB_CONVERSATION_MISMATCH', '当前会话职位不一致，未索取简历');
      }
      if (matchesJob(conversation) && conversation.securityId) {
        const queue = [editor.$root || editor];
        const seen = new Set(), found = [];
        while (queue.length && seen.size < 6000) {
          const vm = queue.shift();
          if (!vm || seen.has(vm)) continue;
          seen.add(vm);
          queue.push(...(vm.$children || []));
          const name = vm.$options?.name || vm.$options?._componentTag;
          if (name === 'ExchangeResume' && typeof vm.handleExChange === 'function' &&
              vm.$el?.getClientRects().length && vm.$el.getAttribute('aria-disabled') !== 'true') {
            found.push(vm);
          }
        }
        if (found.length !== 1) {
          return stop('NATIVE_CHAT_UNAVAILABLE', '无法唯一确定原客户端的索取简历入口');
        }
        const vm = found[0];
        if (vm.type != null && Number(vm.type) !== 4) {
          return stop('NATIVE_CHAT_UNAVAILABLE', '原客户端交换类型与附件简历不一致');
        }
        state.editor = editor;
        state.component = vm;
        state.securityId = String(conversation.securityId);
        state.matchesJob = matchesJob;
        return {ok: true};
      }
    }
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  return stop(alive() ? 'JOB_CONVERSATION_MISMATCH' : 'REQUESTS_PAUSED',
    alive() ? '原客户端未切换到指定候选人与职位' : '索取简历操作已停止');
}

// Shared frame locator only. All writes go through NativeChat's single
// account/job-checked action and actual delivery or exchange confirmation.
class NativeResume {
  constructor({nativeWindow, allContents}) {
    this.nativeWindow = nativeWindow;
    this.allContents = allContents;
  }
  frame() {
    const win = this.nativeWindow();
    if (!win || win.isDestroyed() || win.name !== 'main') {
      throw fail('NATIVE_CHAT_UNAVAILABLE', '请先在原客户端完成 BOSS 登录');
    }
    // An unrelated website or our HR BrowserView cannot become the action
    // target. Only a webview owned by this actual native main window qualifies.
    const candidates = [];
    for (const contents of this.allContents()) {
      if (contents.isDestroyed() || contents.getType() !== 'webview' ||
          contents.hostWebContents !== win.webContents) continue;
      for (const frame of contents.mainFrame.framesInSubtree) {
        try {if (isChatURL(frame.url)) candidates.push(frame);} catch {}
      }
    }
    if (candidates.length !== 1) {
      throw fail('NATIVE_CHAT_UNAVAILABLE', '原客户端聊天页面不唯一或尚未加载');
    }
    return candidates[0];
  }
}

module.exports = {NativeResume, isChatURL, preparePage};
