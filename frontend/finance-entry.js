/* Transitional finance entry contract. Only this adapter chooses a renderer. */
(function (root, factory) {
  'use strict';
  const api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.FinanceEntry = api;
})(typeof globalThis === 'object' ? globalThis : this, function () {
  'use strict';
  const filterKeys = ['start','end','shopId','groupId','platform','currency','displayCurrency'];
  const routes = new Set(['overview','live','settlements','reconciliation','orders','fees','fee-records','expense-ledger','costs','ads','shops','qianyi']);
  const languages = new Set(['zh','en','ms','id','th','vi','fil']);

  function modeFromSearch(search) {
    return new URLSearchParams(search || '').get('financeUi') === 'modern' ? 'modern' : 'legacy';
  }
  function date(value) {
    if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
    const parsed = new Date(value + 'T12:00:00Z');
    return Number.isFinite(parsed.getTime()) && parsed.toISOString().slice(0, 10) === value;
  }
  function cleanFilters(input) {
    const result = {};
    for (const key of filterKeys) {
      const value = input && input[key];
      if (typeof value !== 'string') continue;
      if ((key === 'start' || key === 'end') && date(value)) result[key] = value;
      else if ((key === 'shopId' || key === 'groupId') && /^[A-Za-z0-9_-]{0,128}$/.test(value)) result[key] = value;
      else if (key === 'platform' && ['', 'SHOPEE', 'TIKTOK_SHOP'].includes(value)) result[key] = value;
      else if (key === 'currency' && /^(?:[A-Z]{3})?$/.test(value)) result[key] = value;
      else if (key === 'displayCurrency' && ['CNY','ORIGINAL'].includes(value)) result[key] = value;
    }
    if (result.start && result.end && result.start > result.end) {
      delete result.start; delete result.end;
    }
    return result;
  }
  function readInitialFilters(search) {
    return cleanFilters(Object.fromEntries(new URLSearchParams(search || '')));
  }
  function buildFinanceUrl(location, filters, mode) {
    if (!['modern','legacy'].includes(mode)) throw new TypeError('Unknown finance interface');
    const current = new URL(location.href || String(location));
    const query = new URLSearchParams();
    const language = current.searchParams.get('lang');
    if (languages.has(language)) query.set('lang', language);
    query.set('financeUi', mode);
    for (const [key, value] of Object.entries(cleanFilters(filters))) query.set(key, value);
    return current.pathname + '?' + query.toString() + '#finance-overview';
  }
  function addExperienceLink(parent, location, filters, enabled) {
    if (!enabled) return null;
    let link = parent.querySelector('[data-finance-modern-entry]');
    if (!link) {
      link = parent.ownerDocument.createElement('a');
      link.dataset.financeModernEntry = '';
      link.className = 'fi-action-link fi-modern-entry';
      link.textContent = '体验新版财务 BI ↗';
      link.target = '_blank';
      link.rel = 'noopener noreferrer';
      parent.append(link);
    }
    link.href = buildFinanceUrl(location, filters, 'modern');
    return link;
  }
  function validRoute(route) {
    const value = String(route || 'overview').replace(/^\//, '');
    return routes.has(value.split('?')[0]) ? value : 'overview';
  }
  function createFacade(config) {
    const { container, location, host } = config;
    const mode = modeFromSearch(location.search);
    let active = false, epoch = 0, route = 'overview', destroyed = false;
    let legacy, legacyPromise, legacySeeded = false, modernPromise, modernModule, modern, modernHost;
    let initialFilters = readInitialFilters(location.search);

    function options() { return {host,initialFilters,route,active}; }
    function removeModern() {
      if (modern) modern.dispose();
      modern = null;
      modernHost?.remove(); modernHost = null;
    }
    function failure() {
      removeModern(); container.replaceChildren();
      const notice = container.ownerDocument.createElement('div');
      notice.setAttribute('role','alert'); notice.className = 'fi-state is-error';
      notice.textContent = mode === 'modern'
        ? '新版财务暂时无法加载，请继续使用旧版财务。'
        : '财务界面暂时无法加载，请刷新页面重试。';
      const link = container.ownerDocument.createElement('a');
      link.href = buildFinanceUrl(location, initialFilters, 'legacy');
      link.textContent = '返回旧版财务 BI';
      notice.append(' ', link); container.append(notice);
    }
    async function ensureLegacy() {
      if (!legacyPromise) legacyPromise = Promise.resolve().then(config.loadLegacy).then(value => {
        legacy = value; return value;
      }).catch(error => { legacyPromise = null; throw error; });
      return legacyPromise;
    }
    async function ensureModern() {
      if (!modernPromise) modernPromise = Promise.resolve().then(config.loadModern).then(value => {
        if (typeof value?.mountFinanceRoot !== 'function') throw new Error('Modern finance entry is unavailable');
        modernModule = value; return value;
      }).catch(error => { modernPromise = null; throw error; });
      return modernPromise;
    }
    const facade = {
      allowed() { host.notify?.(); return !!host.getSnapshot().canView; },
      canManage() { return !!host.getSnapshot().canManage; },
      async enter(nextRoute) {
        if (destroyed || !facade.allowed()) return false;
        active = true; route = validRoute(nextRoute);
        const generation = ++epoch;
        try {
          if (mode === 'legacy') {
            const renderer = await ensureLegacy();
            if (!active || generation !== epoch || destroyed || !facade.allowed()) return false;
            renderer.enter(route, legacySeeded ? undefined : initialFilters);
            legacySeeded = true;
          } else {
            await ensureModern();
            if (!active || generation !== epoch || destroyed || !facade.allowed()) return false;
            if (!modern) {
              modernHost = container.ownerDocument.createElement('div');
              modernHost.dataset.financeModernHost = '';
              container.replaceChildren(modernHost);
              modern = modernModule.mountFinanceRoot(modernHost, options());
              if (modernModule.cssText && modern.surface?.styleContainer) {
                const style = container.ownerDocument.createElement('style');
                style.dataset.financeCompiledStyles = '';
                style.textContent = modernModule.cssText;
                modern.surface.styleContainer.append(style);
              }
            } else modern.render(options());
          }
          return true;
        } catch (_error) {
          if (active && generation === epoch && !destroyed) failure();
          return false;
        }
      },
      leave() {
        active = false; epoch++;
        if (mode === 'legacy') legacy?.leave();
        else modern?.render(options());
      },
      reset() {
        active = false; epoch++; initialFilters = {}; legacySeeded = false;
        if (mode === 'legacy') legacy?.reset();
        removeModern();
      },
      dispose() {
        facade.reset(); destroyed = true;
      }
    };
    return facade;
  }
  return {modeFromSearch,cleanFilters,readInitialFilters,buildFinanceUrl,addExperienceLink,createFacade,enabled:true};
});
