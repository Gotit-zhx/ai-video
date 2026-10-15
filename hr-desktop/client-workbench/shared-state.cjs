const path = require('node:path');

function storagePaths(app, env = process.env) {
  const profile = app.getPath('userData');
  const nativeRoot = path.join(profile, 'heyi');
  // Share the existing assistant's per-owner account records, quotas, uncertain
  // sends and holds. Native client cookies stay in its separate userData.
  // Offline probes must never read or alter a real assistant's state.
  const assistantRoot = env.HEYI_WORKBENCH_TEST_DATA
    ? path.join(nativeRoot, 'offline-assistant')
    : path.join(app.getPath('appData'), 'heyi-recruiter');
  return {nativeRoot, assistantRoot, recruiterDirectory: path.join(assistantRoot, 'recruiter'),
    deviceFile: path.join(assistantRoot, 'device.json')};
}
module.exports = {storagePaths};
