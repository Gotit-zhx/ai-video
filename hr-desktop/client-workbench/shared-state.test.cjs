const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const {storagePaths} = require('./shared-state.cjs');
const app = {getPath: name => name === 'userData'
  ? path.resolve('/offline/native-client') : path.resolve('/offline/appdata')};

test('modified client retains existing account ledgers while separating native login cookies', () => {
  const paths = storagePaths(app, {});
  assert.equal(paths.recruiterDirectory, path.resolve('/offline/appdata/heyi-recruiter/recruiter'));
  assert.equal(paths.deviceFile, path.resolve('/offline/appdata/heyi-recruiter/device.json'));
  assert.equal(paths.nativeRoot, path.resolve('/offline/native-client/heyi'));
});
test('offline and UI probes never select real assistant records', () => {
  const paths = storagePaths(app, {HEYI_WORKBENCH_TEST_DATA: '/offline/native-client'});
  assert.equal(paths.assistantRoot, path.resolve('/offline/native-client/heyi/offline-assistant'));
  assert.ok(paths.recruiterDirectory.startsWith(paths.nativeRoot + path.sep));
});
test('Windows launcher can preserve the legacy records outside its isolated native AppData', () => {
  const assistantRoot = path.resolve('/offline/original-appdata/heyi-recruiter');
  const paths = storagePaths(app, {}, {assistantRoot});
  assert.equal(paths.recruiterDirectory, path.join(assistantRoot, 'recruiter'));
  const isolated = storagePaths(app, {HEYI_WORKBENCH_TEST_DATA: '/offline/native-client'}, {assistantRoot});
  assert.notEqual(isolated.assistantRoot, assistantRoot);
  assert.throws(() => storagePaths(app, {}, {assistantRoot: 'relative'}), /Absolute/);
});
