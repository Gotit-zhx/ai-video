const path = require('node:path');

function storagePaths(app, env = process.env, options = {}) {
  const profile = app.getPath('userData');
  const nativeRoot = path.join(profile, 'heyi');
  // Share the existing assistant's per-owner account records, quotas, uncertain
  // sends and holds. Native client cookies stay in its separate userData.
  // Offline probes must never read or alter a real assistant's state.
  if (options.assistantRoot !== undefined &&
      (typeof options.assistantRoot !== 'string' || !path.isAbsolute(options.assistantRoot))) {
    throw Error('Absolute existing assistant records directory required');
  }
  const assistantRoot = env.HEYI_WORKBENCH_TEST_DATA
    ? path.join(nativeRoot, 'offline-assistant')
    : options.assistantRoot || path.join(app.getPath('appData'), 'heyi-recruiter');
  return {nativeRoot, assistantRoot, recruiterDirectory: path.join(assistantRoot, 'recruiter'),
    deviceFile: path.join(assistantRoot, 'device.json')};
}
module.exports = {storagePaths};
