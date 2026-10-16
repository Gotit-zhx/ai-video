// Observe only the original page's own resume exchange request. We never
// construct or replay that request, replace its response, or synthesize a
// text-message delivery receipt for an HTTP exchange.
function prepareExchangePage(args) {
  const state = window.__heyiChatAction, resume = window.__heyiResumeAction;
  const stop = (code, message) => ({ok: false, code, message});
  if (!state || state.token !== args.token || !state.valid() ||
      !resume || resume.token !== args.token || !resume.component ||
      ![true, 1].includes(resume.component.isCanNormalChart) ||
      typeof resume.component.handleCheckOpen !== 'function' ||
      resume.component.isExchangeResumeBlueMsg ||
      state.service.conversation.requestResume) {
    return stop('NATIVE_SEND_BLOCKED', '当前会话暂不能发起新的简历请求，请核对 BOSS 会话');
  }
  const prototype = window.XMLHttpRequest?.prototype;
  if (!prototype || typeof prototype.open !== 'function' || typeof prototype.send !== 'function') {
    return stop('NATIVE_CHAT_UNAVAILABLE', '原客户端简历交换响应观察尚未就绪');
  }
  const securityId = String(state.service.conversation.securityId);
  const routes = new Set(['/wapi/zpchat/exchange/test', '/wapi/zpchat/exchange/request']);
  const requests = new WeakMap();
  let sent = 0, observationTimer;
  const complete = () => {
    if (!state.valid()) {
      state.finish(stop('REQUESTS_PAUSED', '会话已变化，简历请求结果未确认'));
    } else if (state.service.conversation.requestResume === 1 ||
               state.service.conversation.requestResume === true) {
      state.finish({ok: true, receipt: {transport: 'native-exchange-response',
        account: args.account, friendId: args.friendId, job: args.job,
        requestId: args.clientMid, exchangeType: 4, code: 0, status: 0}});
    } else observationTimer = setTimeout(complete, 50);
  };
  state.cleanups.push(() => clearTimeout(observationTimer));
  const parsedBody = body => {
    try {
      if (typeof body === 'string' && body.trim().startsWith('{')) return JSON.parse(body);
      const entries = typeof body === 'string' ? [...new URLSearchParams(body)] :
        body && typeof body.entries === 'function' ? [...body.entries()] : [];
      if (!entries.length || entries.length > 40 ||
          entries.some(([key, value]) => typeof key !== 'string' || typeof value !== 'string') ||
          new Set(entries.map(([key]) => key)).size !== entries.length) return null;
      return Object.fromEntries(entries);
    } catch {return null;}
  };
  const wrap = (name, make) => {
    const previous = Object.getOwnPropertyDescriptor(prototype, name), original = prototype[name];
    const hook = make(original);
    Object.defineProperty(prototype, name, {...previous, value: hook, configurable: true});
    state.cleanups.push(() => {
      if (prototype[name] !== hook) return;
      if (previous) Object.defineProperty(prototype, name, previous);
      else delete prototype[name];
    });
  };
  wrap('open', original => function(method, url, ...rest) {
    requests.delete(this);
    try {
      const parsed = new URL(url, location.href);
      if (String(method).toUpperCase() === 'POST' && parsed.origin === 'https://www.zhipin.com' &&
          !parsed.username && !parsed.password && routes.has(parsed.pathname)) {
        requests.set(this, parsed.pathname);
      }
    } catch {}
    return Reflect.apply(original, this, [method, url, ...rest]);
  });
  wrap('send', original => function(body) {
    const route = requests.get(this), data = route && parsedBody(body);
    if (state.invoked && state.valid() && String(data?.type) === '4' &&
        String(data?.securityId || '') === securityId) {
      if (route.endsWith('/request') && ++sent !== 1) {
        state.finish(stop('SEND_UNCONFIRMED', '观察到多个简历交换请求，结果未确认'));
      }
      const xhr = this;
      const finished = () => {
        xhr.removeEventListener('loadend', finished);
        if (!state.invoked || !state.valid() || sent > 1) return;
        let response, responseURL;
        try {
          responseURL = new URL(xhr.responseURL);
        } catch {return;}
        if (responseURL.origin !== 'https://www.zhipin.com' || responseURL.pathname !== route) return;
        if (xhr.status === 429 || xhr.status === 401 || xhr.status === 403) {
          state.finish(stop(xhr.status === 429 ? 'RATE_LIMITED' : 'AUTH_EXPIRED',
            'BOSS 要求暂停本次操作，请在原客户端核对'));
          return;
        }
        if (xhr.status < 200 || xhr.status >= 300) return;
        try {response = xhr.responseType === 'json' ? xhr.response : JSON.parse(xhr.responseText);}
        catch {return;}
        if (response?.code !== 0 || response?.zpData?.status !== 0) {
          const code = ({7: 'AUTH_EXPIRED', 1011: 'AUTH_EXPIRED', 10: 'SESSION_MISMATCH', 9: 'RATE_LIMITED',
            36: 'ACCOUNT_RISK', 70: 'ACCOUNT_RISK', 1013: 'ACCOUNT_RISK',
            37: 'SECURITY_VERIFICATION_REQUIRED', 1014: 'SECURITY_VERIFICATION_REQUIRED'})[response?.code];
          state.finish(stop(code || 'NATIVE_SEND_BLOCKED',
            'BOSS 未接受本次简历交换请求，请查看原会话提示'));
          return;
        }
        if (route.endsWith('/request') && sent === 1 && response.zpData.type === 4) complete();
      };
      xhr.addEventListener('loadend', finished);
      state.cleanups.push(() => xhr.removeEventListener('loadend', finished));
    }
    return Reflect.apply(original, this, [body]);
  });
  return {ok: true};
}

