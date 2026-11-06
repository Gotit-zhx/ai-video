// Records whether this harness requested termination. An exit code alone does
// not distinguish a native failure from Windows TerminateProcess on timeout.
function waitForProcess(child, timeout = 35000) {
  if (!Number.isInteger(timeout) || timeout < 1 || timeout > 60000) {
    return Promise.reject(Error('Invalid process deadline'));
  }
  const started = Date.now();
  if (child.exitCode !== null || child.signalCode !== null) {
    return Promise.resolve({code: child.exitCode, signal: child.signalCode,
      timedOut: false, terminationRequested: false, elapsedMs: 0});
  }
  return new Promise((resolve, reject) => {
    let timedOut = false, terminationRequested = false;
    const timer = setTimeout(() => {
      timedOut = true;
      terminationRequested = child.kill();
    }, timeout);
    const cleanup = () => {
      clearTimeout(timer);child.removeListener('exit', exited);child.removeListener('error', failed);
    };
    function exited(code, signal) {
      cleanup();
      resolve({code, signal, timedOut, terminationRequested, elapsedMs: Date.now() - started});
    }
    function failed() {cleanup();reject(Error('Observed process failed'));}
    child.once('exit', exited);child.once('error', failed);
  });
}
module.exports = {waitForProcess};
