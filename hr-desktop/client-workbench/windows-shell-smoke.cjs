// Real Windows original-window UI integration, with OS network blocking and a
// disposable profile. Only our local fixture input is edited; no BOSS actions.
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const {pathToFileURL} = require('node:url');

async function run(electron, root) {
  const {app, BrowserWindow, clipboard, session} = electron;
  if (process.platform !== 'win32' || !app.isReady()) throw Error('Ready Windows original runtime required');
  const inside = target => {
    const rel = path.relative(root,target);
    return rel && rel !== '..' && !rel.startsWith('..'+path.sep) && !path.isAbsolute(rel);
  };
  if (!inside(app.getPath('userData')) || !inside(app.getPath('appData'))) {
    throw Error('Disposable original profile required');
  }
  const {IntegratedShell} = require('./integrated-shell.cjs');
  const windows = BrowserWindow.getAllWindows();
  const login = windows.find(win=>win.name==='login');
  const main = windows.find(win=>win.name==='main');
  if (!login || !main || windows.some(win=>win.isVisible())) {
    throw Error('Expected isolated original hidden windows');
  }
  const fixture = path.join(root,'heyi-shell-local-fixture.html');
  await fs.writeFile(fixture,
    '<!doctype html><meta charset="utf-8"><input id="sample" aria-label="本机粘贴测试">');
  const originalClipboard = clipboard.readText();
  const integrated = new IntegratedShell();
  let stage='attach-original-windows';
  const until = async check => {
    const deadline = Date.now()+3000;
    while (!await check()) {
      if (Date.now()>=deadline) throw Error('Native UI state did not settle');
      await new Promise(resolve=>setTimeout(resolve,20));
    }
  };
  try {
    integrated.attachWindow(login,'login');
    integrated.attachWindow(main,'main');
    integrated.configure({session:session.fromPartition('heyi-offline-windows-shell'),
      url:pathToFileURL(fixture).href,origin:'null'});
    stage='load-local-views';
    await Promise.all([integrated.platformReady,integrated.chromeReady]);
    stage='original-login-layout';
    integrated.showNative();
    assert.equal(BrowserWindow.getAllWindows().filter(win=>win.isVisible()).length,1);
    const css = await login.webContents.executeJavaScript(
      'getComputedStyle(document.querySelector("#app")).left');
    assert.equal(css,'88px');
    stage='sidebar-labels';
    const labels = await integrated.sidebar.webContents.executeJavaScript(
      'Array.from(document.querySelectorAll("nav button")).map(node=>node.innerText.trim().split("\\n").pop())');
    assert.deepEqual(labels,['BOSS','系统']);
    const click = async action => {
      await integrated.sidebar.webContents.executeJavaScript(
        `document.querySelector('[data-action="${action}"]').click()`);
      await until(()=>integrated.mode===action);
    };
    stage='sidebar-switch-to-system';
    await click('workbench');
    assert.equal(login.getBrowserViews().includes(integrated.platform),true);
    const platformId=integrated.platform.webContents.id;
    stage='local-fixture-paste';
    clipboard.writeText('赫逸 Windows 粘贴测试');
    await integrated.platform.webContents.executeJavaScript('document.querySelector("#sample").focus()');
    integrated.platform.webContents.paste();
    await until(async()=>await integrated.platform.webContents.executeJavaScript(
      'document.querySelector("#sample").value')==='赫逸 Windows 粘贴测试');
    stage='sidebar-switch-round-trip';
    await click('boss');
    assert.equal(login.getBrowserViews().includes(integrated.platform),false);
    await click('workbench');
    stage='login-to-main-migration';
    integrated.beforeNativeClose('login',main);
    assert.equal(login.isVisible(),false);
    assert.equal(main.isVisible(),true);
    assert.equal(main.getBrowserViews().includes(integrated.platform),true);
    assert.equal(integrated.platform.webContents.id,platformId);
    assert.equal(BrowserWindow.getAllWindows().length,windows.length);
    assert.equal(BrowserWindow.getAllWindows().filter(win=>win.isVisible()).length,1);
    stage='black-brand';
    const brand = await integrated.toolbar.webContents.executeJavaScript(
      'getComputedStyle(document.querySelector(".brand")).color');
    assert.equal(brand,'rgb(0, 0, 0)');
    return {ok:true, originalWindows:true, singleVisibleWindow:true, localFixturePaste:true,
      sidebarSwitching:true, viewMigration:true, lateNativeLayout:true, blackBrand:true,
      candidateMessagesSent:0, realAccountProvided:false, actualBossNetworkUsed:false};
  } catch (error) {
    return {ok:false, reason:'original-window-shell-integration-incomplete', stage,
      failureKind:error.code==='ERR_ASSERTION'?'assertion-failed':'native-ui-action-failed',
      candidateMessagesSent:0,realAccountProvided:false,actualBossNetworkUsed:false};
  } finally {
    clipboard.writeText(originalClipboard);
    for (const win of windows) if (!win.isDestroyed()) win.hide();
  }
}

module.exports = {run};
