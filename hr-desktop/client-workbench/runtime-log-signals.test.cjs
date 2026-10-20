const {test} = require('node:test');
const assert = require('node:assert/strict');
const {spawn} = require('node:child_process');
const {once} = require('node:events');
const {RuntimeLogSignals} = require('./runtime-log-signals.cjs');

test('split diagnostic markers yield fixed categories without retaining raw text', () => {
  const signals = new RuntimeLogSignals();
  signals.observe('stderr', Buffer.from('secret-fixture-cookie /private/user FATAL ER'));
  signals.observe('stderr', Buffer.from('ROR: napi_call_threadsafe_function address=fixture\n'));
  signals.observe('stderr', Buffer.from('thread "fixture-worker" panicked at /private/source\n'));
  signals.observe('stdout', Buffer.from('Waiting for the debugger to disconnect...\n'));
  const value = signals.close();
  assert.deepEqual(value.signals.map(item => item.name),
    ['nativeFatal', 'nativeThreadsafeCall', 'rustPanic', 'debuggerDisconnectWait']);
  assert.equal(signals.tails.size, 0);
  const output = JSON.stringify(value);
  for (const text of ['secret-fixture-cookie', '/private/', 'fixture-worker', 'address=']) {
    assert.equal(output.includes(text), false);
  }
  assert.equal(value.rawLogsRetained, false);
});

test('real worker stderr can be classified while its failure remains a failure', async t => {
  const signals = new RuntimeLogSignals();
  const child = spawn(process.execPath, ['-e',
    'process.stderr.write("FATAL ERROR: fixture not a native crash\\n"); process.exitCode=3;'],
  {stdio: ['ignore', 'pipe', 'pipe']});
  t.after(() => child.kill());
  child.stdout.on('data', chunk => signals.observe('stdout', chunk));
  child.stderr.on('data', chunk => signals.observe('stderr', chunk));
  const [code] = await once(child, 'close');
  assert.equal(code, 3);
  assert.deepEqual(signals.close().signals.map(item => item.name), ['nativeFatal']);
});

test('startup port, option and bytecode failures expose only fixed categories', () => {
  const signals = new RuntimeLogSignals();
  signals.observe('stderr', Buffer.from(
    'Starting inspector on 127.0.0.1:9229 failed: address already in use\n' +
    '/private/profile/app.exe: bad option: --private-fixture\n' +
    'Error: Invalid or incompatible cached data (cachedDataRejected)\n' +
    'Debugger listening on ws://127.0.0.1:54321/private-token\n'));
  const output = signals.close();
  assert.deepEqual(output.signals.map(value => value.name),
    ['inspectorStarted', 'inspectorAddressInUse', 'invalidRuntimeOption', 'cachedBytecodeRejected']);
  assert.equal(/private|127\.0\.0\.1|9229|54321/.test(JSON.stringify(output)), false);
});
