// App-owned controller, run by the pinned original Electron in Node mode.
// The vendor EXE/ASAR remain unchanged; only our addon is bound after startup.
const fs = require('node:fs/promises');
const path = require('node:path');
const {spawn} = require('node:child_process');
const {once} = require('node:events');
const {verifyPackage, profileEnvironment} = require('./windows-package.cjs');
const {launchInspecting, evaluateRunning} = require('./inspector-driver.cjs');
const {bindNativeModule} = require('./native-bindings.cjs');
const {waitForProcess} = require('./process-outcome.cjs');

async function run(packageRoot, selfTest = false) {
  if (process.platform !== 'win32') throw Error('Windows launcher required');
  let connection, statusFile, verified, stage = 'verify-package';
  try {
    verified = await verifyPackage(packageRoot);
    stage = 'profile-isolation';
    const profileRoot = selfTest ? process.env.HEYI_WORKBENCH_TEST_DATA :
      path.join(process.env.LOCALAPPDATA || '', 'HeyiBossWorkbench');
    if (selfTest && (!process.env.HEYI_WORKBENCH_TEST_OUTPUT ||
        process.env.HEYI_WORKBENCH_TEST_PORT !== '0')) throw Error('Fresh test profile and output required');
    if (selfTest) {
      const isolatedRoot = await fs.realpath(process.env.HEYI_NATIVE_PROBE_ROOT);
      const firewall = JSON.parse(await fs.readFile(path.join(isolatedRoot, 'firewall.json'), 'utf8'));
      const inside = value => {
        const relative = path.relative(isolatedRoot, value);
        return relative && relative !== '..' && !relative.startsWith('..' + path.sep) && !path.isAbsolute(relative);
      };
      if (firewall.verified !== true || firewall.workbenchIncluded !== true ||
          !inside(verified.root) || !inside(profileRoot) || !inside(process.env.HEYI_WORKBENCH_TEST_OUTPUT)) {
        throw Error('Verified OS-isolated package check required');
      }
      try {await fs.lstat(profileRoot);throw Error('Test profile already exists');}
      catch (error) {if (error.code !== 'ENOENT') throw error;}
      try {await fs.lstat(process.env.HEYI_WORKBENCH_TEST_OUTPUT);throw Error('Test output already exists');}
      catch (error) {if (error.code !== 'ENOENT') throw error;}
    }
    const plan = profileEnvironment(profileRoot);
    for (const directory of [profileRoot, plan.profile, plan.env.APPDATA,
      plan.env.LOCALAPPDATA, plan.env.TEMP]) await fs.mkdir(directory, {recursive: true});
    statusFile = path.join(profileRoot, 'heyi-launcher-status.json');
    stage = 'original-startup';
    connection = await launchInspecting(verified.executable,
      [`--user-data-dir=${plan.profile}`], {
        env: plan.env, cwd: path.dirname(verified.executable), timeout: 45000,
        activate: async child => {
          await new Promise(resolve => setTimeout(resolve, 12000));
          if (child.exitCode !== null) throw Error('Original client exited before addon binding');
          const helper = spawn(verified.executable, ['-e',
            'process._debugProcess(Number(process.argv[1]))', String(child.pid)], {
              cwd: path.dirname(verified.executable),
              env: {...plan.env, ELECTRON_RUN_AS_NODE: '1'}, stdio: 'ignore', windowsHide: true,
            });
          const timer = setTimeout(() => helper.kill(), 5000);
          try {
            const [code] = await once(helper, 'exit');
            if (code !== 0) throw Error('Original runtime helper did not activate');
          } finally {clearTimeout(timer);}
        },
      });
    if (new URL(connection.client.socket.url).port === '9229') {
      throw Error('Dedicated loopback inspector was not used');
    }
    stage = 'original-readiness';
    const deadline = Date.now() + 15000;
    while (!await evaluateRunning(connection, 'typeof globalThis.$socket?.handleEvent === "function"')) {
      if (Date.now() >= deadline) throw Error('Original client did not become ready');
      await new Promise(resolve => setTimeout(resolve, 100));
    }
    stage = 'install-workbench';
    connection.client.timeout = 45000;
    let offlineURL;
    if (selfTest) {
      offlineURL = await evaluateRunning(connection, `(()=>{
        const app=process.mainModule.require('electron').app,fs=process.mainModule.require('node:fs');
        const file=process.mainModule.require('node:path').join(app.getPath('userData'),'heyi-offline.html');
        fs.writeFileSync(file,'<!doctype html><meta charset="utf-8"><input id="fixture">');
        return process.mainModule.require('node:url').pathToFileURL(file).href;
      })()`);
    }
    const installed = await bindNativeModule(connection, {modulePath: verified.addon,
      pinnedOriginalVerified: true, builderDescriptor: verified.requestBuilder,
      config: {profileRoot, assistantRoot: plan.assistantRoot, offline: selfTest, offlineURL}});
    if (installed?.ok !== true) throw Error('Workbench installation incomplete');
    if (selfTest) {
      stage = 'packaged-workbench-check';
      const checked = await connection.client.command('Runtime.evaluate', {returnByValue: true, awaitPromise: true,
        expression: `process.mainModule.require(${JSON.stringify(verified.addon)}).offlineCheck()`});
      if (checked.exceptionDetails || checked.result?.value?.ok !== true) throw Error('Packaged check failed');
      const report = checked.result.value;
      stage = 'workbench-close';
      const closed = await connection.client.command('Runtime.evaluate', {returnByValue: true, awaitPromise: true,
        expression: `process.mainModule.require(${JSON.stringify(verified.addon)}).close().then(()=>true)`});
      if (closed.exceptionDetails || closed.result?.value !== true) throw Error('Workbench close failed');
      stage = 'original-shutdown';
      const exitModule = path.join(path.dirname(verified.addon), 'original-sdk-exit.cjs');
      await evaluateRunning(connection, `setTimeout(()=>{
        process.mainModule.require('node:inspector').close();
        process.mainModule.require(${JSON.stringify(exitModule)}).requestOriginalExit(globalThis.$socket,{});
      },50);true`);
      connection.client.close();
      const outcome = await waitForProcess(connection.child, 15000);
      report.originalExitCode = outcome.code;report.timedOut = outcome.timedOut;
      report.terminationRequested = outcome.terminationRequested;
      report.ok = report.ok && outcome.code === 0 && !outcome.timedOut && !outcome.terminationRequested;
      await fs.writeFile(process.env.HEYI_WORKBENCH_TEST_OUTPUT, JSON.stringify(report, null, 2));
      return report.ok ? 0 : 1;
    }
    stage = 'detach-inspector';
    await evaluateRunning(connection, `setImmediate(() => process.mainModule.require('node:inspector').close()); true`);
    connection.client.close();
    await fs.writeFile(statusFile, JSON.stringify({ok: true, experimental: true,
      stage: 'ready', originalResourcesModified: false}), {mode: 0o600});
    // Stay with this exact child. Never attach to another process or replay
    // addon installation if observation or the native process fails.
    const [code] = connection.child.exitCode === null ?
      await once(connection.child, 'exit') : [connection.child.exitCode];
    if (code !== 0) throw Error('Original client ended unexpectedly');
    return 0;
  } catch (error) {
    let diagnostic = error.packageDiagnostic || error.nativeBindingDiagnostic;
    if (stage === 'install-workbench' && connection && verified) {
      try {
        const state = await evaluateRunning(connection,
          `process.mainModule.require(${JSON.stringify(verified.addon)}).state()`);
        if (state?.diagnostic) diagnostic = {binding: diagnostic, workbench: state.diagnostic};
      } catch {}
    }
    if (statusFile) await fs.writeFile(statusFile,
      JSON.stringify({ok: false, experimental: true, stage}), {mode: 0o600}).catch(() => {});
    if (selfTest && process.env.HEYI_WORKBENCH_TEST_OUTPUT) {
      await fs.writeFile(process.env.HEYI_WORKBENCH_TEST_OUTPUT, JSON.stringify({ok: false, stage, diagnostic,
        candidateMessagesSent: 0, actualBossNetworkUsed: false})).catch(() => {});
    }
    if (connection?.child.exitCode === null) {
      const stopper = spawn('taskkill.exe', ['/PID', String(connection.child.pid), '/T', '/F'],
        {stdio: 'ignore', windowsHide: true});
      const timer = setTimeout(() => {stopper.kill();connection.child.kill();}, 5000);
      try {await once(stopper, 'exit');} catch {connection.child.kill();}
      finally {clearTimeout(timer);}
    }
    return 1;
  } finally {connection?.client.close();}
}

if (require.main === module) {
  run(process.argv[2], process.argv[3] === '--self-test').then(
    code => {process.exitCode = code;}, () => {process.exitCode = 1;});
}
module.exports = {run};
