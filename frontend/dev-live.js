/* The route exists only in the dev profile. No tokens, configuration or writes. */
(() => {
  'use strict';
  let baseline = null;
  let delay = 1000;
  async function poll() {
    try {
      const response = await fetch('/_dev/revision', {cache: 'no-store', credentials: 'same-origin'});
      if (response.status === 404 || response.status === 401 || response.status === 403) return;
      if (response.status === 304) {
        delay = 1000;
      } else if (response.ok) {
        const state = await response.json();
        if (typeof state.contextId !== 'string' || !/^[a-f0-9]{64}$/.test(state.frontendRevision)) {
          throw new Error('Revision unavailable');
        }
        const revision = state.frontendRevision + ':' + state.contextId;
        if (baseline !== null && baseline !== revision) {
          window.location.reload();
          return;
        }
        baseline = revision;
        delay = 1000;
      } else {
        delay = Math.min(delay * 2, 10000);
      }
    } catch (_) {
      delay = Math.min(delay * 2, 10000);
    }
    setTimeout(poll, delay);
  }
  poll();
})();
