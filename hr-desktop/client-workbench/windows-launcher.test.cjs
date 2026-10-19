const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const {once} = require('node:events');
const {launchWorkbenchProcess} = require('./windows-launcher.cjs');
const {evaluateRunning} = require('./inspector-driver.cjs');

test('the product launcher starts without an inspector and later uses its own loopback port', async t => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'heyi-launcher-port-'));
  let connection;
  t.after(async () => {
    connection?.client.close();
    if (connection && connection.child.exitCode === null && connection.child.signalCode === null) {
      connection.child.kill();
    }
    await fs.rm(root, {recursive: true, force: true});
  });
  const fixture = path.join(root, 'fixture.cjs');
  const ready = path.join(root, 'ready');
  await fs.writeFile(fixture, `
    globalThis.startedWithoutInspector=!require('node:inspector').url();
    globalThis.fixtureStartupCalls=1;
    require('node:fs').writeFileSync(process.argv[2],'ready');
    setInterval(()=>{},1000);
  `);
  connection = await launchWorkbenchProcess(process.execPath, [fixture, ready],
    {env: {...process.env}, cwd: root, waitUntilReady: async () => {
      const deadline = Date.now() + 5000;
      while (true) {
        try {await fs.stat(ready);return;}
        catch (error) {if (error.code !== 'ENOENT' || Date.now() > deadline) throw error;}
        await new Promise(resolve => setTimeout(resolve, 20));
      }
    }});
  const endpoint = new URL(connection.client.socket.url);
  assert.equal(endpoint.hostname, '127.0.0.1');
  assert.notEqual(endpoint.port, '9229');
  assert.equal(await evaluateRunning(connection, 'startedWithoutInspector'), true);
  assert.equal(await evaluateRunning(connection, 'fixtureStartupCalls'), 1);
  const exited = once(connection.child, 'exit');
  await evaluateRunning(connection, `setTimeout(()=>{
    process.mainModule.require('node:inspector').close();process.exit(0);
  },50);true`);
  connection.client.close();
  assert.equal((await exited)[0], 0);
});
