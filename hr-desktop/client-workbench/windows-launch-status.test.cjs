const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const os = require('node:os');
const {launchStatus, nativeHandle, waitForEntry, waitForNativeWindow} = require('./windows-launch-status.cjs');
test('a previous launch acknowledgement cannot release the current startup gate', async t => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'heyi-startup-status-'));
  t.after(() => fs.rm(root, {recursive: true, force: true}));
  const id = 'a'.repeat(32);
  const report = launchStatus(root, id, 'heyi-launcher-status.json');
  report({stage: 'original-startup', nativePid: 123});
  assert.deepEqual(JSON.parse(await fs.readFile(path.join(root, 'heyi-launcher-status.json'))),
    {stage: 'original-startup', nativePid: 123, launchId: id});
  const file = path.join(root, 'heyi-entry-status.json');
  await fs.writeFile(file, JSON.stringify({launchId: 'b'.repeat(32), ready: true}));
  await assert.rejects(waitForEntry(root, id, 10), /handoff/);
  await fs.writeFile(file, JSON.stringify({launchId: id, ready: true}));
  await waitForEntry(root, id, 100);
});
test('window readiness must belong to the exact current launch and native child', async t => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'heyi-window-ready-'));
  t.after(() => fs.rm(root, {recursive: true, force: true}));
  const file = path.join(root, 'heyi-native-window-ready.json');
  const child = {pid: 123, exitCode: null, signalCode: null}, launchId = 'a'.repeat(32);
  for (const value of [{launchId: 'old', nativePid: 123, ready: true},
    {launchId, nativePid: 456, ready: true}]) {
    await fs.writeFile(file, JSON.stringify(value));
    await assert.rejects(waitForNativeWindow(root, launchId, child, 10), /creation/);
  }
  await fs.writeFile(file, JSON.stringify({launchId, nativePid: 123, ready: true}));
  await waitForNativeWindow(root, launchId, child, 100);
  child.exitCode = 0;
  await assert.rejects(waitForNativeWindow(root, launchId, child), /exited/);
});
test('both x86 and x64 Windows HWND values retain their exact integer value', () => {
  for (const length of [4, 8]) {
    const buffer = Buffer.alloc(length);
    if (length === 4) buffer.writeUInt32LE(0xf1234567);
    else buffer.writeBigUInt64LE(0x123456789abcdefn);
    assert.equal(nativeHandle({getNativeWindowHandle: () => buffer}),
      length === 4 ? String(0xf1234567) : '81985529216486895');
  }
});
