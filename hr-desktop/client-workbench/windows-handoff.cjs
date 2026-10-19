const fs = require('node:fs/promises');
const path = require('node:path');

function files(root, launchId) {
  if (!path.isAbsolute(root || '') || !/^[a-f0-9]{32}$/.test(launchId || '')) {
    throw Error('Exact startup identity required');
  }
  return {
    request: path.join(root, 'heyi-detach-request.json'),
    status: path.join(root, 'heyi-detach-status.json'),
  };
}
async function write(file, value) {
  await fs.writeFile(file + '.tmp', JSON.stringify(value), {mode: 0o600});
  await fs.rename(file + '.tmp', file);
}
async function wait(file, launchId, nativePid, {timeout = 15000, child} = {}) {
  const deadline = Date.now() + timeout;
  do {
    if (child && (child.exitCode !== null || child.signalCode !== null)) {
      throw Error('Native process exited before startup handoff');
    }
    try {
      const value = JSON.parse(await fs.readFile(file, 'utf8'));
      if (value.launchId === launchId && value.nativePid === nativePid) return value;
    } catch (error) {
      if (error.code && error.code !== 'ENOENT') throw error;
    }
    await new Promise(resolve => setTimeout(resolve, 25));
  } while (Date.now() < deadline);
  throw Error('Native startup handoff timed out');
}

// Called in the native process. Never close the inspector from an evaluation
// whose response the controller is still waiting to receive. The controller
// explicitly disconnects first, then sends a launch-scoped file acknowledgement.
async function finishNativeHandoff(root, launchId) {
  const {request, status} = files(root, launchId);
  await wait(request, launchId, process.pid);
  const inspector = require('node:inspector');
  inspector.close();
  if (inspector.url()) throw Error('Startup inspector is still listening');
  await write(status, {launchId, nativePid: process.pid, inspectorClosed: true});
}

async function detachAndWait(connection, root, launchId, timeout = 15000) {
  const {request, status} = files(root, launchId);
  connection.client.close();
  await write(request, {launchId, nativePid: connection.child.pid});
  const state = await wait(status, launchId, connection.child.pid, {timeout, child: connection.child});
  if (state.inspectorClosed !== true) throw Error('Native startup inspector did not close');
}

module.exports = {finishNativeHandoff, detachAndWait};
