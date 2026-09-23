(function (root) {
  'use strict';

  const PREFIX = 'heyi:bulk-upload:batch:';
  const WAITING = new Set(['WAITING', 'PREPARED']);
  const FAILED = new Set(['PREPARE_FAILED', 'CREATE_FAILED', 'FAILED', 'REVIEW_REQUIRED', 'CANCELLED', 'CANCELED']);

  function storageKey(organizationId, batchId) {
    if (typeof organizationId !== 'string' || !organizationId.trim()
      || typeof batchId !== 'string' || !batchId.trim()) throw Error('批次和公司信息不能为空');
    return `${PREFIX}${encodeURIComponent(organizationId)}:${encodeURIComponent(batchId)}`;
  }

  function timestamp(value) {
    const number = typeof value === 'number' ? value : typeof value === 'string' && value ? Date.parse(value) : 0;
    return Number.isFinite(number) && number > 0 ? number : 0;
  }

  function updatedTime(record) {
    return timestamp(record.updatedAt) || timestamp(record.createdAt);
  }

  function list(storage, organizationId) {
    if (!storage || typeof organizationId !== 'string' || !organizationId.trim()) return [];
    const currentPrefix = `${PREFIX}${encodeURIComponent(organizationId)}:`;
    const legacyPrefix = `heyi:bulk-upload:${organizationId}:`;
    const found = new Map();
    let count;
    try { count = storage.length; } catch (_error) { return []; }
    for (let index = 0; index < count; index++) {
      try {
        const key = storage.key(index);
        if (typeof key !== 'string') continue;
        const current = key.startsWith(currentPrefix);
        if (!current && !key.startsWith(legacyPrefix)) continue;
        const suffix = key.slice((current ? currentPrefix : legacyPrefix).length);
        if (!suffix) continue;
        const saved = JSON.parse(storage.getItem(key) || 'null');
        if (!saved || saved.organizationId !== organizationId || !Array.isArray(saved.items)
          || !saved.items.length || saved.items.some(item => !item || typeof item !== 'object' || Array.isArray(item))) continue;
        const keyBatchId = current ? decodeURIComponent(suffix) : '';
        if (current && saved.batchId && saved.batchId !== keyBatchId && saved.batchId !== key) continue;
        const batchId = current ? keyBatchId : typeof saved.batchId === 'string' && saved.batchId ? saved.batchId : `legacy:${suffix}`;
        const record = {...saved, batchId, key, storageKey: key, legacy: !current};
        const previous = found.get(batchId);
        if (!previous || previous.legacy && current
          || previous.legacy === record.legacy && updatedTime(record) > updatedTime(previous)) {
          found.set(batchId, record);
        }
      } catch (_error) { /* One damaged or inaccessible record must not hide other batches. */ }
    }
    return [...found.values()].sort((left, right) =>
      updatedTime(right) - updatedTime(left)
      || timestamp(right.createdAt) - timestamp(left.createdAt)
      || left.key.localeCompare(right.key));
  }

  function summary(record) {
    const items = Array.isArray(record?.items) ? record.items : [];
    const result = {total: items.length, waiting: 0, running: 0, succeeded: 0, failed: 0, status: 'EMPTY'};
    for (const item of items) {
      const state = String(item?.workflow?.status || item?.status || 'WAITING').toUpperCase();
      if (state === 'SUCCEEDED') result.succeeded++;
      else if (FAILED.has(state)) result.failed++;
      else if (WAITING.has(state) && !item?.workflow?.id) result.waiting++;
      else result.running++;
    }
    result.status = !result.total ? 'EMPTY' : result.running ? 'RUNNING' : result.waiting ? 'WAITING'
      : result.failed ? 'FAILED' : 'SUCCEEDED';
    return result;
  }

  function escapeHtml(value) {
    return String(value ?? '').replace(/[&<>"']/g, character => ({'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'}[character]));
  }

  function timeLabel(record) {
    const time = timestamp(record.createdAt) || timestamp(record.updatedAt);
    return time ? new Date(time).toLocaleString((globalThis.HoyeeLanguage?.locale || "zh-CN"), {year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false}) : '历史批次';
  }

  function render(records) {
    if (!Array.isArray(records) || !records.length) return '<p class="product-bulk-history-empty">暂无批量任务。勾选商品后即可发起批量上传。</p>';
    return records.map(record => {
      const counts = summary(record);
      const label = {EMPTY: '暂无商品', WAITING: '待准备', RUNNING: '进行中', SUCCEEDED: '全部成功', FAILED: counts.succeeded ? '部分失败' : '需要处理'}[counts.status];
      const names = (record.items || []).slice(0, 2).map(item => item.title || '未命名商品').join('、');
      const caption = names + (counts.total > 2 ? ` 等 ${counts.total} 件商品` : '');
      return `<article class="product-bulk-history-row" role="listitem">
        <div class="product-bulk-history-heading"><strong>${escapeHtml(timeLabel(record))}</strong><span class="product-bulk-history-status is-${counts.status.toLowerCase()}">${escapeHtml(label)}</span></div>
        <p class="product-bulk-history-products">${escapeHtml(caption)}</p>
        <div class="product-bulk-history-footer"><dl class="product-bulk-history-counts" aria-label="批次商品数量"><div><dt>共计</dt><dd>${counts.total}</dd></div><div class="is-success"><dt>成功</dt><dd>${counts.succeeded}</dd></div><div class="is-failed"><dt>失败</dt><dd>${counts.failed}</dd></div><div><dt>进行中</dt><dd>${counts.running}</dd></div><div><dt>待准备</dt><dd>${counts.waiting}</dd></div></dl><button type="button" data-product-bulk-history-key="${escapeHtml(record.key || record.storageKey)}" aria-label="查看${escapeHtml(timeLabel(record))}批次详情">查看详情</button></div>
      </article>`;
    }).join('');
  }

  const api = {storageKey, list, summary, render};
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.ProductBulkHistory = api;
})(typeof window === 'undefined' ? globalThis : window);
