const path = require('node:path');
const {app, nativeImage} = require('electron');
const {displayName} = require('./assets/brand.json');

let icon;
function applyWindowBrand(window) {
  if (!icon) {
    icon = nativeImage.createFromPath(path.join(__dirname, 'assets/ai-boss-app.png'));
    if (icon.isEmpty()) throw Error('Ai boss application icon is missing');
    if (process.platform === 'win32') app.setAppUserModelId('cn.hoyee.bossworkbench.experimental');
  }
  window.setTitle(displayName);
  // Native pages update their own title when navigation completes. Keep the
  // surrounding application's name stable without changing page content.
  window.on('page-title-updated', event => event.preventDefault());
  if (process.platform === 'win32') window.setIcon(icon);
  if (process.platform === 'darwin') app.dock.setIcon(icon);
  // Do not call app.setName here: existing cookie encryption and Keychain
  // services depend on the internal name used by the installed workbench.
}

module.exports = {displayName, applyWindowBrand};
