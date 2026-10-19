const path = require('node:path');
const {EventEmitter} = require('node:events');
const {BrowserView, ipcMain, shell, Menu} = require('electron');
const {applyWindowBrand} = require('./brand.cjs');

const HEIGHT = 54;
const SIDEBAR = 88;
const LOGIN_CSS = `
html,body{background:#f7f9fc!important}
#app{position:absolute!important;top:54px!important;right:auto!important;left:88px!important;
 width:calc(100% - 88px)!important;height:calc(100% - 54px)!important;
 display:flex!important;align-items:center!important;justify-content:center!important;
 border:0!important;background:#f7f9fc!important}
.login-wrap{width:340px!important;height:520px!important;max-height:100%!important;
 flex-shrink:0!important;border-radius:18px!important;background:white!important;
 box-shadow:0 12px 44px #1b355010!important;border:1px solid #e4eaf1!important}
.login-wrap .header-close{visibility:hidden!important}
.login-wrap .common-mac-header{display:none!important}
.login-wrap .logo{filter:brightness(0)!important}
`;
const MAIN_CSS = `#app{position:absolute!important;top:54px!important;left:88px!important;
width:calc(100% - 88px)!important;height:calc(100% - 54px)!important}`;

function boundsFor(width, height, nativeName, mode) {
  return {
    toolbar: {x: 0, y: 0, width, height: HEIGHT},
    sidebar: {x: 0, y: HEIGHT, width: SIDEBAR, height: Math.max(0, height - HEIGHT)},
    platform: {x: SIDEBAR, y: HEIGHT,
      width: Math.max(0, width - SIDEBAR),
      height: Math.max(0, height - HEIGHT)},
  };
}

