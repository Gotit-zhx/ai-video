const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../../frontend/hr-desktop-bridge.js'), 'utf8');
const start = source.indexOf('  async function health(');
const end = source.indexOf('  async function diagnose(', start);
assert.ok(start >= 0 && end > start);
function check(platform, version) {
  const value = {app: 'heyi-recruiter', protocol: 1, version};
  return vm.runInNewContext(source.slice(start, end) + '\nhealth()', {
    navigator: {platform}, versionError: null, base: 'http://127.0.0.1:17861',
    data: async response => response, connectionFetch: async () => value,
    timeout: options => options,
  });
}
test('Windows startup fix is required while a working Mac 0.1.25 remains usable', async () => {
  await assert.rejects(check('Win32', '0.1.25'), error =>
    error.code === 'ASSISTANT_UPDATE_REQUIRED' && error.message.includes('0.1.27'));
  await assert.rejects(check('Win32', '0.1.26'), error =>
    error.code === 'ASSISTANT_UPDATE_REQUIRED' && error.message.includes('0.1.27'));
  assert.equal((await check('Win32', '0.1.27')).version, '0.1.27');
  assert.equal((await check('MacIntel', '0.1.25')).version, '0.1.25');
  assert.equal((await check('MacIntel', '0.1.26')).version, '0.1.26');
  assert.equal((await check('MacIntel', '0.1.27')).version, '0.1.27');
  await assert.rejects(check('Win32', 'invalid'));
  await assert.rejects(check('MacIntel', '0.1.24'));
});
