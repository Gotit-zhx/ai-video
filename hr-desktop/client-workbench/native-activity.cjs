const crypto = require('node:crypto');
const {NativeResume, isChatURL} = require('./native-resume.cjs');

// This is a wake-up hint, never a receipt or permission to send. The worker
// re-reads the official conversation under its existing pacing and job checks.
// Observe the current page's own local storage callback and unread observable;
// do not open another socket or fetch messages to discover activity.
function installActivityPage(token) {
  const previous = window.__heyiIncomingActivity;
  if (previous?.token === token) return {ok: true};
  if (previous) return {ok: false};
  const root = window.iBossRoot, store = root?.messageStore;
  const own = () => String(root?.user?.get('userId') || '');
  const id = value => (typeof value !== 'number' || Number.isSafeInteger(value)) &&
    /^[1-9]\d{0,29}$/.test(String(value || '')) ? String(value) : '';
  if (!id(own()) || !store || typeof store.add !== 'function') return {ok: false};
  const descriptor = Object.getOwnPropertyDescriptor(store, 'add'), original = store.add;
  if (descriptor?.configurable === false) return {ok: false};
  const account = own(), seen = new Set(), queue = [];
  const state = {token, closed: false, drain: null, waiter: null, timer: null};
  const active = () => !state.closed && window.__heyiIncomingActivity === state &&
    window.iBossRoot === root && own() === account;
  const flush = () => {
    if (!state.waiter) return;
    const resolve = state.waiter;state.waiter = null;clearTimeout(state.timer);
    resolve({ok: active(), hints: active() ? queue.splice(0, 128) : []});
  };
  const notify = rows => {
    if (!active() || !Array.isArray(rows) || rows.length > 256) return;
    for (const row of rows) {
      if (!row || row.isSelf === true || row.newSubmit === true || row.typeSource === 'newSubmit') continue;
      const raw = row.from && row.to;
      const from = id(raw ? row.from.uid : row.fromId), to = id(raw ? row.to.uid : row.toId);
      const mid = id(row.mid);
      let time = Number(row.time);
      if (time > 1e12) time /= 1000;
      if (!from || from === account || to !== account || !mid || !Number.isFinite(time) ||
          time <= 0 || time > Date.now() / 1000 + 60) continue;
      // The same packet may reach both callbacks; keep only bounded identifiers.
      const key = from + ':' + mid;
      if (seen.has(key)) continue;
      seen.add(key);
      if (seen.size > 512) seen.delete(seen.values().next().value);
      queue.push({fromId: from, toId: to, mid, time, isSelf: false});
      if (queue.length > 256) queue.shift();
    }
    if (queue.length) flush();
  };
  const hook = function(...args) {
    const result = Reflect.apply(original, this, args);
    // Observation failure must never break the original page's message store.
    try {notify(Array.isArray(args[0]) ? args[0] : [args[0]]);} catch {}
    return result;
  };
  let subscription;
  state.close = () => {
    if (state.closed) return;
    state.closed = true;queue.length = 0;seen.clear();flush();clearTimeout(state.timer);
    try {subscription?.unsubscribe();} catch {}
    if (store.add === hook) {
      if (descriptor) Object.defineProperty(store, 'add', descriptor);
      else delete store.add;
    }
    if (window.__heyiIncomingActivity === state) delete window.__heyiIncomingActivity;
  };
  state.drain = () => {
    if (!active()) {state.close();return {ok: false, hints: []};}
    if (queue.length) return {ok: true, hints: queue.splice(0, 128)};
    if (state.waiter) return {ok: false, hints: []};
    return new Promise(resolve => {
      state.waiter = resolve;
      // Local memory only; no BOSS request is issued by this timeout.
      state.timer = setTimeout(flush, 20000);
    });
  };
  window.__heyiIncomingActivity = state;
  try {
    Object.defineProperty(store, 'add', {configurable: true, writable: true, value: hook});
    const unread = root.chat?.lastBossUnreadMsg$;
    if (typeof unread?.subscribe === 'function') {
      subscription = unread.subscribe(row => {try {notify([row]);} catch {}});
    }
    return {ok: true};
  } catch {state.close();return {ok: false};}
}

function drainActivityPage(token) {
  const state = window.__heyiIncomingActivity;
  return state?.token === token ? state.drain() : {ok: false, hints: []};
}
function closeActivityPage(token) {
  const state = window.__heyiIncomingActivity;
  if (state?.token === token) state.close();
}

class NativeActivity {
  constructor({nativeWindow, allContents, receive}) {
    this.locator = new NativeResume({nativeWindow, allContents});
    this.receive = receive;
    this.token = crypto.randomBytes(24).toString('hex');
    this.stopped = false;
  }
  execute(frame, fn) {
    if (!isChatURL(frame.url)) throw Error('Original chat frame changed');
    return frame.executeJavaScript(`(${fn.toString()})(${JSON.stringify(this.token)})`);
  }
  start() {
    if (this.started || this.stopped) return;
    this.started = true;
    void this.step();
  }
  async step() {
    let delay = 10000;
    try {
      const frame = this.locator.frame();
      if (this.frame !== frame) {
        await this.detach();
        if (this.stopped) return;
        this.frame = frame;
      }
      const installed = await this.execute(frame, installActivityPage);
      if (this.stopped) {await this.detach();return;}
      if (installed?.ok !== true) return;
      const result = await this.execute(frame, drainActivityPage);
      if (!this.stopped && this.frame === frame && this.locator.frame() === frame &&
          result?.ok === true && Array.isArray(result.hints) && result.hints.length <= 128) {
        this.receive(result.hints);
        delay = 0;
      }
    } catch {} finally {
      if (!this.stopped) this.timer = setTimeout(() => {void this.step();}, delay);
    }
  }
  async detach() {
    const frame = this.frame;this.frame = null;
    if (frame) try {await this.execute(frame, closeActivityPage);} catch {}
  }
  async close() {
    this.stopped = true;clearTimeout(this.timer);
    await this.detach();
  }
}
module.exports = {NativeActivity, installActivityPage, drainActivityPage, closeActivityPage};
