const {app, BrowserWindow} = require('electron');
const fs = require('node:fs');
async function run() {
  const output = process.env.HEYI_WORKBENCH_TEST_OUTPUT;
  if (!output || !process.env.HEYI_WORKBENCH_TEST_DATA ||
      process.env.HEYI_WORKBENCH_TEST_PORT !== '0') {
    throw Error('Isolated profile, output and ephemeral bridge port required');
  }
  await app.whenReady();
  const started = Date.now();
  const inspect = async () => {
    const login = BrowserWindow.getAllWindows().find(win => win.name === 'login');
    if (login && !login.webContents.isLoading()) {
      try {
        // Report fixed login controls only. Do not export QR credentials,
        // cookies, user identities, page source or account information.
        const page = await login.webContents.executeJavaScript(`(() => {
          const text = document.body?.innerText || '';
          return {hasScanLogin: /扫码|二维码/.test(text),
            hasPhoneLogin: /手机|验证码/.test(text),
            hasPrivacyText: /隐私|协议/.test(text),
            rendered: !!document.body && document.body.children.length > 0};
        })()`);
        if (page.rendered && (page.hasScanLogin || page.hasPhoneLogin)) {
          await fs.promises.writeFile(output, JSON.stringify({ok: true,
            originalLoginWindow: true, actualAccountLoginTested: false,
            outboundCandidateMessages: 0, profile: app.getPath('userData'),
            electron: process.versions.electron, page}, null, 2));
          app.quit();return;
        }
      } catch {}
    }
    if (Date.now() - started > 25000) {
      await fs.promises.writeFile(output, JSON.stringify({ok: false,
        originalLoginWindow: !!login, actualAccountLoginTested: false,
        error: 'Original login controls did not render within 25 seconds'}));
      app.quit();return;
    }
    setTimeout(inspect, 500);
  };
  void inspect();
}
module.exports = {run};