class IntegratedShell extends EventEmitter {
  constructor() {
    super();
    this.windows = new Map();
    this.originalShow = new WeakMap();
    this.mode = 'boss';
    this.networkState = 'starting';
    this.accountState = 'signed-out';
    ipcMain.on('heyi-integrated-shell-action', (event, name) => {
      if (![this.toolbar?.webContents, this.sidebar?.webContents].includes(event.sender)
          || !this.window || this.window.isDestroyed()) return;
      if (name === 'workbench') this.showWorkbench();
      if (name === 'boss') this.showNative();
      if (name === 'minimize') this.window.minimize();
      if (name === 'maximize') {
        if (this.window.isMaximized()) this.window.unmaximize();else this.window.maximize();
      }
      if (name === 'hide') this.window.hide();
    });
  }
  configure({session, url, origin}) {
    if (!this.platform) {
      this.platform = new BrowserView({webPreferences: {
        session, nodeIntegration: false, contextIsolation: true, sandbox: true, webSecurity: true,
      }});
      this.platform.webContents.setWindowOpenHandler(({url}) => {
        try {if (new URL(url).protocol === 'https:') void shell.openExternal(url);} catch {}
        return {action: 'deny'};
      });
      this.platform.webContents.on('will-navigate', (event, url) => {
        try {if (new URL(url).origin !== origin) event.preventDefault();} catch {event.preventDefault();}
      });
      this.enablePaste(this.platform.webContents);
      this.platformReady = this.platform.webContents.loadURL(url);
      void this.platformReady.catch(() => {});
    }
    this.layout();
  }
  chromeViews() {
    if (!this.toolbar) {
      this.toolbar = new BrowserView({webPreferences: {
        preload: path.join(__dirname, 'shell-preload.cjs'),
        nodeIntegration: false, contextIsolation: true, sandbox: true,
      }});
      const toolbarReady = this.toolbar.webContents.loadFile(path.join(__dirname, 'shell.html'));
      this.toolbar.webContents.on('did-finish-load', () => this.sendState());
      this.sidebar = new BrowserView({webPreferences: {
        preload: path.join(__dirname, 'shell-preload.cjs'),
        nodeIntegration: false, contextIsolation: true, sandbox: true,
      }});
      const sidebarReady = this.sidebar.webContents.loadFile(
        path.join(__dirname, 'shell.html'), {hash: 'sidebar'});
      this.chromeReady = Promise.all([toolbarReady, sidebarReady]);
      void this.chromeReady.catch(() => {});
      this.sidebar.webContents.on('did-finish-load', () => this.sendState());
    }
    return this.toolbar;
  }
  attachWindow(win, name) {
    if (!['login', 'main'].includes(name) || this.originalShow.has(win)) return;
    this.windows.set(name, win);
    this.originalShow.set(win, win.show.bind(win));
    this.enablePaste(win.webContents);
    // Keep the original BrowserWindow and its authenticated renderer. Add
    // views inside it rather than copying QR data or starting another login.
    win.setResizable(true);win.setMinimizable(true);win.setMaximizable(true);
    win.setFullScreenable(true);win.setMinimumSize(1080, 700);
    if (this.window && !this.window.isDestroyed()) win.setBounds(this.window.getBounds());
    else {win.setSize(1360, 880);win.center();}
    applyWindowBrand(win);
    win.show = () => {this.selectWindow(win);this.originalShow.get(win)();};
    // The Windows client may retain show() before the addon is installed,
    // or show its HWND through native code. Observe the actual event too.
    win.on('show', () => this.selectWindow(win));
    win.on('resize', () => {if (this.window === win) this.layout();});
    const applyNativeCSS = () => {
      void win.webContents.insertCSS(name === 'login' ? LOGIN_CSS : MAIN_CSS).catch(() => {});
    };
    win.webContents.on('did-finish-load', applyNativeCSS);
    // The Windows bytecode finishes creating its windows before we can attach
    // our addon. Apply the same layout to an already loaded native page.
    if (win.webContents.isLoadingMainFrame() === false) applyNativeCSS();
    win.on('closed', () => {
      if (this.windows.get(name) === win) this.windows.delete(name);
      if (this.window === win) this.window = null;
    });
    if (!this.window) {
      if (name === 'main') this.mode = 'workbench';
      this.selectWindow(win);
    }
  }
  enablePaste(contents) {
    contents.on('before-input-event', (event, input) => {
      const command = process.platform === 'darwin' ? input.meta : input.control;
      if (input.type === 'keyDown' && command && !input.alt && input.key.toLowerCase() === 'v') {
        event.preventDefault();
        contents.paste();
      }
    });
    contents.on('context-menu', (_event, params) => {
      if (!params.isEditable) return;
      Menu.buildFromTemplate([
        {role: 'undo'}, {role: 'redo'}, {type: 'separator'},
        {role: 'cut'}, {role: 'copy'}, {label: '粘贴', click: () => contents.paste()},
        {role: 'selectAll'},
      ]).popup({window: this.window});
    });
    contents.on('did-attach-webview', (_event, guest) => this.enablePaste(guest));
  }
  selectWindow(win) {
    if (!win || win.isDestroyed()) return;
    const changed = this.window !== win;
    if (this.window !== win) {
      const previous = this.window;
      if (previous && !previous.isDestroyed()) {
        for (const view of [this.platform, this.toolbar, this.sidebar]) {
          if (view && previous.getBrowserViews().includes(view)) previous.removeBrowserView(view);
        }
        previous.hide();
      }
      this.window = win;
    }
    this.layout();
    if (changed) this.emit('window-selected', win);
  }
  layout() {
    const win = this.window;
    if (!win || win.isDestroyed()) return;
    const [width, height] = win.getContentSize();
    const bounds = boundsFor(width, height, win.name, this.mode);
    const toolbar = this.chromeViews();
    if (!win.getBrowserViews().includes(toolbar)) win.addBrowserView(toolbar);
    toolbar.setBounds(bounds.toolbar);
    if (!win.getBrowserViews().includes(this.sidebar)) win.addBrowserView(this.sidebar);
    this.sidebar.setBounds(bounds.sidebar);
    if (this.platform) {
      const attached = win.getBrowserViews().includes(this.platform);
      if (this.mode === 'workbench') {
        if (!attached) win.addBrowserView(this.platform);
        this.platform.setBounds(bounds.platform);
      } else if (attached) win.removeBrowserView(this.platform);
    }
    this.sendState();
  }
  sendState() {
    for (const view of [this.toolbar, this.sidebar]) {
      if (!view || view.webContents.isDestroyed()) continue;
      view.webContents.send('heyi-integrated-shell-state', {
        mode: this.mode, loggedIn: this.accountState === 'connected',
        accountState: this.accountState,
        networkState: this.networkState,
      });
    }
  }
  setNetworkState(state) {
    if (!['starting', 'ready', 'stopped'].includes(state)) return;
    this.networkState = state;
    this.sendState();
  }
  setAccountState(state) {
    if (!['signed-out', 'connected', 'paused'].includes(state)) return;
    this.accountState = state;
    this.sendState();
  }
  beforeNativeClose(name, next) {
    if (name === 'login' && next && !next.isDestroyed()) {
      this.mode = 'workbench';
      this.selectWindow(next);
      this.originalShow.get(next)?.();
    }
  }
  showWorkbench() {
    this.mode = 'workbench';this.layout();
    this.showCurrent();
  }
  showNative() {
    this.mode = 'boss';this.layout();
    this.showCurrent();
  }
  showCurrent() {
    if (this.window && !this.window.isDestroyed()) {
      if (this.window.isMinimized()) this.window.restore();
      this.originalShow.get(this.window)?.();this.window.focus();
    }
  }
  state() {
    return {mode: this.mode, nativeName: this.window?.name,
      platformId: this.platform?.webContents.id, toolbarId: this.toolbar?.webContents.id};
  }
}

let shared;
function instance() {return shared || (shared = new IntegratedShell());}
module.exports = {IntegratedShell, boundsFor, instance};
