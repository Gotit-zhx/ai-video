const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const {launchInspecting, evaluateRunning} = require('./inspector-driver.cjs');
const {detachAndWait} = require('./windows-handoff.cjs');
const {waitForProcess} = require('./process-outcome.cjs');

test('normal startup disconnects first and verifies native inspector closure before handoff', async t => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'heyi-production-handoff-'));
  const launchId = 'b'.repeat(32), file = path.join(root, 'fixture.cjs');
  const modulePath = path.join(__dirname, 'windows-handoff.cjs');
  await fs.writeFile(file, `
    globalThis.ready=true;
    const hold=setInterval(()=>{},1000);
    globalThis.finish=()=>{
      require(${JSON.stringify(modulePath)}).finishNativeHandoff(${JSON.stringify(root)},${JSON.stringify(launchId)})
        .then(()=>{clearInterval(hold);}).catch(()=>{clearInterval(hold);process.exitCode=1;});
      return true;
    };
  `);
  const connection = await launchInspecting(process.execPath, [file]);
  t.after(async () => {
    connection.client.close();
    if (connection.child.exitCode === null && connection.child.signalCode === null) connection.child.kill();
    await fs.rm(root, {recursive: true, force: true});
  });
  while (!await evaluateRunning(connection, 'globalThis.ready===true')) {
    await new Promise(resolve => setTimeout(resolve, 10));
  }
  // A previous launch's request must not detach this process.
  await fs.writeFile(path.join(root, 'heyi-detach-request.json'), JSON.stringify({
    launchId: 'a'.repeat(32), nativePid: connection.child.pid,
  }));
  assert.equal(await evaluateRunning(connection, 'finish()'), true);
  await new Promise(resolve => setTimeout(resolve, 50));
  assert.equal(await evaluateRunning(connection, 'Boolean(process.mainModule.require("node:inspector").url())'), true);
  // Keep the fixture alive until the controller observes its completed handoff.
  await evaluateRunning(connection, 'setTimeout(()=>{},300);true');
  await detachAndWait(connection, root, launchId);
  const outcome = await waitForProcess(connection.child, 5000);
  assert.equal(outcome.code, 0);
  assert.equal(outcome.terminationRequested, false);
  const state = JSON.parse(await fs.readFile(path.join(root, 'heyi-detach-status.json'), 'utf8'));
  assert.deepEqual(state, {launchId, nativePid: connection.child.pid, inspectorClosed: true});
});

test('a stale success for a different PID never completes the startup handoff', async t => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'heyi-stale-handoff-'));
  t.after(() => fs.rm(root, {recursive: true, force: true}));
  const launchId = 'c'.repeat(32);
  await fs.writeFile(path.join(root, 'heyi-detach-status.json'), JSON.stringify({
    launchId, nativePid: 100, inspectorClosed: true,
  }));
  let disconnected = false;
  await assert.rejects(detachAndWait({
    child: {pid: 200, exitCode: null, signalCode: null}, client: {close() {disconnected = true;}},
  }, root, launchId, 1), /timed out/);
  assert.equal(disconnected, true);
});
