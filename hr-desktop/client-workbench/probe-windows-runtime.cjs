// Launch only the SHA-pinned, OS-firewalled copy prepared by windows-native-probe.ps1.
const fs = require('node:fs/promises');
const path = require('node:path');
const crypto = require('node:crypto');
const {once} = require('node:events');
const {spawn} = require('node:child_process');
const net = require('node:net');
const {launchInspecting, evaluateRunning} = require('./inspector-driver.cjs');
const {waitForProcess} = require('./process-outcome.cjs');

async function main() {
  if (process.platform !== 'win32') throw Error('Windows runtime required');
  const [executableArg, rootArg] = process.argv.slice(2);
  if (!executableArg || !rootArg) throw Error('Disposable executable and root required');
  const root = await fs.realpath(rootArg), executable = await fs.realpath(executableArg);
  if (path.relative(root, executable).startsWith('..')) throw Error('Executable must be disposable');
  const expected = 'be47a9065167f57d43933f0d6cb1c6f764084dcc3ba88ef47ba212283d6e953f';
  const actual = crypto.createHash('sha256').update(await fs.readFile(executable)).digest('hex');
  if (actual !== expected) throw Error('Original executable fingerprint mismatch');
  const firewall = JSON.parse(await fs.readFile(path.join(root, 'firewall.json'), 'utf8'));
  if (firewall.verified !== true || firewall.executables < 1) throw Error('Verified OS network isolation required');
  const output = path.join(root, 'runtime-observation.json');
  const env = {...process.env, ELECTRON_ENABLE_LOGGING: '1',
    APPDATA: path.join(root, 'app-data'), LOCALAPPDATA: path.join(root, 'local-app-data'),
    USERPROFILE: path.join(root, 'user'), TEMP: path.join(root, 'temp'), TMP: path.join(root, 'temp'),
  };
  for (const name of ['ELECTRON_RUN_AS_NODE', 'NODE_OPTIONS', 'NODE_PATH']) delete env[name];
  for (const name of ['APPDATA', 'LOCALAPPDATA', 'USERPROFILE', 'TEMP']) await fs.mkdir(env[name], {recursive: true});
  let connection;
  try {
    // Without any inspector CLI flag the original bytecode starts normally.
    // Reserve/check the default late-activation port before creating our app;
    // never attach to a pre-existing debugger on the runner.
    const portCheck = net.createServer();
    await new Promise((resolve, reject) => {
      portCheck.once('error', () => reject(Error('Default local inspector port is already occupied')));
      portCheck.listen(9229, '127.0.0.1', resolve);
    });
    await new Promise(resolve => portCheck.close(resolve));
    connection = await launchInspecting(executable, ['--disable-gpu',
      `--user-data-dir=${path.join(root, 'profile')}`], {
      env, cwd: path.dirname(executable), timeout: 45000,
      inspectPortArgument: false,
      onSpawn: child => fs.writeFile(path.join(root, 'probe-pid.txt'), String(child.pid)).catch(() => {}),
      activate: async child => {
        // Let the original cached main code load without a startup inspector.
        // Node's own x86 debug helper uses its matching Windows debug mapping.
        await new Promise(resolve => setTimeout(resolve, 12000));
        if (child.exitCode !== null) throw Error('Original process exited before late attach');
        const helper = spawn(executable, ['-e',
          'process._debugProcess(Number(process.argv[1]))', String(child.pid)], {
          cwd: path.dirname(executable), env: {...env, ELECTRON_RUN_AS_NODE: '1'},
          stdio: 'ignore', windowsHide: true,
        });
        const deadline = setTimeout(() => helper.kill(), 5000);
        try {
          const [code] = await once(helper, 'exit');
          if (code !== 0) throw Error('Original x86 debug helper failed');
        } finally {clearTimeout(deadline);}
      },
    });
    const outcome = waitForProcess(connection.child, 35000);
    outcome.catch(() => {});
    const loader = '(typeof require === "function" ? require : process.mainModule?.require.bind(process.mainModule))';
    let entry;
    // Poll only presence/type metadata, never execute an SDK method.
    for (let attempt = 0; attempt < 40; attempt++) {
      entry = await evaluateRunning(connection,
        `({platform:process.platform, arch:process.arch, versions:process.versions, requireType:typeof ${loader},
          sdkNames:Object.entries(Object.getOwnPropertyDescriptors(globalThis))
            .filter(([name])=>/^_sdk_[A-Za-z0-9_]+$/.test(name))
            .map(([name,entry])=>({name,type:typeof entry.value}))})`);
      if (entry?.requireType === 'function') break;
      await new Promise(resolve => setTimeout(resolve, 200));
    }
    if (entry?.requireType === 'function') {
      entry.profileState = await evaluateRunning(connection,
        `((load,root)=>{
          const app=load('electron').app, path=load('node:path');
          const inside=value=>{const r=path.relative(root,value);return Boolean(r)&&r!=='..'&&!r.startsWith('..'+path.sep)&&!path.isAbsolute(r)};
          return {ready:app.isReady(),userDataUnderTestRoot:inside(app.getPath('userData')),appDataUnderTestRoot:inside(app.getPath('appData'))};
        })(${loader},${JSON.stringify(root)})`);
    }
    await fs.writeFile(path.join(root, 'entry-runtime.json'), JSON.stringify({
      ...entry, observationStage: 'late-attached-original-client',
    }, null, 2));
    if (entry?.platform !== 'win32' || entry.arch !== 'ia32' || entry.requireType !== 'function') {
      throw Error('Unexpected original running environment');
    }
    const observer = path.join(__dirname, 'windows-runtime-observer.cjs');
    const result = await evaluateRunning(connection,
      `((load) => load(${JSON.stringify(observer)})(load('electron'), ${JSON.stringify({root, output})}))(${loader})`);
    if (!result?.observerInstalled) throw Error('Runtime observer was not installed');
    connection.client.close();
    const processOutcome = await outcome;
    const report = JSON.parse(await fs.readFile(output, 'utf8'));
    report.executableSha256 = actual;
    report.osFirewall = firewall;
    report.processExitCode = processOutcome.code;
    report.processOutcome = processOutcome;
    report.ok = report.ok && processOutcome.code === 0 && !processOutcome.timedOut;
    await fs.writeFile(output, JSON.stringify(report, null, 2));
    if (!report.ok) throw Error('Original runtime observation incomplete; inspect the report');
    console.log(JSON.stringify({ok: true, arch: report.arch, electron: report.versions.electron,
      sdkNames: report.observedSdk.length, nativeModules: report.nativeModules.length,
      candidateMessagesSent: 0}));
  } finally {
    connection?.client.close();
    if (connection?.child.exitCode === null) connection.child.kill();
  }
}
main().catch(error => {
  // No vendor stdout, response bodies, cookies or inspector payloads in CI logs.
  console.error(error.message);
  process.exitCode = 1;
});
