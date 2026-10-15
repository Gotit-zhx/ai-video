const {EventEmitter} = require('node:events');
const {createHash} = require('node:crypto');
const {nativeHeaders} = require('./native-http.cjs');
const fail = (code, message) => Object.assign(Error(message), {code});
const id = value => (typeof value !== 'number' || Number.isSafeInteger(value)) &&
  /^[1-9]\d{0,19}$/.test(String(value || '')) ? String(value) : '';
const fingerprint = token => createHash('sha256').update(token).digest('hex');
const permanent = new Set(['ACCOUNT_RISK', 'SESSION_MISMATCH', 'SECURITY_VERIFICATION_REQUIRED']);

function cookieFields(header) {
  if (typeof header !== 'string' || header.length > 16384 || !/^[\x20-\x7e]+$/.test(header)) {
    throw fail('AUTH_REQUIRED', '原客户端登录态尚未建立');
  }
  const fields = new Map();
  for (const field of header.split(';')) {
    const at = field.indexOf('='), name = field.slice(0, at).trim(), value = field.slice(at + 1).trim();
    if (at < 1 || !/^[!#$%&'*+\-.^_`|~0-9a-zA-Z]+$/.test(name) ||
        !value || /[\s;]/.test(value) || fields.has(name)) {
      throw fail('AUTH_REQUIRED', '原客户端登录态尚未核对');
    }
    fields.set(name, value);
  }
  if (!fields.has('wt2')) throw fail('AUTH_REQUIRED', '原客户端登录态尚未建立');
  return fields;
}

// Own only derived observation metadata. The original client remains the
// source of login status, identity, credentials and User-Agent. No login,
// refresh, platform request or polling is initiated by construction.
class NativeSession extends EventEmitter {
  constructor({sdk, identity, authentication, timeout = 1000}) {
    super();
    if (typeof sdk?._sdk_getLoginStatus !== 'function' || !identity ||
        typeof authentication?.getAuthCookieHeader !== 'function' ||
        !Number.isInteger(timeout) || timeout < 1 || timeout > 5000) {
      throw Error('Verified original session bindings required');
    }
    this.sdk = sdk;this.identity = identity;this.authentication = authentication;
    this.timeout = timeout;this.epoch = 0;this.pending = new Set();
    this.verifiedAccount = '';this.boundAccount = '';this.closed = false;
    this.observed = null;this.lastCredentialHash = '';this.lostCredentialHash = '';
    this.observedSignedOut = false;this.failure = null;
  }
  user() {return this.identity.userInfo;}
  account() {return id(this.user()?.userId);}
  loggedIn() {
    return !this.closed && !permanent.has(this.failure) &&
      Boolean(this.verifiedAccount) && this.account() === this.verifiedAccount;
  }
  check(expected, epoch = this.epoch) {
    if (this.closed) throw fail('REQUESTS_PAUSED', '原客户端连接已关闭');
    if (permanent.has(this.failure)) throw fail(this.failure, 'BOSS 会话已暂停，请在原客户端确认');
    if (epoch !== this.epoch) throw fail(this.failure || 'REQUESTS_PAUSED', '原客户端会话已变化');
    const account = this.account();
    if (!account) throw fail('AUTH_REQUIRED', '请先在原客户端登录 BOSS');
    if (this.boundAccount && this.boundAccount !== account) {
      this.invalidate('SESSION_MISMATCH');
      throw fail('SESSION_MISMATCH', '原客户端账号已变化，本次操作未执行');
    }
    if (account !== id(expected)) throw fail('SESSION_MISMATCH', '请求账号与原客户端不一致');
    return account;
  }
  invalidate(code = 'AUTH_EXPIRED') {
    if (this.closed) return;
    if (permanent.has(this.failure)) code = this.failure;
    if (!['AUTH_EXPIRED', ...permanent].includes(code)) code = 'AUTH_EXPIRED';
    if (this.failure === code && !this.verifiedAccount) return;
    const previouslyVerified = Boolean(this.verifiedAccount);
    this.failure = code;this.lostCredentialHash ||= this.lastCredentialHash;
    this.verifiedAccount = '';this.epoch++;
    for (const controller of this.pending) controller.abort();
    if (previouslyVerified || permanent.has(code)) this.emit('session-lost', code);
  }
  bounded(operation, signal, code) {
    if (this.closed || signal?.aborted) return Promise.reject(fail('REQUESTS_PAUSED', '会话读取已取消'));
    const controller = new AbortController();this.pending.add(controller);
    return new Promise((resolve, reject) => {
      let settled = false;
      const finish = (error, value) => {
        if (settled) return;
        settled = true;clearTimeout(timer);
        signal?.removeEventListener('abort', cancel);controller.signal.removeEventListener('abort', cancel);
        this.pending.delete(controller);
        if (error) reject(error);else resolve(value);
      };
      const cancel = () => finish(fail(this.closed ? 'REQUESTS_PAUSED' :
        this.failure || 'REQUESTS_PAUSED', '会话读取已停止'));
      const timer = setTimeout(() => finish(fail(code, '原客户端会话读取超时')), this.timeout);
      signal?.addEventListener('abort', cancel, {once: true});
      controller.signal.addEventListener('abort', cancel, {once: true});
      if (signal?.aborted || this.closed) {cancel();return;}
      Promise.resolve().then(() => {
        if (settled) return;
        return operation();
      }).then(value => finish(null, value), error =>
        finish(fail(permanent.has(error?.code) ? error.code : code, '原客户端会话尚未核对')));
    });
  }
  async verifyAccount(expected, signal) {
    if (this.closed) throw fail('REQUESTS_PAUSED', '原客户端连接已关闭');
    if (permanent.has(this.failure)) throw fail(this.failure, 'BOSS 会话已暂停，请在原客户端确认');
    const epoch = this.epoch;
    const loggedIn = await this.bounded(
      () => Reflect.apply(this.sdk._sdk_getLoginStatus, this.sdk, []), signal, 'AUTH_REQUIRED');
    if (epoch !== this.epoch) throw fail(this.failure || 'REQUESTS_PAUSED', '原客户端会话已变化');
    if (loggedIn !== true) {
      this.observedSignedOut = loggedIn === false || this.observedSignedOut;
      if (this.verifiedAccount) this.invalidate('AUTH_EXPIRED');
      throw fail('AUTH_REQUIRED', '请先在原客户端登录 BOSS');
    }
    const account = this.check(expected, epoch);
    this.boundAccount ||= account;this.verifiedAccount = account;
    return account;
  }
  observePrepared(input) {
    // A passive observer never calls a getter, transport or login method.
    // Associate actual native UA with a hash of the actual native wt2 value,
    // rather than borrowing a previous user's headers or a Chromium default.
    try {
      const url = new URL(input?.url);
      if (url.origin !== 'https://www.zhipin.com' || url.username || url.password) return;
      const descriptors = Object.getOwnPropertyDescriptors(input.headers || {});
      const read = name => {
        const names = Object.keys(descriptors).filter(key => key.toLowerCase() === name);
        if (names.length !== 1 || !Object.hasOwn(descriptors[names[0]], 'value')) throw Error();
        return descriptors[names[0]].value;
      };
      const cookieHeader = read('cookie'), userAgent = read('user-agent');
      nativeHeaders({cookieHeader, userAgent});
      const token = cookieFields(cookieHeader).get('wt2');
      if (!this.closed) this.observed = {tokenHash: fingerprint(token), userAgent};
    } catch {}
  }
  async credentials(expected, checkAccount, signal) {
    await this.verifyAccount(expected, signal);
    const epoch = this.epoch;
    checkAccount();this.check(expected, epoch);
    const cookieHeader = await this.bounded(() => Reflect.apply(
      this.authentication.getAuthCookieHeader, this.authentication, []), signal, 'AUTH_REQUIRED');
    checkAccount();this.check(expected, epoch);
    const fields = cookieFields(cookieHeader), token = fields.get('wt2'), tokenHash = fingerprint(token);
    if (!this.observed || this.observed.tokenHash !== tokenHash) {
      throw fail('AUTH_REQUIRED', '请在 BOSS 原客户端打开会话后重新连接，正在等待原生请求标识');
    }
    const result = {cookieHeader, token, userAgent: this.observed.userAgent};
    nativeHeaders(result);this.lastCredentialHash = tokenHash;
    return result;
  }
  async snapshot(expected, checkAccount, signal) {
    const credentials = await this.credentials(expected, checkAccount, signal);
    const hash = fingerprint(credentials.token);
    // A real false->true memory transition, or a changed native credential
    // after actual expiry, is required. A truthy Promise is never a login event.
    if (this.failure === 'AUTH_EXPIRED' &&
        (this.observedSignedOut || (this.lostCredentialHash && this.lostCredentialHash !== hash))) {
      this.failure = null;this.lostCredentialHash = '';this.observedSignedOut = false;
      this.emit('login-established');
    }
    return {accountId: this.check(expected), userAgent: credentials.userAgent,
      cookies: [...cookieFields(credentials.cookieHeader)].map(([name, value]) =>
        ({name, value, domain: 'www.zhipin.com', secure: true}))};
  }
  close() {
    if (this.closed) return;
    this.closed = true;this.epoch++;this.verifiedAccount = '';
    this.observed = null;this.lastCredentialHash = '';this.lostCredentialHash = '';
    for (const controller of this.pending) controller.abort();
  }
}
module.exports = {NativeSession, cookieFields};
