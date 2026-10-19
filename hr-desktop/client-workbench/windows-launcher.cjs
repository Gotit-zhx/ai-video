// App-owned controller, run by the pinned original Electron in Node mode.
// The vendor EXE/ASAR remain unchanged; only our addon is bound after startup.
const fs = require('node:fs/promises');
const path = require('node:path');
const {spawn} = require('node:child_process');
const {once} = require('node:events');
const net = require('node:net');
const {verifyPackage, profileEnvironment} = require('./windows-package.cjs');
const {launchInspecting, evaluateRunning} = require('./inspector-driver.cjs');
const {bindNativeModule} = require('./native-bindings.cjs');
const {waitForProcess} = require('./process-outcome.cjs');
const {launchStatus, waitForEntry, waitForNativeWindow} = require('./windows-launch-status.cjs');

async function launchWorkbenchProcess(executable, args, {env = process.env, cwd, onSpawn, waitUntilReady} = {}) {
  if (typeof waitUntilReady !== 'function') throw Error('Native window readiness observer required');
  return launchInspecting(executable, args, {
    env, cwd, onSpawn, timeout: 60000,
    // Node 16 defines --debug-port as an alias of --inspect-port. Both set
    // the future address without enabling a debugger before cached startup.
    // Use the pinned runtime's supported alias and bind only an OS-assigned
    // loopback port; no connection to a pre-existing debugger is attempted.
    inspectPortArgument: true, latePortOption: 'debug-port',
    activate: async child => {
      await waitUntilReady(child);
      if (child.exitCode !== null || child.signalCode !== null) {
        throw Error('Original client exited before addon binding');
      }
      const helper = spawn(executable, ['-e',
        'process._debugProcess(Number(process.argv[1]))', String(child.pid)], {
          cwd, env: {...env, ELECTRON_RUN_AS_NODE: '1'}, stdio: 'ignore', windowsHide: true,
        });
      const timer = setTimeout(() => helper.kill(), 5000);
      try {
        const [code] = await once(helper, 'exit');
        if (code !== 0) throw Error('Original runtime helper did not activate');
      } finally {clearTimeout(timer);}
    },
  });
}

