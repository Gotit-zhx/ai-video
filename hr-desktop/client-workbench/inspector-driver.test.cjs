const assert = require('node:assert/strict');
const {test} = require('node:test');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const {once} = require('node:events');
const {localInspectorURL, launchPaused, launchInspecting, evaluateEntry,
  evaluateRunning} = require('./inspector-driver.cjs');

test('inspector accepts only an explicit numeric loopback endpoint', () => {
  const token = '11111111-2222-3333-4444-555555555555';
  assert.equal(localInspectorURL(`ws://127.0.0.1:12345/${token}`),
    `ws://127.0.0.1:12345/${token}`);
  for (const value of [
    `ws://localhost:12345/${token}`, `ws://example.com:12345/${token}`,
    `wss://127.0.0.1:12345/${token}`, `ws://127.0.0.1:0/${token}`,
    `ws://user@127.0.0.1:12345/${token}`, `ws://127.0.0.1:12345/${token}?x=y`,
    `ws://127.0.0.1:12345/${token}#x`, 'ws://127.0.0.1:12345/',
  ]) assert.throws(() => localInspectorURL(value));
});

test('real Node entry is paused, evaluated once, and resumed without modifying source', async t => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'heyi-inspector-fixture-'));
  t.after(() => fs.rm(root, {recursive: true, force: true}));
  const fixture = path.join(root, 'fixture.cjs');
  const output = path.join(root, 'result.json');
  const source = `const fs = require('node:fs');
fs.writeFileSync(process.argv[2], JSON.stringify({injected: globalThis.heyiFixture || 0}));
`;
  await fs.writeFile(fixture, source);
  const connection = await launchPaused(process.execPath, [fixture, output]);
  t.after(() => {connection.client.close();connection.child.kill();});
  await assert.rejects(fs.stat(output), {code: 'ENOENT'});
  assert.deepEqual(await evaluateEntry(connection,
    'globalThis.heyiFixture = (globalThis.heyiFixture || 0) + 1; ({count: globalThis.heyiFixture, requireType: typeof require})'),
  {count: 1, requireType: 'function'});
  const exited = once(connection.child, 'exit');
  await connection.client.command('Debugger.resume');
  connection.client.close();
  await exited;
  assert.deepEqual(JSON.parse(await fs.readFile(output)), {injected: 1});
  assert.equal(await fs.readFile(fixture, 'utf8'), source);
});

test('entry exceptions fail without resuming or repeating the expression', async t => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'heyi-inspector-error-'));
  t.after(() => fs.rm(root, {recursive: true, force: true}));
  const fixture = path.join(root, 'fixture.cjs');
  await fs.writeFile(fixture, 'setInterval(() => {}, 1000);');
  const connection = await launchPaused(process.execPath, [fixture]);
  t.after(() => {connection.client.close();connection.child.kill();});
  await assert.rejects(evaluateEntry(connection,
    'globalThis.heyiFixture = (globalThis.heyiFixture || 0) + 1; throw Error("fixture")'),
  /Entry evaluation failed/);
  assert.equal(await evaluateEntry(connection, 'globalThis.heyiFixture'), 1);
  connection.client.close();
  await assert.rejects(evaluateEntry(connection, 'globalThis.heyiFixture++'), /closed/);
});

test('startup failure and missing inspector are bounded', async t => {
  await assert.rejects(launchPaused(path.join(os.tmpdir(), 'heyi-missing-executable')),
    /Failed to start/);
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'heyi-inspector-timeout-'));
  t.after(() => fs.rm(root, {recursive: true, force: true}));
  // --version exits without opening the requested inspector.
  await assert.rejects(launchPaused(process.execPath, ['--version'], {timeout: 1500}),
    /exited before attaching|No local inspector endpoint/);
});

