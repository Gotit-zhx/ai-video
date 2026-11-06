const test = require('node:test');
const assert = require('node:assert/strict');
const {runtimeExecutable} = require('./runtime-paths.cjs');

test('Windows host and NIC relay select the packaged executable with its actual extension', () => {
  assert.equal(runtimeExecutable('C:\\Program Files\\Heyi\\resources', 'win32'),
    'C:\\Program Files\\Heyi\\resources\\heyi-runtime\\heyi-boss.exe');
});
test('Mac keeps its existing packaged runtime location', () => {
  assert.equal(runtimeExecutable('/Applications/Heyi.app/Contents/Resources', 'darwin'),
    '/Applications/Heyi.app/Contents/Resources/heyi-runtime/heyi-boss');
  for (const invalid of [undefined, '', 'relative/resources']) {
    assert.throws(() => runtimeExecutable(invalid, 'darwin'), /resources path/);
  }
});
