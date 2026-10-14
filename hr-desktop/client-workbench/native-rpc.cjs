const http = require('node:http');
const crypto = require('node:crypto');
const {EventEmitter} = require('node:events');

const identity = value => /^[1-9]\d{0,19}$/.test(String(value || '')) ? String(value) : '';
const failure = (code, message) => Object.assign(Error(message), {code});

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
  requireAccount(expected) {
    if (this.closed) throw failure('REQUESTS_PAUSED', '客户端已关闭');
    const current = this.currentIdentity();
    if (!current || !this.adapter.loggedIn()) throw failure('AUTH_REQUIRED', '请先在客户端登录 BOSS');
    if (!identity(expected) || identity(expected) !== current) {
      throw failure('SESSION_MISMATCH', '客户端账号已变化，本次操作未执行');
    }
    if (this.sessionFailure) throw failure(this.sessionFailure, 'BOSS 会话已失效，请在原客户端确认');
    return current;
  }
  sessionLost(code = 'AUTH_EXPIRED') {
    if (code === 'AUTH_EXPIRED' && this.sessionFailure && this.sessionFailure !== 'AUTH_EXPIRED') {
      code = this.sessionFailure;
    }
    this.sessionFailure = ['AUTH_EXPIRED', 'ACCOUNT_RISK', 'SESSION_MISMATCH',
      'SECURITY_VERIFICATION_REQUIRED'].includes(code) ? code : 'AUTH_EXPIRED';
    for (const pending of this.pendingSends.values()) {
      pending.reject(failure(this.sessionFailure, 'BOSS 会话已暂停，请在原客户端确认'));
    }
    this.emit('session-lost', this.sessionFailure);
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
    const pending = {account, friend: id, content, clientMid, started, ...settle};
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
  async dispatch(route, input) {
    this.requireAccount(input.accountId);
    if (route === '/snapshot') {
      const snapshot = await this.adapter.snapshot();
      this.requireAccount(input.accountId);
      return snapshot;
    }
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
    throw failure('UNSUPPORTED_OPERATION', '不支持的客户端操作');
  }
  async handle(req, res) {
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
      const value = await this.dispatch(req.url, JSON.parse(body));
      respond(200, {ok: true, data: value});
    } catch (error) {
      respond(409, {ok: false, error: {code: error.code || 'NATIVE_OPERATION_FAILED',
        message: error.message || '客户端操作未完成'}});
    }
  }
  async close() {
    this.closed = true;
    for (const pending of this.pendingSends.values()) pending.reject(failure('REQUESTS_PAUSED', '客户端已关闭'));
    this.server?.closeAllConnections?.();
    await new Promise(resolve => this.server ? this.server.close(resolve) : resolve());
  }
}
module.exports = {NativeRPC, identity};
