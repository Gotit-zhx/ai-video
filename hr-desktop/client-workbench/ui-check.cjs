const {app, BrowserWindow, session} = require('electron');
const fs = require('node:fs');
const {validateHeaderValue} = require('node:http');
async function run() {
  const output = process.env.HEYI_WORKBENCH_TEST_OUTPUT;
  if (!output || !process.env.HEYI_WORKBENCH_TEST_DATA ||
      process.env.HEYI_WORKBENCH_TEST_PORT !== '0') {
    throw Error('Isolated profile, output and ephemeral bridge port required');
  }
  await app.whenReady();
  let userAgentHeaderValid = false;
  try {
    // Test the real default header and the value captured by the original
    // client's ready handler. Never log QR credentials or the header itself.
    validateHeaderValue('user-agent', session.defaultSession.getUserAgent());
    validateHeaderValue('user-agent', global.userAgent);
    userAgentHeaderValid = true;
  } catch {}
  const started = Date.now();
  let latestPage = null;
  let platformConnection = null;
  const inspect = async () => {
    const login = BrowserWindow.getAllWindows().find(win => win.name === 'login');
    const integrated = require('./integrated-shell.cjs').instance();
    if (process.env.HEYI_WORKBENCH_TEST_LIVE_BRIDGE === 'health-only' &&
        !platformConnection && integrated.platform?.webContents.getURL().startsWith('https://hoyee.org.cn/') &&
        !integrated.platform.webContents.isLoadingMainFrame()) {
      platformConnection = await integrated.platform.webContents.executeJavaScript(`(async () => {
        try {
          const r = await fetch('http://127.0.0.1:17861/health',
            {credentials: 'omit', cache: 'no-store', signal: AbortSignal.timeout(5000)});
          const result = await r.json();
          return {ok: r.ok && result.data?.app === 'heyi-recruiter', status: r.status};
        } catch (error) {return {ok: false, error: error.name + ': ' + error.message};}
      })()`);
    }
    if (login && !login.webContents.isLoading()) {
      try {
        // Report fixed login controls only. Do not export QR credentials,
        // cookies, user identities, page source or account information.
        const page = await login.webContents.executeJavaScript(`(() => {
          const text = document.body?.innerText || '';
          const qr = document.querySelector('.scan-login .qr-wrap > img');
          return {hasScanLogin: /扫码|二维码/.test(text),
            hasPhoneLogin: /手机|验证码/.test(text),
            hasPrivacyText: /隐私|协议/.test(text),
            qrImageLoaded: !!qr && qr.complete && qr.naturalWidth > 0,
            qrImagePresent: !!qr,
            qrImageSourceKind: qr ? (/^([a-z]+):/.exec(qr.getAttribute('src') || '')?.[1] || 'empty') : 'absent',
            qrLoadingVisible: !!document.querySelector('.scan-login .loading-wrap'),
            qrRefreshVisible: !!document.querySelector('.scan-login .reload-text'),
            qrErrorVisible: /二维码异常|二维码加载失败/.test(text),
            invalidHeaderVisible: /Invalid character in header/.test(text),
            rendered: !!document.body && document.body.children.length > 0};
        })()`);
        latestPage = page;
        if (userAgentHeaderValid && page.qrImageLoaded && !page.invalidHeaderVisible) {
          await integrated.chromeReady;
          const visibleWindows = BrowserWindow.getAllWindows().filter(win => win.isVisible()).length;
          const proxyRoute = await session.defaultSession.resolveProxy('https://www.zhipin.com');
          const relay = require('./native-network.cjs').instance().egress.info;
          const directProxyConfigured = proxyRoute === `PROXY 127.0.0.1:${relay?.port}`;
          const integratedLogin = !!integrated.window && integrated.window === login;
          await fs.promises.writeFile(output, JSON.stringify({
            ok: integratedLogin && visibleWindows === 1 && directProxyConfigured,
            originalLoginWindow: true, actualAccountLoginTested: false,
            outboundCandidateMessages: 0, profile: app.getPath('userData'),
            electron: process.versions.electron, userAgentHeaderValid, page,
            integratedLogin,
            visibleWindows, embeddedChromeViews: login.getBrowserViews().length,
            directProxyConfigured, platformConnection}, null, 2));
          app.quit();return;
        }
      } catch {}
    }
    if (Date.now() - started > 35000) {
      await fs.promises.writeFile(output, JSON.stringify({ok: false,
        originalLoginWindow: !!login, actualAccountLoginTested: false,
        userAgentHeaderValid, page: latestPage, platformConnection,
        nativeWindows: BrowserWindow.getAllWindows().map(win => ({
          name: ['login', 'download', 'main', 'free'].includes(win.name) ? win.name : 'other',
          loading: win.webContents.isLoadingMainFrame(), visible: win.isVisible(),
        })),
        error: 'Original QR image did not load within 35 seconds'}));
      app.quit();return;
    }
    setTimeout(inspect, 500);
  };
  void inspect();
}
module.exports = {run};
