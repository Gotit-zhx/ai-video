/* Boundary between the shared portal and the independently mounted finance UI. */
(function (root, factory) {
  'use strict';
  const adapter = factory();
  if (typeof module === 'object' && module.exports) module.exports = adapter;
  else {
    root.FinanceHostAdapter = adapter;
    adapter.install({
      window: root,
      readUser() { try { return typeof currentUser === 'undefined' ? null : currentUser; } catch (_) { return null; } },
      expire() { if (typeof handleSessionExpired === 'function') handleSessionExpired(); }
    });
  }
})(typeof globalThis === 'object' ? globalThis : this, function () {
  'use strict';

  function createHost({window, readUser, expire}) {
    const document = window.document, listeners = new Set();
    let cached, notified, signature = '', disposed = false;
    function getSnapshot() {
      const user = readUser();
      const canManage = !!user && (user.platformAdmin === true || (user.roles || []).includes('ADMIN') || (user.permissions || []).includes('finance:manage'));
      const next = {
        organizationId: String(user?.organizationId || ''), userId: String(user?.userId || ''),
        canView: canManage || !!user?.permissions?.includes('finance:view'), canManage,
        locale: document.documentElement.lang || 'zh-CN',
        theme: document.documentElement.dataset.theme === 'dark' ? 'dark' : 'light'
      };
      const key = JSON.stringify(next);
      if (key !== signature) { cached = Object.freeze(next); signature = key; }
      return cached;
    }
    function notify() {
      if (disposed) return;
      const snapshot = getSnapshot();
      if (notified !== snapshot) { notified = snapshot; for (const listener of listeners) listener(snapshot); }
    }
    const observer = new window.MutationObserver(notify);
    observer.observe(document.documentElement, {attributes:true, attributeFilter:['lang','data-theme']});
    document.addEventListener('hoyee:languagechange', notify);
    return {
      getSnapshot,
      subscribe(listener) { notified = getSnapshot(); listeners.add(listener); return () => listeners.delete(listener); },
      navigate(route) {
        const value = String(route || 'overview').replace(/^\//, '');
        if (/^[a-z-]+(?:\?[^#]*)?$/.test(value)) window.location.hash = '#finance-' + value;
      },
      getLegacyUrl(filters) { return window.FinanceEntry.buildFinanceUrl(window.location,filters,'legacy'); },
      consumeAuthorizationNotice() {
        const notice = window.HoyeeFinanceAuthNotice || null;
        delete window.HoyeeFinanceAuthNotice;
        return notice;
      },
      onSessionExpired() { expire(); },
      notify,
      dispose() { disposed=true; observer.disconnect(); document.removeEventListener('hoyee:languagechange', notify); listeners.clear(); }
    };
  }

  async function loadModern({location, fetch, importModule}) {
    const base = new URL('/assets/finance-modern/', location.href);
    const response = await fetch(new URL('manifest.json', base).href, {credentials:'same-origin',cache:'no-store'});
    if (!response.ok) throw new Error('Finance assets unavailable');
    const manifest = await response.json();
    const entry = manifest?.['src/app/build-entry.ts'];
    const assets = Object.values(manifest || {});
    function assetUrl(file, extension) {
      if (typeof file !== 'string' || !/^assets\/[A-Za-z0-9_-]+\.[A-Za-z0-9]+$/.test(file) || !file.endsWith(extension)) throw new Error('Invalid finance asset');
      const url = new URL(file, base);
      if (url.origin !== base.origin || !url.pathname.startsWith(base.pathname)) throw new Error('Invalid finance asset origin');
      return url.href;
    }
    const entryUrl = assetUrl(entry?.isEntry === true ? entry.file : '', '.js');
    const styles = assets.filter(asset => typeof asset?.file === 'string' && asset.file.endsWith('.css'));
    if (styles.length !== 1) throw new Error('Invalid finance stylesheet');
    const cssUrl = assetUrl(styles[0].file, '.css');
    const stylesheet = await fetch(cssUrl, {credentials:'same-origin',cache:'no-store'});
    if (!stylesheet.ok) throw new Error('Finance stylesheet unavailable');
    const cssText = await stylesheet.text();
    const module = await importModule(entryUrl);
    if (typeof module?.mountFinanceRoot !== 'function') throw new Error('Invalid finance entry module');
    return {mountFinanceRoot:module.mountFinanceRoot, cssText};
  }

  async function loadLegacy({window, facade, loadScript}) {
    try {
      for (const item of window.document.querySelectorAll('script[data-finance-legacy-src]')) await loadScript(item.dataset.financeLegacySrc);
      const renderer = window.FinanceWorkspace;
      if (renderer === facade || typeof renderer?.enter !== 'function') throw new Error('Classic finance unavailable');
      return renderer;
    } finally { window.FinanceWorkspace = facade; }
  }

  function install(config) {
    const window = config.window, document = window.document;
    const container = document.getElementById('finance-page');
    if (!container || !window.FinanceEntry) return null;
    const host = createHost(config);
    const completedScripts = new Set();
    const loadScript = async src => {
      const url = new URL(src, window.location.href);
      if (url.origin !== window.location.origin || !/^\/finance-[a-z-]+\.js$/.test(url.pathname)) throw new Error('Invalid classic finance script');
      if (completedScripts.has(url.href)) return;
      await new Promise((resolve,reject) => {
        const script = document.createElement('script');
        script.src = url.href; script.async = false;
        const timer = window.setTimeout(() => { script.remove(); reject(new Error('Classic finance load timed out')); }, 15000);
        script.onload = () => { window.clearTimeout(timer); completedScripts.add(url.href); resolve(); };
        script.onerror = () => { window.clearTimeout(timer); script.remove(); reject(new Error('Classic finance load failed')); };
        document.head.append(script);
      });
    };
    const facade = window.FinanceEntry.createFacade({
      container, location:window.location, host,
      loadLegacy:config.loadLegacy || (() => loadLegacy({window,facade,loadScript})),
      loadModern:config.loadModern || (() => loadModern({location:window.location,fetch:window.fetch.bind(window),importModule:url=>import(url)}))
    });
    window.FinanceWorkspace = facade;
    const reset = () => { facade.reset(); window.queueMicrotask(() => host.notify()); };
    window.addEventListener('heyi:company-reset',reset);
    return {
      facade, host,
      dispose() { window.removeEventListener('heyi:company-reset',reset); facade.dispose(); host.dispose(); }
    };
  }
  return {createHost, loadModern, loadLegacy, install};
});
