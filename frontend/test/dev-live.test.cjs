const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const code = fs.readFileSync(require('node:path').join(__dirname, '../dev-live.js'), 'utf8');
function harness(responses) {
  const timers = [];
  let reloads = 0;
  const context = {
    window: {location: {reload() {reloads++;}}},
    fetch: async () => {
      const response = responses.shift();
      if (response instanceof Error) throw response;
      return {status: response.status || 200, ok: !response.status || response.status === 200, json: async () => response.body};
    },
    setTimeout(fn, delay) {timers.push({fn, delay});},
  };
  vm.runInNewContext(code, context);
  return {
    get reloads() {return reloads;}, timers,
    async tick() {await new Promise(setImmediate); if (timers.length) await timers.shift().fn(); await new Promise(setImmediate);},
    async flush() {await new Promise(setImmediate);},
  };
}
const state = (revision = 'a'.repeat(64), contextId = 'context-one') => ({body: {frontendRevision: revision, contextId}});
test('unchanged and 304 responses do not reload; changed frontend reloads once', async () => {
  const h = harness([state(), {status:304}, state(), state('b'.repeat(64))]);
  await h.flush(); await h.tick(); await h.tick();
  assert.equal(h.reloads, 0);
  await h.tick();
  assert.equal(h.reloads, 1);
  assert.equal(h.timers.length, 0);
});
test('maintenance and network failures back off; new Java context refreshes', async () => {
  const h = harness([state(), {status:503}, new Error('restarting'), state('a'.repeat(64), 'context-two')]);
  await h.flush(); await h.tick();
  assert.ok(h.timers[0].delay > 1000);
  await h.tick(); await h.tick();
  assert.equal(h.reloads, 1);
});
test('404 on a non-development server stops polling', async () => {
  const h = harness([{status:404}]); await h.flush();
  assert.equal(h.timers.length, 0);
  assert.equal(h.reloads, 0);
});
test('first valid response on a freshly loaded page establishes baseline', async () => {
  const h = harness([{status:503}, state('b'.repeat(64), 'new-context'), state('b'.repeat(64), 'new-context')]);
  await h.flush(); await h.tick(); await h.tick();
  assert.equal(h.reloads, 0);
});