async function run(packageRoot, selfTest = false) {
  if (process.platform !== 'win32') throw Error('Windows launcher required');
  let connection, reportStatus, nativePid, verified, testOutput, reservedPort, defaultPortConnections = 0,
    stage = 'verify-package';
  const progress = (next, extra = {}) => {
    stage = next;
    reportStatus?.({ok: next === 'ready', stage, nativePid, ...extra});
  };
  try {
    const profileRoot = selfTest ? process.env.HEYI_WORKBENCH_TEST_DATA :
      path.join(process.env.LOCALAPPDATA || '', 'HeyiBossWorkbench');
    if (selfTest && (!process.env.HEYI_WORKBENCH_TEST_OUTPUT ||
        process.env.HEYI_WORKBENCH_TEST_PORT !== '0')) throw Error('Fresh test profile and output required');
    if (selfTest) {
      stage = 'test-isolation';
      const isolatedRoot = await fs.realpath(process.env.HEYI_NATIVE_PROBE_ROOT);
      const packageDirectory = await fs.realpath(packageRoot);
      const firewall = JSON.parse(await fs.readFile(path.join(isolatedRoot, 'firewall.json'), 'utf8'));
      const inside = value => {
        const relative = path.relative(isolatedRoot, value);
        return relative && relative !== '..' && !relative.startsWith('..' + path.sep) && !path.isAbsolute(relative);
      };
      if (firewall.verified !== true || firewall.workbenchIncluded !== true ||
          !inside(packageDirectory) || !inside(profileRoot) || !inside(process.env.HEYI_WORKBENCH_TEST_OUTPUT)) {
        throw Error('Verified OS-isolated package check required');
      }
      try {await fs.lstat(profileRoot);throw Error('Test profile already exists');}
      catch (error) {if (error.code !== 'ENOENT') throw error;}
      try {await fs.lstat(process.env.HEYI_WORKBENCH_TEST_OUTPUT);throw Error('Test output already exists');}
      catch (error) {if (error.code !== 'ENOENT') throw error;}
      testOutput = process.env.HEYI_WORKBENCH_TEST_OUTPUT;
    }
    await fs.mkdir(profileRoot, {recursive: true});
    reportStatus = launchStatus(profileRoot, process.env.HEYI_LAUNCH_ID, 'heyi-launcher-status.json');
    progress('verify-package');
    verified = await verifyPackage(packageRoot);
    progress('profile-isolation');
    const plan = profileEnvironment(profileRoot);
    // The packaged worker independently requires this disposable-profile
    // marker before it will start its loopback-only test relay.
    if (selfTest) plan.env.HEYI_TEST_USER_DATA = plan.profile;
    for (const directory of [profileRoot, plan.profile, plan.env.APPDATA,
      plan.env.LOCALAPPDATA, plan.env.USERPROFILE, plan.env.TEMP]) await fs.mkdir(directory, {recursive: true});
    if (selfTest) {
      // The actual packaged entry must coexist with another local service.
      // This listener is created only inside the verified disposable test.
      stage = 'test-default-port-occupied';
      reservedPort = net.createServer(socket => {defaultPortConnections++;socket.destroy();});
      await new Promise((resolve, reject) => {
        reservedPort.once('error', reject);
        reservedPort.listen(9229, '127.0.0.1', resolve);
      });
    }
    progress('original-startup');
    connection = await launchWorkbenchProcess(verified.executable,
      [`--user-data-dir=${plan.profile}`], {
        env: plan.env, cwd: path.dirname(verified.executable),
        onSpawn: child => {nativePid = child.pid;progress('original-startup');},
        waitUntilReady: child => waitForNativeWindow(profileRoot, process.env.HEYI_LAUNCH_ID, child),
      });
    if (new URL(connection.client.socket.url).port === '9229') {
      throw Error('Original runtime did not use an independent inspector port');
    }
    progress('original-readiness');
    const deadline = Date.now() + 15000;
    while (!await evaluateRunning(connection, 'typeof globalThis.$socket?.handleEvent === "function"')) {
      if (Date.now() >= deadline) throw Error('Original client did not become ready');
      await new Promise(resolve => setTimeout(resolve, 100));
    }
    progress('install-workbench');
    connection.client.timeout = 90000;
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
      config: {profileRoot, assistantRoot: plan.assistantRoot, offline: selfTest, offlineURL,
        launchId: process.env.HEYI_LAUNCH_ID, entryExecutable: path.join(verified.root, 'Ai boss.exe')}});
    if (installed?.ok !== true) throw Error('Workbench installation incomplete');
    if (selfTest) {
      // The same real entry's gate must release before UI assertions run.
      progress('ready');
      await waitForEntry(profileRoot, process.env.HEYI_LAUNCH_ID);
      if (process.env.HEYI_WORKBENCH_INSTALLATION_PROBE === '1') {
        // Only the verified disposable installation probe may hold this entry
        // while exercising real concurrent clicks and updater rejection.
        const barrier = path.join(profileRoot, 'installer-checks-complete');
        const end = Date.now() + 90000;
        while (true) {
          try {await fs.access(barrier);break;}
          catch (error) {
            if (error.code !== 'ENOENT' || Date.now() > end) throw Error('Installation probe did not finish');
          }
          await new Promise(resolve => setTimeout(resolve, 100));
        }
      }
      stage = 'packaged-workbench-check';
      const checked = await connection.client.command('Runtime.evaluate', {returnByValue: true, awaitPromise: true,
        expression: `process.mainModule.require(${JSON.stringify(verified.addon)}).offlineCheck()`});
      if (checked.exceptionDetails || checked.result?.value?.ok !== true) throw Error('Packaged check failed');
      const report = checked.result.value;
      report.independentInspector = true;
      report.installerGuardBarrierUsed = process.env.HEYI_WORKBENCH_INSTALLATION_PROBE === '1';
      report.startupOutput = connection.startupOutput;
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
      report.defaultPortReservedByTest = reservedPort.listening && reservedPort.address().port === 9229;
      report.defaultPortConnections = defaultPortConnections;
      report.shutdown = JSON.parse(await fs.readFile(path.join(profileRoot, 'workbench-exit-check.json'), 'utf8'));
      report.ok = report.ok && outcome.code === 0 && !outcome.timedOut && !outcome.terminationRequested &&
        report.defaultPortReservedByTest && report.defaultPortConnections === 0 &&
        report.shutdown.nativeExitGuardInvoked === true &&
        report.shutdown.hostClosedBeforeNativeExit === true && report.shutdown.platformSessionSaved === true;
      await fs.writeFile(testOutput, JSON.stringify(report, null, 2), {flag: 'wx'});
      return report.ok ? 0 : 1;
    }
    progress('detach-inspector');
    await evaluateRunning(connection, `setImmediate(() => process.mainModule.require('node:inspector').close()); true`);
    connection.client.close();
    progress('ready', {experimental: true, originalResourcesModified: false,
      startupOutput: connection.startupOutput});
    await waitForEntry(profileRoot, process.env.HEYI_LAUNCH_ID);
    // Stay with this exact child. Never attach to another process or replay
    // addon installation if observation or the native process fails.
    const [code] = connection.child.exitCode === null ?
      await once(connection.child, 'exit') : [connection.child.exitCode];
    if (code !== 0) throw Error('Original client ended unexpectedly');
    return 0;
  } catch (error) {
    let diagnostic = error.packageDiagnostic || error.nativeBindingDiagnostic ||
      (error.inspectorStartupDiagnostic ? {inspector: error.inspectorStartupDiagnostic} : undefined);
    if (stage === 'install-workbench' && connection && verified) {
      try {
        const state = await evaluateRunning(connection,
          `process.mainModule.require(${JSON.stringify(verified.addon)}).state()`);
        if (state?.diagnostic) diagnostic = {binding: diagnostic, workbench: state.diagnostic};
      } catch {}
    }
    try {reportStatus?.({ok: false, experimental: true, stage, diagnostic, nativePid});} catch {}
    if (selfTest && testOutput) {
      await fs.writeFile(testOutput, JSON.stringify({ok: false, stage, diagnostic,
        candidateMessagesSent: 0, actualBossNetworkUsed: false}), {flag: 'wx'}).catch(() => {});
    }
    if (connection?.child.exitCode === null) {
      const stopper = spawn('taskkill.exe', ['/PID', String(connection.child.pid), '/T', '/F'],
        {stdio: 'ignore', windowsHide: true});
      const timer = setTimeout(() => {stopper.kill();connection.child.kill();}, 5000);
      try {await once(stopper, 'exit');} catch {connection.child.kill();}
      finally {clearTimeout(timer);}
    }
    return 1;
  } finally {
    connection?.client.close();
    if (reservedPort?.listening) await new Promise(resolve => reservedPort.close(resolve));
  }
}

if (require.main === module) {
  run(process.argv[2], process.argv[3] === '--self-test').then(
    code => {process.exitCode = code;}, () => {process.exitCode = 1;});
}
module.exports = {run, launchWorkbenchProcess};