test('a timed-out evaluation is never repeated', async t => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'heyi-inspector-once-'));
  t.after(() => fs.rm(root, {recursive: true, force: true}));
  const fixture = path.join(root, 'fixture.cjs'), output = path.join(root, 'calls.txt');
  await fs.writeFile(fixture, 'setInterval(() => {}, 1000);');
  const connection = await launchPaused(process.execPath, [fixture]);
  t.after(() => {connection.client.close();connection.child.kill();});
  connection.client.timeout = 150;
  await assert.rejects(evaluateEntry(connection,
    `require('node:fs').appendFileSync(${JSON.stringify(output)}, 'x'); while (true) {}`),
  /timed out; outcome unknown/);
  assert.equal(await fs.readFile(output, 'utf8'), 'x');
});

test('disconnect rejects a pending call without reconnecting', async t => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'heyi-inspector-disconnect-'));
  t.after(() => fs.rm(root, {recursive: true, force: true}));
  const fixture = path.join(root, 'fixture.cjs');
  await fs.writeFile(fixture, 'setInterval(() => {}, 1000);');
  const connection = await launchPaused(process.execPath, [fixture]);
  t.after(() => {connection.client.close();connection.child.kill();});
  const pending = evaluateEntry(connection, 'while (true) {}');
  const rejected = assert.rejects(pending, /(?:disconnected|transport failed); outcome unknown/);
  connection.child.kill();
  await rejected;
});

test('running-process observation uses the real main module without changing source', async t => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'heyi-inspector-running-'));
  t.after(() => fs.rm(root, {recursive: true, force: true}));
  const fixture = path.join(root, 'fixture.cjs');
  const ready = path.join(root, 'ready');
  const source = 'globalThis._sdk_fixture = () => {}; require("node:fs").writeFileSync(process.argv[2], "ready"); setInterval(() => {}, 1000);';
  await fs.writeFile(fixture, source);
  const connection = await launchInspecting(process.execPath, [fixture, ready]);
  t.after(() => {connection.client.close();connection.child.kill();});
  // The inspector may accept commands before Node has run its entry module.
  // Use a signal written by the real fixture instead of a timing assumption.
  const deadline = Date.now() + 5000;
  while (true) {
    try {await fs.stat(ready);break;} catch (error) {
      if (error.code !== 'ENOENT' || Date.now() >= deadline) throw error;
      await new Promise(resolve => setTimeout(resolve, 25));
    }
  }
  assert.equal(await evaluateRunning(connection,
    'typeof process.mainModule.require("node:fs").readFileSync'), 'function');
  assert.equal(await evaluateRunning(connection, 'typeof globalThis._sdk_fixture'), 'function');
  await assert.rejects(evaluateRunning(connection, 'throw Error("fixture")'), /Runtime evaluation failed/);
  assert.equal(await fs.readFile(fixture, 'utf8'), source);
});

test('late activation observes code that ran before the inspector started', async t => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'heyi-inspector-late-'));
  t.after(() => fs.rm(root, {recursive: true, force: true}));
  const fixture = path.join(root, 'fixture.cjs'), ready = path.join(root, 'ready');
  await fs.writeFile(fixture,
    'globalThis.heyiBeforeInspector = 1; require("node:fs").writeFileSync(process.argv[2],"ready"); setInterval(()=>{},1000);');
  const connection = await launchInspecting(process.execPath, [fixture, ready], {
    activate: async child => {
      const deadline = Date.now() + 5000;
      while (true) {
        try {await fs.stat(ready);break;} catch (error) {
          if (error.code !== 'ENOENT' || Date.now() >= deadline) throw error;
          await new Promise(resolve => setTimeout(resolve, 25));
        }
      }
      if (child.exitCode !== null) throw Error('Fixture already exited');
      process._debugProcess(child.pid);
    },
  });
  t.after(() => {connection.client.close();connection.child.kill();});
  assert.equal(await evaluateRunning(connection, 'globalThis.heyiBeforeInspector'), 1);
});
