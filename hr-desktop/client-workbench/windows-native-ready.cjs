const {evaluateRunning} = require('./inspector-driver.cjs');

// Executed inside our exact verified child. Visibility is not readiness:
// hidden login/main windows are valid and no BOSS account is required.
function nativeReadiness() {
  const {app, BrowserWindow} = process.mainModule.require('electron');
  if (!app.isReady()) return 'electron';
  if (typeof globalThis.$socket?.handleEvent !== 'function') return 'dispatcher';
  const window = BrowserWindow.getAllWindows().find(win =>
    !win.isDestroyed() && ['login', 'main'].includes(win.name) &&
    !win.webContents.isDestroyed());
  return window ? 'ready' : 'native-window';
}

async function waitForOriginalReady(connection, {timeout = 30000, onProgress} = {}) {
  const deadline = Date.now() + timeout;
  let previous;
  do {
    if (connection.child.exitCode !== null || connection.child.signalCode !== null) {
      throw Error('Original client exited before initialization');
    }
    const stage = await evaluateRunning(connection, `(${nativeReadiness.toString()})()`);
    if (stage === 'ready') return;
    if (!['electron', 'dispatcher', 'native-window'].includes(stage)) throw Error('Invalid native readiness');
    if (stage !== previous) {onProgress?.(stage);previous = stage;}
    await new Promise(resolve => setTimeout(resolve, 100));
  } while (Date.now() < deadline);
  throw Object.assign(Error('Original client initialization did not complete'), {
    nativeBindingDiagnostic: {stage: previous},
  });
}

module.exports = {nativeReadiness, waitForOriginalReady};
