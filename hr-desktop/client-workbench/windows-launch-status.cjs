const fs = require('node:fs');
const path = require('node:path');

function launchStatus(root, launchId, name) {
  if (!/^[a-f0-9]{32}$/.test(launchId || '') ||
      !['heyi-launcher-status.json', 'heyi-window-status.json'].includes(name)) {
    throw Error('An exact launcher observation identity is required');
  }
  const file = path.join(root, name);
  return value => {
    const temporary = file + '.tmp';
    fs.writeFileSync(temporary, JSON.stringify({...value, launchId}), {mode: 0o600});
    fs.renameSync(temporary, file);
  };
}
function nativeHandle(window) {
  const bytes = window.getNativeWindowHandle();
  if (bytes.length === 4) return String(bytes.readUInt32LE(0));
  if (bytes.length === 8) return bytes.readBigUInt64LE(0).toString();
  throw Error('Unexpected Windows native handle size');
}
async function waitForEntry(root, launchId, timeout = 15000) {
  const end = Date.now() + timeout, file = path.join(root, 'heyi-entry-status.json');
  while (Date.now() < end) {
    try {
      const state = JSON.parse(await fs.promises.readFile(file, 'utf8'));
      if (state.launchId === launchId && state.ready === true) return;
    } catch (error) {
      if (error.code && error.code !== 'ENOENT') throw error;
    }
    await new Promise(resolve => setTimeout(resolve, 50));
  }
  throw Error('Ai boss startup window handoff did not complete');
}
async function waitForNativeWindow(root, launchId, child, timeout = 45000) {
  const end = Date.now() + timeout, file = path.join(root, 'heyi-native-window-ready.json');
  while (Date.now() < end) {
    if (child.exitCode !== null || child.signalCode !== null) throw Error('Native process exited before its window');
    try {
      const state = JSON.parse(await fs.promises.readFile(file, 'utf8'));
      if (state.launchId === launchId && state.nativePid === child.pid && state.ready === true) return;
    } catch (error) {
      if (error.code && error.code !== 'ENOENT') throw error;
    }
    await new Promise(resolve => setTimeout(resolve, 50));
  }
  throw Error('Native window creation did not complete');
}
module.exports = {launchStatus, nativeHandle, waitForEntry, waitForNativeWindow};
