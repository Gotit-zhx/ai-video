const test = require('node:test');
const assert = require('node:assert/strict');
const {installNativeExitGuard} = require('./native-exit-guard.cjs');

test('native exit waits for session storage and preserves original receiver, arguments and result', async () => {
  let release, drains = 0, exits = 0;
  const original = function(...args) {
    assert.equal(this, sdk);assert.deepEqual(args, ['original-argument']);
    exits++;return 'original-result';
  };
  const sdk = {_sdk_exit: original}, globalTarget = {_sdk_exit: original};
  const previous = Object.getOwnPropertyDescriptor(sdk, '_sdk_exit');
  const guard = installNativeExitGuard({targets: [sdk, globalTarget], beforeExit() {
    drains++;return new Promise(resolve => {release = resolve;});
  }});
  const result = sdk._sdk_exit('original-argument');
  assert.equal(globalTarget._sdk_exit('second-click'), result);
  await Promise.resolve();
  assert.equal(drains, 1);assert.equal(exits, 0);
  release();
  assert.equal(await result, 'original-result');assert.equal(exits, 1);
  guard.close();
  assert.deepEqual(Object.getOwnPropertyDescriptor(sdk, '_sdk_exit'), previous);
  assert.equal(globalTarget._sdk_exit, original);
});
test('a failed drain does not end the process or repeat the native exit', async () => {
  const failure = Error('storage unavailable');
  const sdk = {_sdk_exit() {assert.fail('must not exit without a completed drain');}};
  const guard = installNativeExitGuard({targets: [sdk], beforeExit: async () => {throw failure;}});
  const result = sdk._sdk_exit();
  await assert.rejects(result, error => error === failure);
  assert.equal(sdk._sdk_exit(), result);
  guard.close();
});
test('partial installation rolls back and cleanup preserves a newer owner', () => {
  const original = () => {}, sdk = {_sdk_exit: original}, locked = {};
  Object.defineProperty(locked, '_sdk_exit', {value: original, configurable: false});
  assert.throws(() => installNativeExitGuard({targets: [sdk, locked], beforeExit() {}}));
  assert.equal(sdk._sdk_exit, original);
  const guard = installNativeExitGuard({targets: [sdk], beforeExit() {}});
  const newer = () => {};sdk._sdk_exit = newer;
  guard.close();assert.equal(sdk._sdk_exit, newer);
  assert.throws(() => installNativeExitGuard({targets: [{}], beforeExit() {}}));
});
