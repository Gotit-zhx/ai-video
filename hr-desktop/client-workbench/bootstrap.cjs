const {app} = require('electron');
const path = require('node:path');
const fs = require('node:fs');

// A separate profile prevents a development build changing the installed
// client's profile or automatically using the old helper's cookies.
const data = process.env.HEYI_WORKBENCH_TEST_DATA ||
  path.join(app.getPath('appData'), 'heyi-boss-workbench');
fs.mkdirSync(data, {recursive: true, mode: 0o700});
app.setPath('userData', data);
// Electron puts this internal name in the default User-Agent before the
// original client builds its HTTP headers. Keep our own product token ASCII;
// the macOS display name and window titles still provide the Chinese branding.
app.setName('HeyiWorkbench');
require('./compat.cjs').installWebTypes();
if (process.argv.includes('--heyi-workbench-smoke') ||
    process.argv.includes('--heyi-session-write') || process.argv.includes('--heyi-session-restore')) {
  const entry = process.argv.includes('--heyi-workbench-smoke') ? './smoke.cjs' : './platform-session-smoke.cjs';
  require(entry).run().catch(error => {
    const output = process.env.HEYI_WORKBENCH_TEST_OUTPUT;
    if (output) fs.writeFileSync(output, JSON.stringify({ok: false, error: String(error)}));
    app.exit(1);
  });
} else {
  require('../main.js');
  if (process.argv.includes('--heyi-workbench-ui-check')) {
    require('./ui-check.cjs').run();
  }
  if (process.argv.includes('--heyi-chat-diagnostics')) {
    require('./chat-diagnostics.cjs').run();
  }
}