async function commitExchangePage(args) {
  const state = window.__heyiChatAction, resume = window.__heyiResumeAction;
  const component = resume?.component;
  const allowed = () => {
    const conversation = resume?.editor?.conversation$;
    return state && state.token === args.token && state.valid() &&
      resume && resume.token === args.token && !resume.cancelled &&
      resume.matchesJob(conversation) && String(conversation?.securityId) === resume.securityId &&
      component?.type === 4 && [true, 1].includes(component.isCanNormalChart) &&
      !component.isExchangeResumeBlueMsg && !conversation.requestResume &&
      component.disabled !== true && component.loading !== true &&
      component.$el?.getClientRects().length && component.$el.getAttribute('aria-disabled') !== 'true';
  };
  if (!allowed() || state.invoked || resume.invoked || component.showTip ||
      typeof component.handleCheckOpen !== 'function') {
    return {ok: false, code: 'NATIVE_SEND_BLOCKED', message: '原客户端当前不允许索取简历'};
  }
  state.invoked = true;resume.invoked = true;
  // Open through the native entry first: it owns chat-reply restrictions,
  // exchange preflight, alert state and whether a confirmation is available.
  // Never force showTip, clear a block or skip the original opening checks.
  const opening = component.handleCheckOpen();
  if (opening && typeof opening.then === 'function') await opening;
  const deadline = Date.now() + 5000;
  while (allowed() && !state.outcome && component.showTip !== true && Date.now() < deadline) {
    await new Promise(resolve => setTimeout(resolve, 50));
  }
  if (state.outcome) return state.outcome;
  if (!allowed() || component.showTip !== true) {
    return {ok: false, code: 'NATIVE_SEND_BLOCKED',
      message: '原客户端未允许打开简历请求确认，请查看原会话提示'};
  }
  // handleExChange is already the confirmation handler. It runs another
  // native exchange check and the request; do not click a second confirmation.
  const result = component.handleExChange();
  if (result && typeof result.then === 'function') await result;
  return {ok: true};
}
module.exports = {prepareExchangePage, commitExchangePage};
