const {app} = require('electron');
const path = require('node:path');
const fs = require('node:fs');

// A separate profile prevents a development build changing the installed
// client's profile or automatically using the old helper's cookies.
const data = process.env.HEYI_WORKBENCH_TEST_DATA ||
  path.join(app.getPath('appData'), 'heyi-boss-workbench');
fs.mkdirSync(data, {recursive: true, mode: 0o700});
app.setPath('userData', data);
app.setName('赫逸工作台 · 客户端改造版');
require('./compat.cjs').installWebTypes();
if (process.argv.includes('--heyi-workbench-smoke')) {
  require('./smoke.cjs').run().catch(error => {
    const output = process.env.HEYI_WORKBENCH_TEST_OUTPUT;
    if (output) fs.writeFileSync(output, JSON.stringify({ok: false, error: String(error)}));
    app.exit(1);
  });
} else {
  require('../main.js');
  if (process.argv.includes('--heyi-workbench-ui-check')) {
    require('./ui-check.cjs').run();
  }
}
