const path = require('node:path');
const {app, nativeImage} = require('electron');
const {displayName} = require('./assets/brand.json');

const appId = 'cn.hoyee.bossworkbench.experimental';
let icon, relaunchEntry;
function setWindowsRelaunch(entry) {
  if (process.platform !== 'win32' || !path.isAbsolute(entry || '') || !entry.endsWith('Ai boss.exe')) {
    throw Error('Verified Ai boss Windows entry required');
  }
  relaunchEntry = entry;
}
function applyWindowBrand(window) {
  if (!icon) {
    icon = nativeImage.createFromPath(path.join(__dirname, 'assets/ai-boss-app.png'));
    if (icon.isEmpty()) throw Error('Ai boss application icon is missing');
    if (process.platform === 'win32') app.setAppUserModelId(appId);
  }
  window.setTitle(displayName);
  // Native pages update their own title when navigation completes. Keep the
  // surrounding application's name stable without changing page content.
  window.on('page-title-updated', event => event.preventDefault());
  if (process.platform === 'win32') {
    window.setIcon(icon);
    if (relaunchEntry) window.setAppDetails({
      appId, appIconPath: path.join(__dirname, 'assets/ai-boss.ico'),
      relaunchCommand: `"${relaunchEntry}"`, relaunchDisplayName: displayName,
    });
  }
  if (process.platform === 'darwin') app.dock.setIcon(icon);
  // Do not call app.setName here: existing cookie encryption and Keychain
  // services depend on the internal name used by the installed workbench.
}

module.exports = {displayName, applyWindowBrand, setWindowsRelaunch};
