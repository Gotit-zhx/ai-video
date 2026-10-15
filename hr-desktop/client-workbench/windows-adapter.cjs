const {EventEmitter} = require('node:events');
const {NativeSession} = require('./native-session.cjs');
const {NativeRequestBroker, chromiumPreparedTransport} = require('./native-request-broker.cjs');
const {installMessageObservers} = require('./native-messages.cjs');
const {downloadResume} = require('./native-download.cjs');
const {NativeResume} = require('./native-resume.cjs');
const fail = (code, message) => Object.assign(Error(message), {code});
function sessionReason(event) {
  const code = event?.code;
  if (['AUTH_EXPIRED', 'SESSION_MISMATCH', 'ACCOUNT_RISK', 'SECURITY_VERIFICATION_REQUIRED'].includes(code)) {
    return code;
  }
  return ({10: 'SESSION_MISMATCH', 70: 'ACCOUNT_RISK',
    1013: 'ACCOUNT_RISK', 1014: 'SECURITY_VERIFICATION_REQUIRED'})[Number(code)] || 'AUTH_EXPIRED';
}

class WindowsAdapter extends EventEmitter {
  constructor({bindings, net, session, endpoints, nativeWindow, allContents, show,
    transport, timeout = 65000, sessionTimeout = 1000,
    logoutTargets = [bindings?.sdk, globalThis]}) {
    super();
    if (!bindings || typeof bindings.requestBuilder !== 'function' ||
        typeof show !== 'function' || typeof nativeWindow !== 'function' ||
        typeof allContents !== 'function') throw Error('Verified Windows adapter inputs required');
    this.bindings = bindings;this.net = net;this.session = session;
    this.showNative = show;this.closed = false;this.logoutHooks = [];
    // Verified bindings are intentionally non-enumerable in the target
    // process. Spreading the binding object would discard every reference.
    this.originalSession = new NativeSession({sdk: bindings.sdk, identity: bindings.identity,
      authentication: bindings.authentication, timeout: sessionTimeout});
    this.originalSession.on('session-lost', code => this.emit('session-lost', code));
    this.originalSession.on('login-established', () => this.emit('login-established'));
    this.resume = new NativeResume({nativeWindow, allContents});
    try {
      this.requests = new NativeRequestBroker({
        clients: bindings.httpClients, builder: bindings.requestBuilder, endpoints, timeout,
        observePrepared: input => this.originalSession.observePrepared(input),
        transport: transport || chromiumPreparedTransport({net, session, endpoints: new Set(endpoints)}),
      });
      this.messages = installMessageObservers({
        sender: bindings.sender, receiver: bindings.receiver,
        account: () => this.loggedIn() ? this.originalSession.account() : '',
        observeSend: protocol => this.emit('send-observed', protocol),
        receive: messages => this.emit('messages', messages),
      });
      const observeLogout = (target, name, reason, required = false) => {
        const previous = Object.getOwnPropertyDescriptor(target, name);
        const original = previous?.value || (!previous ? target[name] : undefined);
        if (typeof original !== 'function') {
          if (required) throw Error('Original logout method required');
          return;
        }
        if (previous?.configurable === false) throw Error('Original logout observer unavailable');
        const adapter = this, hook = function(...args) {
          try {adapter.originalSession.invalidate(reason(args[0]));} catch {}
          return Reflect.apply(original, this, args);
        };
        Object.defineProperty(target, name, {value: hook, configurable: true, writable: true});
        this.logoutHooks.push({target, name, previous, hook});
      };
      observeLogout(bindings.authentication, 'clearLoginCookies', () => 'AUTH_EXPIRED', true);
      for (const target of new Set(logoutTargets)) {
        if (target) observeLogout(target, '_sdk_logout', sessionReason);
      }
    } catch (error) {this.close();throw error;}
  }
  user() {return this.originalSession.user();}
  loggedIn() {return this.originalSession.loggedIn();}
  verifyAccount(account, signal) {return this.originalSession.verifyAccount(account, signal);}
  show() {return this.showNative();}
  check(account, checkAccount) {
    this.originalSession.check(account);
    if (this.originalSession.failure) {
      throw fail(this.originalSession.failure, '原客户端会话尚未重新核对');
    }
    if (!this.loggedIn()) throw fail(this.originalSession.failure || 'AUTH_REQUIRED', '请先在原客户端登录 BOSS');
    checkAccount?.();
  }
  snapshot(signal) {
    const account = this.originalSession.account();
    return this.originalSession.snapshot(account, () => this.originalSession.check(account), signal);
  }
  async request(input, checkAccount, signal) {
    const account = this.originalSession.account();
    await this.verifyAccount(account, signal);
    const result = await this.requests.request(input, () => this.check(account, checkAccount), signal);
    const code = Number(result?.body?.code);
    const reason = ({7: 'AUTH_EXPIRED', 1011: 'AUTH_EXPIRED', 10: 'SESSION_MISMATCH',
      70: 'ACCOUNT_RISK', 1013: 'ACCOUNT_RISK', 1014: 'SECURITY_VERIFICATION_REQUIRED'})[code];
    if (reason) this.originalSession.invalidate(reason);
    this.check(account, checkAccount);
    return result;
  }
  async download(input, checkAccount, signal) {
    const account = this.originalSession.account();
    return downloadResume({
      net: this.net, session: this.session, url: input.url, signal, useSessionCookies: false,
      checkAccount: () => this.check(account, checkAccount),
      credentials: () => this.originalSession.credentials(account, () => this.check(account, checkAccount), signal),
    });
  }
  async sendText(input) {
    const account = this.originalSession.account();
    await this.verifyAccount(account);
    this.check(account);
    const options = input?.options;
    if (typeof input?.content !== 'string' || !input.content.trim() || input.content.length > 3000 ||
        !/^[1-9]\d{0,19}$/.test(String(options?.uid || '')) ||
        !/^[1-9]\d{0,29}$/.test(String(options?.clientMid || ''))) {
      throw fail('INVALID_ACTION', '沟通对象或原生消息编号不正确');
    }
    // The original SDK constructs and sends once. Its return value is not a
    // server receipt; NativeRPC still waits for the exact native clientMid.
    return Reflect.apply(this.bindings.sdk._sdk_chatSocket_sendTextMessage, this.bindings.sdk, [input]);
  }
  async requestResume(input) {
    const account = this.originalSession.account();
    await this.verifyAccount(account, input.signal);
    this.check(account, input.checkAccount);
    return this.resume.request({...input,
      checkAccount: () => this.check(account, input.checkAccount)});
  }
  close() {
    if (this.closed) return;
    this.closed = true;this.originalSession.close();
    this.requests?.close();this.messages?.close();
    for (const {target, name, previous, hook} of this.logoutHooks.reverse()) {
      if (Object.getOwnPropertyDescriptor(target, name)?.value === hook) {
        if (previous) Object.defineProperty(target, name, previous);
        else delete target[name];
      }
    }
  }
}
module.exports = {WindowsAdapter};
