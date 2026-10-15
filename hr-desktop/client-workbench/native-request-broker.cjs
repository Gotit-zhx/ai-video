const {AsyncLocalStorage} = require('node:async_hooks');
const {encodedForm, nativeHeaders, nativeJSONRequest} = require('./native-http.cjs');
const ORIGIN = 'https://www.zhipin.com';
const attached = new WeakSet();
const failure = (code, message) => Object.assign(Error(message), {code});

function preparedCredentials(input) {
  const descriptors = Object.getOwnPropertyDescriptors(input.headers || {});
  const read = name => {
    const keys = Object.keys(descriptors).filter(key => key.toLowerCase() === name);
    if (keys.length > 1 || keys.some(key => !Object.hasOwn(descriptors[key], 'value'))) {
      throw failure('AUTH_REQUIRED', '原客户端请求头尚未核对');
    }
    return descriptors[keys[0]]?.value;
  };
  const credentials = {cookieHeader: read('cookie'), userAgent: read('user-agent'),
    traceId: read('traceid'), zpToken: read('zp_token')};
  nativeHeaders(credentials);
  return credentials;
}
function chromiumPreparedTransport({net, session, endpoints, authenticate}) {
  return (input, checkAccount, signal) => nativeJSONRequest({
    net, session, endpoints, authenticate, signal, checkAccount,
    url: input.url, method: input.method, data: input.data,
    credentials: () => preparedCredentials(input),
  });
}

