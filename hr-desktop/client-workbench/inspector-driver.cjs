// Test tooling only. No retries: a lost inspector response has an unknown outcome.
const {spawn} = require('node:child_process');

function localInspectorURL(value) {
  let url;
  try {url = new URL(value);} catch {throw Error('Invalid inspector address');}
  if (url.protocol !== 'ws:' || url.hostname !== '127.0.0.1' ||
      !/^\d+$/.test(url.port) || Number(url.port) < 1 || Number(url.port) > 65535 ||
      url.username || url.password || url.search || url.hash ||
      !/^\/[a-f0-9-]{16,64}$/i.test(url.pathname)) {
    throw Error('Inspector must use a numeric loopback address');
  }
  return url.href;
}

class Inspector {
  constructor(socket, timeout) {
    this.socket = socket;
    this.timeout = timeout;
    this.nextId = 0;
    this.pending = new Map();
    this.waiters = new Map();
    this.recent = new Map();
    this.closed = false;
    socket.addEventListener('message', event => {
      if (typeof event.data !== 'string' || event.data.length > 1024 * 1024) {
        this.fail(Error('Inspector response exceeds limit'));
        socket.close();
        return;
      }
      let message;
      try {message = JSON.parse(event.data);} catch {
        this.fail(Error('Invalid inspector response'));socket.close();return;
      }
      if (message.id) {
        const pending = this.pending.get(message.id);
        if (!pending) return;
        this.pending.delete(message.id);
        clearTimeout(pending.timer);
        if (message.error) pending.reject(Error(`Inspector command failed: ${pending.method}`));
        else pending.resolve(message.result || {});
      } else if (message.method) {
        // Keep only the one event needed by this driver, never console output.
        if (message.method !== 'Debugger.paused') return;
        const waiter = this.waiters.get(message.method);
        if (waiter) {
          this.waiters.delete(message.method);
          clearTimeout(waiter.timer);waiter.resolve(message.params);
        } else this.recent.set(message.method, message.params);
      }
    });
    socket.addEventListener('close', () => this.fail(Error('Inspector disconnected; outcome unknown')));
    socket.addEventListener('error', () => this.fail(Error('Inspector transport failed; outcome unknown')));
  }
  static async connect(address, timeout = 5000) {
    const socket = new WebSocket(localInspectorURL(address));
    await new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        socket.close();reject(Error('Inspector connection timed out'));
      }, timeout);
      socket.addEventListener('open', () => {clearTimeout(timer);resolve();}, {once: true});
      socket.addEventListener('error', () => {
        clearTimeout(timer);reject(Error('Inspector connection failed'));
      }, {once: true});
    });
    return new Inspector(socket, timeout);
  }
  command(method, params = {}) {
    if (this.closed) return Promise.reject(Error('Inspector is closed'));
    const id = ++this.nextId;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(Error(`Inspector command timed out; outcome unknown: ${method}`));
      }, this.timeout);
      this.pending.set(id, {resolve, reject, timer, method});
      try {this.socket.send(JSON.stringify({id, method, params}));}
      catch {clearTimeout(timer);this.pending.delete(id);reject(Error('Inspector send failed'));}
    });
  }
  event(method, timeout = this.timeout) {
    if (this.recent.has(method)) {
      const value = this.recent.get(method);this.recent.delete(method);
      return Promise.resolve(value);
    }
    if (this.closed) return Promise.reject(Error('Inspector is closed'));
    if (this.waiters.has(method)) return Promise.reject(Error('Duplicate inspector event waiter'));
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.waiters.delete(method);reject(Error(`Inspector event timed out: ${method}`));
      }, timeout);
      this.waiters.set(method, {resolve, reject, timer});
    });
  }
  fail(error) {
    this.closed = true;
    for (const map of [this.pending, this.waiters]) {
      for (const pending of map.values()) {clearTimeout(pending.timer);pending.reject(error);}
      map.clear();
    }
    this.recent.clear();
  }
  close() {
    this.fail(Error('Inspector closed'));
    this.socket.close();
  }
}

async function launchInspecting(executable, args = [], options = {}) {
  const {timeout = 15000, env = process.env, cwd, onSpawn, pauseOnStart = false} = options;
  const flag = pauseOnStart ? '--inspect-brk=127.0.0.1:0' : '--inspect=127.0.0.1:0';
  const child = spawn(executable, [flag, ...args], {
    cwd, env, stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true,
  });
  try {
    onSpawn?.(child);
    const address = await new Promise((resolve, reject) => {
      let buffer = '';
      const timer = setTimeout(() => finish(Error('No local inspector endpoint before timeout')), timeout);
      function finish(error, result) {
        clearTimeout(timer);
        child.removeListener('error', onError);child.removeListener('exit', onExit);
        child.stderr.removeListener('data', onData);
        if (error) reject(error);else resolve(result);
      }
      const onError = () => finish(Error('Failed to start inspected process'));
      const onExit = () => finish(Error('Inspected process exited before attaching'));
      const onData = data => {
        buffer += data.toString('utf8');
        if (buffer.length > 128 * 1024) {
          finish(Error('Inspector startup output exceeds limit'));return;
        }
        const match = buffer.match(/Debugger listening on (ws:\/\/[^\s]+)/);
        if (match) {
          try {finish(null, localInspectorURL(match[1]));} catch (error) {finish(error);}
        }
      };
      child.once('error', onError);child.once('exit', onExit);
      child.stderr.on('data', onData);
    });
    // Drain vendor logs without retaining or printing them.
    child.stdout.resume();child.stderr.resume();
    const client = await Inspector.connect(address, Math.min(timeout, 5000));
    try {
      if (!pauseOnStart) return {child, client};
      await client.command('Debugger.enable');
      const paused = client.event('Debugger.paused', timeout);
      // Attach rejection immediately, before the protocol command can fail.
      paused.catch(() => {});
      await client.command('Runtime.runIfWaitingForDebugger');
      const state = await paused;
      const frame = state.callFrames?.[0]?.callFrameId;
      if (!frame) throw Error('Missing entry call frame');
      return {child, client, frame};
    } catch (error) {client.close();throw error;}
  } catch (error) {
    child.stdout.resume();child.stderr.resume();
    if (child.exitCode === null) child.kill();
    throw error;
  }
}

function launchPaused(executable, args = [], options = {}) {
  return launchInspecting(executable, args, {...options, pauseOnStart: true});
}

async function evaluateEntry(connection, expression) {
  const response = await connection.client.command('Debugger.evaluateOnCallFrame', {
    callFrameId: connection.frame, expression, returnByValue: true,
  });
  if (response.exceptionDetails) throw Error('Entry evaluation failed');
  return response.result?.value;
}

async function evaluateRunning(connection, expression) {
  const response = await connection.client.command('Runtime.evaluate', {expression, returnByValue: true});
  if (response.exceptionDetails) throw Error('Runtime evaluation failed');
  return response.result?.value;
}

module.exports = {localInspectorURL, Inspector, launchPaused, launchInspecting, evaluateEntry, evaluateRunning};
