// Anonymous, OS-firewalled probe only. Read the original request-header shape
// after its own preparation, then return a controlled failure before transport.
// Header values and SDK results stay in the target process.
const {readOriginalSDK} = require('./original-sdk-read.cjs');

async function install(bindings, config = {}) {
  if (config.anonymousIsolated !== true) throw Error('Anonymous isolated runtime required');
  const loggedIn = await readOriginalSDK(globalThis.$socket, '_sdk_getLoginStatus', 1000);
  const report = {mode: 'offline-original-request-header-contract', ok: false,
    loginStatusReads: 1, originalSDKCalls: 0, originalHTTPCalls: 0,
    userInfoIntercepts: 0, otherIntercepts: 0, hooksRestored: false,
    rawHeadersExported: false, sdkResultsExported: false, candidateMessagesSent: 0};
  if (loggedIn !== false) return {...report, reason: 'anonymous-login-required'};
  const clients = bindings.httpClients;
  if (!Array.isArray(clients) || clients.length < 1 || clients.length > 8) {
    return {...report, reason: 'original-http-clients-required'};
  }
  const cleanup = [];
  let timer;
  const restore = () => {
    for (const {client, previous, hook} of cleanup) {
      if (Object.getOwnPropertyDescriptor(client, 'send')?.value !== hook) continue;
      if (previous) Object.defineProperty(client, 'send', previous);
      else delete client.send;
    }
    report.hooksRestored = true;
  };
  try {
    for (const client of clients) {
      const previous = Object.getOwnPropertyDescriptor(client, 'send');
      if (previous && !previous.configurable) throw Error('Original send interception unavailable');
      const hook = function(input) {
        let url;
        try {url = new URL(input?.url);} catch {}
        if (url?.origin === 'https://www.zhipin.com' &&
            url.pathname === '/wapi/zpuser/wap/getUserInfo.json' && !url.username && !url.password) {
          report.userInfoIntercepts++;
          const headers = Object.getOwnPropertyDescriptors(input.headers || {});
          const names = Object.keys(headers).filter(name => /^[a-zA-Z0-9-]{1,64}$/.test(name));
          const value = name => headers[names.find(key => key.toLowerCase() === name)]?.value;
          const ua = value('user-agent'), cookie = value('cookie');
          report.headerNames = names.map(name => name.toLowerCase()).sort();
          report.userAgentPrintable = typeof ua === 'string' && ua.length > 0 && ua.length <= 4096 &&
            /^[\x20-\x7e]+$/.test(ua);
          report.windowsUserAgent = typeof ua === 'string' && /Windows/i.test(ua);
          report.cookieHeaderType = typeof cookie;
          report.wt2FieldPresent = typeof cookie === 'string' && /(^|;\s*)wt2=[^;\s]+/.test(cookie);
        } else report.otherIntercepts++;
        // Failure code prevents the original SDK from accepting fixture user
        // data as its current identity. The real HTTP method is never called.
        return Promise.resolve({status: false, statusCode: 503,
          body: {code: -1, message: 'Offline request contract; transport not invoked'}});
      };
      Object.defineProperty(client, 'send', {configurable: true, writable: true, value: hook});
      cleanup.push({client, previous, hook});
    }
    report.originalSDKCalls++;
    const completion = Promise.resolve().then(() =>
      Reflect.apply(bindings.sdk._sdk_getUserInfo, bindings.sdk, [true])).then(
      () => 'settled', () => 'settled').finally(restore);
    const state = await Promise.race([completion, new Promise(resolve => {
      timer = setTimeout(() => resolve('timed-out'), 1500);
    })]);
    if (state === 'timed-out') {
      // Keep the blocking hooks until the one pending SDK call settles. Never
      // restore transport while delayed credential preparation is outstanding.
      return {...report, reason: 'original-sdk-did-not-settle'};
    }
    report.ok = report.userInfoIntercepts === 1 && report.userAgentPrintable === true &&
      report.cookieHeaderType === 'string' && report.hooksRestored;
    return {...report, reason: 'original-request-header-shape-checked'};
  } catch {
    restore();
    return {...report, reason: 'original-request-header-check-unavailable'};
  } finally {clearTimeout(timer);}
}
module.exports = {install};
