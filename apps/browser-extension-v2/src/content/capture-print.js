(() => {
  'use strict';
  const collector = globalThis.HeyiCollector;
  const adapter = collector?.adapters?.find(candidate => candidate.matchesPage());
  if (!adapter) return;
  let control;
  let currentUrl;

  async function collect(url) {
    const product = await adapter.extract(document.querySelector('main') || document.body, url, 'DETAIL');
    if (location.href !== url || adapter.blockedReason()) throw new Error('商品页面已变化，请重新采集');
    const images = collector.planListingImages([
      ...product.images.filter(image => ['MAIN', 'GALLERY', 'VARIANT'].includes(image.role)),
      ...product.skus.map(sku => ({url: sku.imageUrl, role: 'VARIANT'}))
    ], 20).images.map(image => image.url);
    if (!product.title || !images.length) throw new Error('未读取到商品图片，请等详情页加载完成后重试');
    return {title: product.title, images, product};
  }

  async function run(button, notice, url) {
    button.disabled = true;
    button.textContent = '正在采集…';
    notice.textContent = '';
    try {
      if (adapter.blockedReason()) throw new Error('请完成商品页验证后重试');
      const auth = await chrome.runtime.sendMessage({type: 'HEYI_GET_STATE', refresh: true});
      if (!auth?.data?.authenticated) throw new Error(auth?.error?.message || '请先登录赫逸印花采集插件');
      await globalThis.HeyiCaptureBridge?.activate?.().catch(() => false);
      const payload = await collect(url);
      const result = await chrome.runtime.sendMessage({type: 'HEYI_CAPTURE_PRINT_SOURCE', payload});
      if (!result?.ok) throw new Error(result?.error?.message || '采集失败，请重试');
      notice.textContent = '已采集商品';
      button.textContent = '重新采集';
    } catch (error) {
      notice.textContent = error.message;
      button.textContent = '采集印花图 v2.0';
    } finally {
      button.disabled = false;
    }
  }

  function sync() {
    const url = location.href;
    if (url !== currentUrl) {
      control?.remove();
      control = null;
      currentUrl = url;
    }
    if (control?.isConnected || !adapter.isDetailUrl(url) || !document.body || adapter.blockedReason()) return;
    control = document.createElement('span');
    control.dataset.heyiPrintCollector = '';
    control.style.cssText = 'all:initial;position:fixed;right:18px;top:calc(44% + 48px);z-index:2147483646;';
    const shadow = control.attachShadow({mode: 'closed'});
    const style = document.createElement('style');
    style.textContent = `
      :host { all: initial; }
      button { display:block; padding:10px 14px; border:1px solid #ffffff80; border-radius:999px; background:#18181b; color:#fff; font:600 14px/1.1 system-ui,sans-serif; cursor:pointer; }
      button:disabled { opacity:.7; cursor:wait; }
      button:focus-visible { outline:2px solid #478af4; outline-offset:2px; }
      p { max-width:190px; margin:6px 0 0; border-radius:6px; padding:7px 9px; background:#18181b; color:#fff; font:13px/1.4 system-ui,sans-serif; }
      p:empty { display:none; }
    `;
    const button = document.createElement('button');
    button.type = 'button';
    button.textContent = '采集印花图 v2.0';
    const notice = document.createElement('p');
    notice.setAttribute('role', 'status');
    button.addEventListener('click', event => {
      if (!event.isTrusted || button.disabled) return;
      event.preventDefault();
      event.stopPropagation();
      void run(button, notice, url);
    });
    shadow.append(style, button, notice);
    document.body.append(control);
  }
  sync();
  window.setInterval(sync, 1200);
})();
