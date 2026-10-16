const crypto = require('node:crypto');

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
  const current = currentEditor()?.conversation$;
  if (String(current?.friendId || '') !== args.friendId) list.geekClick(args.friend);
  const end = Date.now() + 5000;
  while (alive() && Date.now() < end) {
    const editor = currentEditor();
    const conversation = editor?.conversation$;
    if (String(conversation?.friendId || '') === args.friendId) {
      const job = String(conversation.encryptJobId || conversation.encJobId || '');
      if (job && job !== args.job) {
        return stop('JOB_CONVERSATION_MISMATCH', '当前会话职位不一致，未索取简历');
      }
      if (job === args.job && conversation.securityId) {
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
        return {ok: true};
      }
    }
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  return stop(alive() ? 'JOB_CONVERSATION_MISMATCH' : 'REQUESTS_PAUSED',
    alive() ? '原客户端未切换到指定候选人与职位' : '索取简历操作已停止');
}

async function commitPage(args) {
  const stop = (code, message) => ({ok: false, code, message});
  const state = window.__heyiResumeAction;
  const valid = () => {
    const conversation = state?.editor?.conversation$;
    return state && state.token === args.token && !state.cancelled &&
      window.__heyiResumeAction === state &&
      String(conversation?.friendId || '') === args.friendId &&
      String(conversation.encryptJobId || conversation.encJobId || '') === args.job &&
      !!conversation.securityId && String(conversation.securityId) === state.securityId;
  };
  if (!valid() || state.invoked) {
    return stop('JOB_CONVERSATION_MISMATCH', '会话已变化或操作已执行，未重复索取');
  }
  const component = state.component;
  if (!component?.$el?.getClientRects().length ||
      component.$el.getAttribute('aria-disabled') === 'true' ||
      component.disabled === true || component.loading === true ||
      (component.type != null && Number(component.type) !== 4)) {
    return stop('NATIVE_CHAT_UNAVAILABLE', '原客户端当前不允许索取简历');
  }
  state.invoked = true;
  // Let the original component do its own security checks and prepare its
  // normal confirmation. Never clear disabled/risk state or fabricate a reply.
  const result = component.handleExChange();
  if (result && typeof result.then === 'function') await result;
  const end = Date.now() + 2500;
  while (valid() && Date.now() < end) {
    const buttons = [];
    for (const panel of document.querySelectorAll('.exchange-tooltip, .popover, .ui-dialog')) {
      if (!panel.getClientRects().length) continue;
      const text = String(panel.innerText || '').replace(/\s+/g, ' ').trim();
      if (!/简历/.test(text) || !/索取|请求|发送|交换/.test(text) ||
          /验证码|安全验证|风控|操作频繁|访问异常|购买|支付|套餐|会员|升级|充值/.test(text)) continue;
      for (const button of panel.querySelectorAll('button, a[role="button"], .btn-primary')) {
        const label = String(button.innerText || '').trim();
        if (!/^(确定|确认|发送|发送请求|确认请求|索取简历|请求简历)$/.test(label) ||
            button.disabled || button.getAttribute('aria-disabled') === 'true' ||
            !button.getClientRects().length) continue;
        if (!buttons.includes(button)) buttons.push(button);
      }
    }
    if (buttons.length > 1) {
      return stop('NATIVE_CONFIRMATION_REQUIRED', '原客户端存在多个确认入口，请手动核对');
    }
    if (buttons.length === 1) {
      if (!valid()) break;
      buttons[0].click();
      return {ok: true, confirmed: true};
    }
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  return stop(valid() ? 'NATIVE_CONFIRMATION_REQUIRED' : 'REQUESTS_PAUSED',
    valid() ? '未找到原客户端的简历请求确认，请手动核对' : '会话已变化，已停止确认');
}

function cancelPage(token) {
  const state = window.__heyiResumeAction;
  if (state?.token === token) {
    state.cancelled = true;
    delete window.__heyiResumeAction;
  }
}

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
  async request({friend, checkAccount, signal, onReady}) {
    const friendId = String(friend.friendId || friend.uid || '');
    const job = String(friend.encryptJobId || friend.encJobId || '');
    if (!/^[1-9]\d{0,19}$/.test(friendId) || !job || job.length > 256) {
      throw fail('JOB_CONVERSATION_MISMATCH', '候选人与职位信息不完整');
    }
    checkAccount();
    const frame = this.frame();
    const args = {friend, friendId, job, token: crypto.randomBytes(24).toString('hex')};
    const execute = (fn, input) => {
      let valid = false;
      try {valid = isChatURL(frame.url);} catch {}
      if (!valid) {
        throw fail('NATIVE_CHAT_UNAVAILABLE', '原客户端聊天页面已变化');
      }
      return frame.executeJavaScript(`(${fn.toString()})(${JSON.stringify(input)})`);
    };
    const cancel = () => {
      try {void execute(cancelPage, args.token).catch(() => {});} catch {}
    };
    signal.addEventListener('abort', cancel, {once: true});
    const allowed = () => {
      checkAccount();
      if (signal.aborted) throw fail('REQUESTS_PAUSED', '索取简历操作已停止');
    };
    try {
      allowed();
      const prepared = await execute(preparePage, args);
      allowed();
      if (prepared?.ok !== true) {
        throw fail(prepared?.code || 'NATIVE_CHAT_UNAVAILABLE',
          prepared?.message || '原客户端会话准备未完成');
      }
      onReady();
      allowed();
      const result = await execute(commitPage, args);
      allowed();
      if (result?.ok !== true) {
        throw fail(result?.code || 'NATIVE_CONFIRMATION_REQUIRED',
          result?.message || '原客户端索取简历尚未确认');
      }
      return result;
    } finally {
      signal.removeEventListener('abort', cancel);
      cancel();
    }
  }
}

module.exports = {NativeResume, isChatURL, preparePage, commitPage};
