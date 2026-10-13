(function (root) {
  'use strict';
  function plan(items, shopId, key) {
    if (!items.length || !shopId || !key) throw Error('请选择商品和目标店铺');
    return [...new Map(items.map(item => [item.id, item])).values()].map((item, index) => {
      if (!item.id) throw Error('商品记录不完整，请刷新后重试');
      return { captureId: item.id, title: item.title, shopId, status: 'WAITING', idempotencyKey: `${key}-${index}` };
    });
  }
  function canChangeShop(item) {
    return !item.workflow?.id && !item.payload
      && ['WAITING', 'PREPARING', 'PREPARE_FAILED', 'PREPARED'].includes(item.status);
  }
  function changeShop(items, shopId, key) {
    if (!shopId || !key) throw Error('请选择目标店铺');
    if (!items.some(item => canChangeShop(item) && item.shopId !== shopId)) return items;
    return items.map((item, index) => canChangeShop(item) && item.shopId !== shopId
      ? {captureId: item.captureId, title: item.title, shopId, status: 'WAITING', idempotencyKey: `${key}-${index}`,
        ...(item.sizeChartImageKey ? {sizeChartImageKey:item.sizeChartImageKey} : {})}
      : item);
  }
  function withDeadline(operation, { timeoutMs = 30000, signal, timeoutMessage = '请求超时，请重试' } = {}) {
    return new Promise((resolve, reject) => {
      const controller = new AbortController();
      let settled = false;
      let timer;
      const finish = (callback, value) => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        signal?.removeEventListener('abort', abort);
        callback(value);
      };
      const abort = () => {
        const error = signal.reason || Object.assign(Error('请求已取消'), { name: 'AbortError' });
        controller.abort(error);
        finish(reject, error);
      };
      if (signal?.aborted) { abort(); return; }
      signal?.addEventListener('abort', abort, { once: true });
      const duration = Number(timeoutMs);
      timer = setTimeout(() => {
        const error = Object.assign(Error(timeoutMessage), { name: 'TimeoutError', status: 0 });
        controller.abort(error);
        finish(reject, error);
      }, Number.isFinite(duration) && duration >= 0 ? duration : 30000);
      // Bound the whole operation, including a fetch response's body and promises
      // that ignore abort. Abort alone cannot release a permanently hung worker.
      try {
        Promise.resolve(operation(controller.signal)).then(
          value => finish(resolve, value),
          error => finish(reject, error)
        );
      } catch (error) {
        finish(reject, error);
      }
    });
  }
  function isRetryableReadError(error) {
    if (!error || error.name === 'AbortError') return false;
    if (error.status !== undefined && error.status !== null) {
      return [0, 408, 429, 502, 503, 504].includes(Number(error.status));
    }
    return error.name === 'TimeoutError';
  }
  function readRetryAbortError() {
    return Object.assign(Error('当前操作已停止'), { name: 'AbortError', status: 0 });
  }
  function readRetryDelay(delayMs, signal) {
    return new Promise((resolve, reject) => {
      let timer;
      const abort = () => {
        clearTimeout(timer);
        signal?.removeEventListener('abort', abort);
        reject(readRetryAbortError());
      };
      if (signal?.aborted) { abort(); return; }
      signal?.addEventListener('abort', abort, { once: true });
      timer = setTimeout(() => {
        signal?.removeEventListener('abort', abort);
        resolve();
      }, delayMs);
    });
  }
  async function retryRead(operation, { onRetry, sleep = readRetryDelay, maxAttempts = 3, isCurrent = () => true, signal } = {}) {
    const requested = Number(maxAttempts);
    const attempts = Number.isFinite(requested) && requested >= 1 ? Math.floor(requested) : 3;
    const assertCurrent = () => {
      if (signal?.aborted || !isCurrent()) throw readRetryAbortError();
    };
    for (let attempt = 1; attempt <= attempts; attempt++) {
      assertCurrent();
      let result;
      try {
        result = await operation(attempt);
      } catch (error) {
        assertCurrent();
        if (attempt === attempts || !isRetryableReadError(error)) throw error;
        const delayMs = attempt === 1 ? 2000 : 5000;
        if (onRetry) await onRetry({ attempt: attempt + 1, maxAttempts: attempts, delayMs });
        assertCurrent();
        await sleep(delayMs, signal);
        assertCurrent();
        continue;
      }
      assertCurrent();
      return result;
    }
  }
  async function submit(items, { save, prepare, create, isCurrent, changed, concurrency = 3 }) {
    if (!isCurrent()) return;
    let nextIndex = 0;
    let stopClaims = false;
    let fatalError;
    const fail = error => {
      fatalError ||= error instanceof Error ? error : Error('无法保存上传任务，请重试');
      stopClaims = true;
    };
    const persist = () => {
      if (!isCurrent()) return false;
      try { save(items); } catch (error) { fail(error); throw fatalError; }
      return isCurrent();
    };
    const update = (item, values) => {
      if (!isCurrent()) return false;
      Object.assign(item, values);
      if (!persist()) return false;
      if (!isCurrent()) return false;
      changed();
      return isCurrent();
    };
    if (!persist()) return;
    async function submitItem(item) {
      let payload = item.payload;
      if (!item.payload) {
        if (!update(item, { status: 'PREPARING', error: '' })) return;
        try {
          payload = await prepare(item);
          if (!isCurrent()) return;
          if (!payload || payload.shopId !== item.shopId || payload.idempotencyKey !== item.idempotencyKey) {
            throw Error('自动准备的店铺或任务信息不一致，请重试');
          }
        } catch (error) {
          if (!isCurrent()) return;
          if (error.status === 401) stopClaims = true;
          update(item, { status: 'PREPARE_FAILED', error: error.message });
          return;
        }
      }
      if (!isCurrent() || fatalError) return;
      // Persist the exact body/key BEFORE submitting. Retries, including legacy
      // requests and lost responses, must never rebuild already-sent parameters.
      if (!update(item, { payload, status: 'SUBMITTING' })) return;
      if (!isCurrent() || fatalError) return;
      let workflow;
      try {
        workflow = await create(item.captureId, item.payload, item.idempotencyKey);
        if (!isCurrent()) return;
        if (!workflow?.id) throw Error('上传任务响应不完整，请重试');
      } catch (error) {
        if (!isCurrent()) return;
        if (error.status === 401) stopClaims = true;
        update(item, { status: 'CREATE_FAILED', error: error.message });
        return;
      }
      update(item, { workflow, status: workflow.status, error: '' });
    }
    async function worker() {
      try {
        while (!stopClaims && isCurrent() && nextIndex < items.length) {
          const item = items[nextIndex++];
          if (item.workflow?.id) continue;
          await submitItem(item);
        }
      } catch (error) { fail(error); }
    }
    const requested = Number(concurrency);
    const count = Number.isFinite(requested) && requested >= 1 ? Math.floor(requested) : 3;
    // Each worker owns both preparation and submission of one item. A slow item
    // occupies one slot only; accepted workflows are never submitted again.
    await Promise.all(Array.from({ length: Math.min(count, items.length) }, () => worker()));
    if (fatalError) throw fatalError;
  }
  const api = { plan, canChangeShop, changeShop, submit, withDeadline, retryRead, isRetryableReadError };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.ProductBulkUpload = api;
})(typeof window === 'undefined' ? globalThis : window);
