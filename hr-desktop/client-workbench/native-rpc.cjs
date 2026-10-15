const http = require('node:http');
const crypto = require('node:crypto');
const {EventEmitter} = require('node:events');
const {resumeURL, MAX_BYTES} = require('./native-download.cjs');

const identity = value => (typeof value !== 'number' || Number.isSafeInteger(value)) &&
  /^[1-9]\d{0,19}$/.test(String(value || '')) ? String(value) : '';
const failure = (code, message) => Object.assign(Error(message), {code});
const RESUME_REQUEST_TEXT = '方便发一份简历过来吗？';

// Only the packaged worker knows this per-launch credential. The website talks
// to the existing authorized HR bridge, never directly to these native methods.
class NativeRPC extends EventEmitter {
  constructor({adapter, endpoints, sendTimeout = 9000}) {
    super();
    this.adapter = adapter;
    this.endpoints = new Set(endpoints);
    this.secret = crypto.randomBytes(32).toString('base64url');
    this.sendTimeout = sendTimeout;
    this.pendingSends = new Map();
    this.downloads = new Set();
    this.sessionEpoch = 0;
    this.loginGeneration = 0;
    this.closed = false;
  }
  async start() {
    this.server = http.createServer((req, res) => this.handle(req, res));
    this.server.requestTimeout = 95000;
    this.server.headersTimeout = 5000;
    await new Promise((resolve, reject) => {
      this.server.once('error', reject);
      this.server.listen(0, '127.0.0.1', resolve);
    });
    this.port = this.server.address().port;
    this.url = `http://127.0.0.1:${this.port}`;
    return this;
  }
  currentIdentity() {
    return identity(this.adapter.user()?.userId);
  }
  accountState() {
    if (this.closed || this.sessionFailure) return 'paused';
    return this.currentIdentity() && this.adapter.loggedIn() ? 'connected' : 'signed-out';
  }
  requireAccount(expected) {
    if (this.closed) throw failure('REQUESTS_PAUSED', '客户端已关闭');
    if (this.sessionFailure) throw failure(this.sessionFailure, 'BOSS 会话已失效，请在原客户端确认');
    const current = this.currentIdentity();
    if (!current || !this.adapter.loggedIn()) throw failure('AUTH_REQUIRED', '请先在客户端登录 BOSS');
    if (!identity(expected) || identity(expected) !== current) {
      throw failure('SESSION_MISMATCH', '客户端账号已变化，本次操作未执行');
    }
    return current;
  }
  sessionLost(code = 'AUTH_EXPIRED') {
    if (code === 'AUTH_EXPIRED' && this.sessionFailure && this.sessionFailure !== 'AUTH_EXPIRED') {
      code = this.sessionFailure;
    }
    this.sessionFailure = ['AUTH_EXPIRED', 'ACCOUNT_RISK', 'SESSION_MISMATCH',
      'SECURITY_VERIFICATION_REQUIRED'].includes(code) ? code : 'AUTH_EXPIRED';
    this.lostAccount = this.currentIdentity() || this.lostAccount;
    this.lostLoginGeneration = this.loginGeneration;
    this.sessionEpoch++;
    for (const pending of this.pendingSends.values()) {
      pending.controller?.abort();
      pending.reject(failure(this.sessionFailure, 'BOSS 会话已暂停，请在原客户端确认'));
    }
    for (const controller of this.downloads) controller.abort();
    this.emit('session-status', this.accountState());
    this.emit('session-lost', this.sessionFailure);
  }
  observeNativeSend(protocol) {
    // Observe the original SDK's real encoded outgoing message. This does not
    // create, replace, send, or acknowledge a message. The server receipt is
    // still mandatory and must match this exact native clientMid.
    const own = this.currentIdentity();
    if (!own || this.closed || this.sessionFailure || !this.adapter.loggedIn()) return;
    for (const message of protocol?.messages || []) {
      if (identity(message.from?.uid) !== own || message.body?.type !== 1) continue;
      const friend = identity(message.to?.uid);
      const pending = this.pendingSends.get(friend);
      const clientMid = String(message.cmid || '');
      if (pending?.kind !== 'resume' || !pending.armed || pending.account !== own ||
          pending.content !== message.body.text || !/^[1-9]\d{0,29}$/.test(clientMid)) continue;
      if (pending.clientMid && pending.clientMid !== clientMid) {
        pending.controller.abort();
        pending.reject(failure('SEND_UNCONFIRMED', '观察到多个原生发送，已停止并保留未确认状态'));
      } else pending.clientMid = clientMid;
    }
  }
  loginEstablished() {
    // Only the original client's completed login handler calls this. This is
    // not exposed to the website or the worker and performs no login itself.
    this.loginGeneration++;
    this.emit('session-status', this.accountState());
  }
  async refreshSession(account) {
    if (!this.sessionFailure) {
      this.requireAccount(account);
      const snapshot = await this.adapter.snapshot();
      this.requireAccount(account);
      return snapshot;
    }
    if (this.sessionFailure !== 'AUTH_EXPIRED') this.requireAccount(account);
    const epoch = this.sessionEpoch;
    if (this.closed || this.currentIdentity() !== account || this.lostAccount !== account ||
        !this.adapter.loggedIn() || this.loginGeneration <= this.lostLoginGeneration) {
      throw failure('AUTH_EXPIRED', '请先在原客户端完成同一账号的重新登录');
    }
    const snapshot = await this.adapter.snapshot();
    const tokens = new Set((snapshot.cookies || []).filter(cookie => {
      const domain = String(cookie.domain || '').replace(/^\./, '').toLowerCase();
      return cookie.name === 'wt2' && cookie.value &&
        (domain === 'zhipin.com' || domain.endsWith('.zhipin.com'));
    }).map(cookie => cookie.value));
    if (this.sessionEpoch !== epoch || this.closed) throw failure('REQUESTS_PAUSED', '会话状态已变化');
    if (this.currentIdentity() !== account || !this.adapter.loggedIn() ||
        identity(snapshot.accountId) !== account || tokens.size !== 1 || !snapshot.userAgent) {
      throw failure('SESSION_MISMATCH', '重新登录后的账号或登录态尚未核对');
    }
    this.sessionFailure = null;
    this.emit('session-status', this.accountState());
    // The recruiter engine remains paused until explicit server verification;
    // no ledger, persistent hold or unconfirmed write is removed here.
    return snapshot;
  }
  // Called by the client's existing receive callback. It adds no polling,
  // subscription, login or request and leaves the original callback intact.
  receive(messages) {
    const own = this.currentIdentity();
    if (!own || this.closed || this.sessionFailure || !this.adapter.loggedIn()) return;
    for (const message of Array.isArray(messages) ? messages : []) {
      const friend = identity(message.isSelf ? message.toId : message.fromId);
      const mid = /^[1-9]\d{0,29}$/.test(String(message.mid || '')) ? String(message.mid) : '';
      if (!friend || !mid) continue;
      let at = Number(message.time);
      if (at > 1e12) at /= 1000;
      if (!Number.isFinite(at) || at <= 0) continue;
      if (message.isSelf === true && identity(message.fromId) === own &&
          message.newSubmit !== true && message.status === 1 && message.type === 'text') {
        for (const pending of this.pendingSends.values()) {
          if (pending.account === own && pending.friend === friend &&
              pending.clientMid &&
              pending.clientMid === String(message.clientMid || '') &&
              pending.content === message.text && at >= pending.started / 1000 - 2) {
            pending.resolve({friendId: friend, messageId: mid, confirmedBy: 'client-message-receipt'});
          }
        }
      } else if (message.isSelf === false && identity(message.toId) === own) {
        this.emit('activity', {friend, mid, at, job: ''});
      }
    }
  }
  async sendText(account, friend, content) {
    this.requireAccount(account);
    const id = identity(friend.friendId || friend.uid);
    if (!id || typeof content !== 'string' || !content.trim() || content.length > 3000) {
      throw failure('INVALID_ACTION', '沟通对象或内容不正确');
    }
    // One pending write per friend. A timeout is uncertain and never retried.
    if (this.pendingSends.has(id)) throw failure('ACCOUNT_BUSY', '该候选人的发送操作尚未完成');
    let settle;
    const confirmation = new Promise((resolve, reject) => {settle = {resolve, reject};});
    const started = Date.now();
    const clientMid = String(started * 1000 + crypto.randomInt(1000));
    const pending = {kind: 'text', account, friend: id, content, clientMid, started, ...settle};
    this.pendingSends.set(id, pending);
    const timer = setTimeout(() => pending.reject(failure('SEND_UNCONFIRMED', '未收到实际消息回执，未自动重复发送')), this.sendTimeout);
    try {
      this.requireAccount(account);
      // Await both transport completion and an actual own-message receipt;
      // the original SDK's immediately resolved promise is insufficient.
      await Promise.all([
        this.adapter.sendText({content, options: {
          uid: id, encryptUid: friend.encryptUid, friendSource: friend.friendSource || 0, clientMid,
        }}),
        confirmation,
      ]);
      this.requireAccount(account);
      return await confirmation;
    } finally {
      clearTimeout(timer);
      if (this.pendingSends.get(id) === pending) this.pendingSends.delete(id);
    }
  }
  async requestResume(account, friend, signal) {
    this.requireAccount(account);
    const id = identity(friend?.friendId || friend?.uid);
    const job = friend?.encryptJobId || friend?.encJobId;
    if (!id || typeof job !== 'string' || !job || job.length > 256) {
      throw failure('JOB_CONVERSATION_MISMATCH', '候选人与当前职位未核实');
    }
    if (typeof this.adapter.requestResume !== 'function') {
      throw failure('UNSUPPORTED_NATIVE_ACTION', '原客户端索取简历尚未就绪');
    }
    // Every resume action changes the one native chat editor. Serialize this
    // across candidates as well as keeping the per-friend send exclusion.
    if (this.resumeAction || this.pendingSends.has(id)) {
      throw failure('ACCOUNT_BUSY', '原客户端的简历索取操作尚未完成，请稍后');
    }
    let settle, timer;
    const confirmation = new Promise((resolve, reject) => {settle = {resolve, reject};});
    // The account may be lost while the native UI is still preparing its
    // conversation. Keep that rejection observed until Promise.all is attached.
    void confirmation.catch(() => {});
    const controller = new AbortController();
    const cancel = () => {
      controller.abort();
      pending.reject(failure('REQUESTS_PAUSED', '索取简历连接已停止'));
    };
    const pending = {kind: 'resume', account, friend: id, content: RESUME_REQUEST_TEXT,
      clientMid: '', started: Date.now(), controller, armed: false, ...settle};
    this.pendingSends.set(id, pending);
    this.resumeAction = pending;
    signal?.addEventListener('abort', cancel, {once: true});
    const prepareTimer = setTimeout(() => {
      controller.abort();
      pending.reject(failure('REQUESTS_PAUSED', '原客户端会话准备超时，已停止'));
    }, 6500);
    try {
      if (signal?.aborted) throw failure('REQUESTS_PAUSED', '索取简历连接已停止');
      const perform = this.adapter.requestResume({
        friend, signal: controller.signal, checkAccount: () => this.requireAccount(account),
        onReady: () => {
          this.requireAccount(account);
          if (controller.signal.aborted) throw failure('REQUESTS_PAUSED', '索取简历已停止');
          if (pending.armed) throw failure('INVALID_ACTION', '索取简历不能重复确认');
          clearTimeout(prepareTimer);
          pending.started = Date.now();
          pending.armed = true;
          timer = setTimeout(() => {
            controller.abort();
            pending.reject(failure('SEND_UNCONFIRMED', '未收到索取简历的实际消息回执，未自动重发'));
          }, this.sendTimeout);
        },
      });
      try {
        await Promise.all([perform, confirmation]);
      } catch (error) {
        if (pending.armed && !['AUTH_EXPIRED', 'ACCOUNT_RISK', 'SESSION_MISMATCH',
          'SECURITY_VERIFICATION_REQUIRED', 'REQUESTS_PAUSED'].includes(error.code)) {
          throw failure('SEND_UNCONFIRMED', '索取简历未得到完整确认，请核对原客户端；没有自动重发');
        }
        throw error;
      }
      this.requireAccount(account);
      return {...await confirmation, exchangeType: 4};
    } finally {
      signal?.removeEventListener('abort', cancel);
      clearTimeout(prepareTimer);clearTimeout(timer);controller.abort();
      if (this.pendingSends.get(id) === pending) this.pendingSends.delete(id);
      if (this.resumeAction === pending) this.resumeAction = null;
    }
  }
  async download(account, value, signal) {
    this.requireAccount(account);
    const url = resumeURL(value);
    if (typeof this.adapter.download !== 'function') {
      throw failure('UNSUPPORTED_NATIVE_ACTION', '原客户端附件下载尚未就绪');
    }
    const controller = new AbortController();
    const cancel = () => controller.abort();
    signal?.addEventListener('abort', cancel, {once: true});
    if (signal?.aborted) controller.abort();
    this.downloads.add(controller);
    try {
      const result = await this.adapter.download({url},
        () => this.requireAccount(account), controller.signal);
      this.requireAccount(account);
      if (!result || !Number.isInteger(result.status) || result.status < 200 || result.status > 599 ||
          !Buffer.isBuffer(result.body) || result.body.length > MAX_BYTES) {
        throw failure('INVALID_ATTACHMENT', '附件返回内容不正确');
      }
      return result;
    } catch (error) {
      this.requireAccount(account);
      throw error;
    } finally {
      signal?.removeEventListener('abort', cancel);
      this.downloads.delete(controller);
    }
  }
  async dispatch(route, input, signal) {
    this.requireAccount(input.accountId);
    if (route === '/snapshot') {
      const snapshot = await this.adapter.snapshot();
      this.requireAccount(input.accountId);
      return snapshot;
    }
    if (route === '/download') return this.download(input.accountId, input.url, signal);
    if (route === '/http') {
      const url = new URL(input.url);
      if (url.origin !== 'https://www.zhipin.com' || url.hash ||
          !this.endpoints.has(url.pathname) || !['GET', 'POST'].includes(input.method)) {
        throw failure('UNSUPPORTED_OPERATION', '不支持的客户端接口');
      }
      // No caller-supplied cookies, client identity, signing fields or redirects.
      if (input.data && (Array.isArray(input.data) || typeof input.data !== 'object')) {
        throw failure('INVALID_ACTION', '接口参数不正确');
      }
      const data = {...Object.fromEntries(url.searchParams), ...(input.data || {})};
      for (const name of ['__zp_stoken__', 'zp_token', 'wt2', 'headers', 'origin',
        'agent', 'clientInfo', 'userAgent', 'user_agent', 'platform', 'appid']) delete data[name];
      const result = await this.adapter.request({url: url.pathname, method: input.method.toLowerCase(), data});
      this.requireAccount(input.accountId);
      return result;
    }
    if (route === '/send-text') return this.sendText(input.accountId, input.friend, input.content);
    if (route === '/request-resume') return this.requestResume(input.accountId, input.friend, signal);
    throw failure('UNSUPPORTED_OPERATION', '不支持的客户端操作');
  }
  async handle(req, res) {
    const controller = new AbortController();
    res.once('close', () => controller.abort());
    const respond = (status, value) => {
      if (res.destroyed) return;
      res.writeHead(status, {'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store'});
      res.end(JSON.stringify(value));
    };
    const actual = Buffer.from(req.headers.authorization || '');
    const expected = Buffer.from('Bearer ' + this.secret);
    if (req.headers.host !== `127.0.0.1:${this.port}` || req.headers.origin ||
        actual.length !== expected.length || !crypto.timingSafeEqual(actual, expected) || req.method !== 'POST') {
      respond(403, {ok: false, error: {code: 'FORBIDDEN'}});
      return;
    }
    try {
      let body = '';
      for await (const chunk of req) {
        body += chunk;
        if (Buffer.byteLength(body) > 65536) throw failure('INVALID_ACTION', '请求过长');
      }
      const value = await this.dispatch(req.url, JSON.parse(body), controller.signal);
      if (req.url === '/download') {
        if (!res.destroyed) {
          res.writeHead(value.status, {'Content-Type': 'application/octet-stream',
            'X-Heyi-Native-Download': '1', 'Cache-Control': 'no-store',
            'Content-Length': value.body.length});
          res.end(value.body);
        }
        return;
      }
      respond(200, {ok: true, data: value});
    } catch (error) {
      respond(409, {ok: false, error: {code: error.code || 'NATIVE_OPERATION_FAILED',
        message: error.message || '客户端操作未完成'}});
    }
  }
  async close() {
    this.closed = true;
    this.emit('session-status', this.accountState());
    for (const controller of this.downloads) controller.abort();
    for (const pending of this.pendingSends.values()) {
      pending.controller?.abort();
      pending.reject(failure('REQUESTS_PAUSED', '客户端已关闭'));
    }
    this.server?.closeAllConnections?.();
    await new Promise(resolve => this.server ? this.server.close(resolve) : resolve());
  }
}
module.exports = {NativeRPC, identity, RESUME_REQUEST_TEXT};