class NativeRequestBroker {
  constructor({clients, builder, transport, endpoints, timeout = 65000}) {
    if (!Array.isArray(clients) || clients.length < 1 || clients.length > 8 ||
        new Set(clients).size !== clients.length || typeof builder !== 'function' ||
        typeof transport !== 'function' || !Number.isInteger(timeout) || timeout < 1 || timeout > 75000) {
      throw Error('Verified native request broker inputs required');
    }
    this.builder = builder;this.transport = transport;this.endpoints = new Set(endpoints);
    this.timeout = timeout;this.context = new AsyncLocalStorage();
    this.pending = new Set();this.builders = new Set();this.changes = [];
    this.closed = false;this.detached = false;
    try {
      for (const client of clients) {
        if (!client || attached.has(client)) throw Error('Native HTTP client already attached');
        const previous = Object.getOwnPropertyDescriptor(client, 'send');
        if (previous && !previous.configurable) throw Error('Native HTTP hook unavailable');
        const original = client.send;
        if (typeof original !== 'function') throw Error('Native HTTP send unavailable');
        const broker = this;
        const hook = function(...args) {
          const context = broker.context.getStore();
          if (!context || context.broker !== broker) return Reflect.apply(original, this, args);
          return broker.prepared(context, args[0]);
        };
        Object.defineProperty(client, 'send', {value: hook, configurable: true, writable: true});
        this.changes.push({client, previous, hook});attached.add(client);
      }
    } catch (error) {this.closed = true;this.detach();throw error;}
  }
  prepared(context, input) {
    try {
      if (this.closed || context.settled || context.controller.signal.aborted) {
        throw failure('REQUESTS_PAUSED', '接口准备已取消，未开始迟到请求');
      }
      if (++context.calls !== 1) {
        throw failure('UNEXPECTED_NATIVE_RETRY', '原客户端请求出现重复调用，已停止');
      }
      context.checkAccount();
      const url = new URL(input?.url);
      if (url.href !== context.url || String(input.method || 'get').toUpperCase() !== context.method ||
          url.origin !== ORIGIN || url.username || url.password || url.hash ||
          !this.endpoints.has(url.pathname)) {
        throw failure('UNSUPPORTED_OPERATION', '原客户端准备的接口与请求不一致');
      }
      // Retain any native preparation, while bounding the final form too.
      encodedForm(input.data);
      const promise = Promise.resolve().then(() => {
        if (context.settled || this.closed) throw failure('REQUESTS_PAUSED', '接口读取已取消');
        context.checkAccount();
        return this.transport(input, context.checkAccount, context.controller.signal);
      }).then(reply => {
        context.checkAccount();
        if (context.settled || !reply || reply.heyiNativeHttp !== 1 ||
            !Number.isInteger(reply.statusCode) || reply.statusCode < 200 || reply.statusCode > 599) {
          throw failure('INVALID_NATIVE_RESPONSE', '原客户端接口状态尚未核对');
        }
        context.reply = reply;
        return {status: reply.statusCode >= 200 && reply.statusCode < 300,
          statusCode: reply.statusCode, body: reply.body};
      });
      context.transportPromise = promise;
      // Observe immediately even if an unexpected original caller discards it.
      promise.catch(error => {context.transportError = error;});
      return promise;
    } catch (error) {
      context.finish(error);
      const stopped = Promise.reject(error);
      stopped.catch(() => {});
      return stopped;
    }
  }
  request(input, checkAccount, signal) {
    let url, method;
    try {
      if (this.closed) throw failure('REQUESTS_PAUSED', '原生接口已关闭');
      if (typeof checkAccount !== 'function') throw failure('INVALID_ACTION', '账号校验未就绪');
      checkAccount();
      url = new URL(input?.url, ORIGIN);method = String(input?.method || 'get').toUpperCase();
      if (url.origin !== ORIGIN || url.username || url.password || url.hash ||
          !url.pathname.startsWith('/wapi/') || !this.endpoints.has(url.pathname) ||
          !['GET', 'POST'].includes(method)) {
        throw failure('UNSUPPORTED_OPERATION', '不支持的原生接口');
      }
      encodedForm(input.data);
      if (signal?.aborted) throw failure('REQUESTS_PAUSED', '接口读取已取消');
    } catch (error) {return Promise.reject(error);}
    return new Promise((resolve, reject) => {
      const context = {broker: this, calls: 0, settled: false, url: url.href, method,
        controller: new AbortController(), checkAccount};
      const cancel = () => {
        try {checkAccount();} catch (error) {finish(error);return;}
        finish(failure('REQUESTS_PAUSED', '接口读取已取消'));
      };
      const timer = setTimeout(() => finish(
        failure('NATIVE_HTTP_TIMEOUT', '原客户端接口读取超时')), this.timeout);
      const finish = (error, value) => {
        if (context.settled) return;
        context.settled = true;clearTimeout(timer);signal?.removeEventListener('abort', cancel);
        this.pending.delete(context);context.controller.abort();
        if (error) reject(error);else resolve(value);
      };
      context.finish = finish;this.pending.add(context);this.builders.add(context);
      signal?.addEventListener('abort', cancel, {once: true});
      if (signal?.aborted) {cancel();this.builders.delete(context);return;}
      const preparedInput = {url: url.pathname + url.search, method: method.toLowerCase(),
        data: input.data || {}};
      const invocation = Promise.resolve().then(() => {
        if (context.settled || this.closed) throw failure('REQUESTS_PAUSED', '接口准备已取消');
        checkAccount();
        return this.context.run(context, () => Reflect.apply(this.builder, undefined, [preparedInput]));
      });
      invocation.then(async () => {
        if (context.settled) return;
        if (context.calls !== 1 || !context.transportPromise) {
          throw failure('NATIVE_HTTP_FAILED', '原客户端未完成接口准备');
        }
        await context.transportPromise;
        checkAccount();
        if (context.transportError) throw context.transportError;
        finish(null, context.reply);
      }).catch(error => {
        if (context.transportError) error = context.transportError;
        finish(error?.code ? error : failure('NATIVE_HTTP_FAILED', '原客户端接口准备未完成'));
      }).finally(() => {
        this.builders.delete(context);
        if (this.closed) this.detach();
      });
    });
  }
  close() {
    if (this.closed) return;
    this.closed = true;
    for (const context of this.pending) {
      context.finish(failure('REQUESTS_PAUSED', '原生接口已关闭'));
    }
    this.detach();
  }
  detach() {
    // A delayed native builder still carries our async context. Its late send
    // must meet the rejecting hook, even after a cancellation or client close.
    if (this.detached || this.builders.size) return;
    this.detached = true;
    for (const {client, previous, hook} of this.changes.reverse()) {
      if (Object.getOwnPropertyDescriptor(client, 'send')?.value === hook) {
        if (previous) Object.defineProperty(client, 'send', previous);
        else delete client.send;
      }
      attached.delete(client);
    }
  }
}
module.exports = {NativeRequestBroker, preparedCredentials, chromiumPreparedTransport};
