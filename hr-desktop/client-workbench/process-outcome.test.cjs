const {test} = require('node:test');
const assert = require('node:assert/strict');
const {spawn} = require('node:child_process');
const {waitForProcess} = require('./process-outcome.cjs');
test('natural process failure remains distinct from harness termination', async () => {
  const child = spawn(process.execPath, ['-e', 'process.exit(3)'], {stdio: 'ignore'});
  const result = await waitForProcess(child, 5000);
  assert.equal(result.code, 3);
  assert.equal(result.timedOut, false);
  assert.equal(result.terminationRequested, false);
  assert.equal((await waitForProcess(child)).code, 3);
});
test('deadline termination is explicitly recorded for the owned worker', async t => {
  const child = spawn(process.execPath, ['-e', 'setInterval(()=>{},1000)'], {stdio: 'ignore'});
  t.after(() => child.kill());
  const result = await waitForProcess(child, 100);
  assert.equal(result.timedOut, true);
  assert.equal(result.terminationRequested, true);
  assert.ok(result.code !== null || result.signal !== null);
});
