// Launch only the SHA-pinned, OS-firewalled copy prepared by windows-native-probe.ps1.
const fs = require('node:fs/promises');
const path = require('node:path');
const crypto = require('node:crypto');
const {once} = require('node:events');
const {launchInspecting, evaluateRunning} = require('./inspector-driver.cjs');

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
    connection = await launchInspecting(executable, ['--disable-gpu',
      `--user-data-dir=${path.join(root, 'profile')}`], {
      env, cwd: path.dirname(executable), timeout: 20000,
      onSpawn: child => fs.writeFile(path.join(root, 'probe-pid.txt'), String(child.pid)).catch(() => {}),
    });
    const loader = '(typeof require === "function" ? require : process.mainModule?.require.bind(process.mainModule))';
    let entry;
    // Poll only presence/type metadata, never execute an SDK method.
    for (let attempt = 0; attempt < 40; attempt++) {
      entry = await evaluateRunning(connection,
        `({platform:process.platform, arch:process.arch, versions:process.versions, requireType:typeof ${loader}})`);
      if (entry?.requireType === 'function') break;
      await new Promise(resolve => setTimeout(resolve, 200));
    }
    await fs.writeFile(path.join(root, 'entry-runtime.json'), JSON.stringify({
      ...entry, observationStage: 'running-original-client',
    }, null, 2));
    if (entry?.platform !== 'win32' || entry.arch !== 'ia32' || entry.requireType !== 'function') {
      throw Error('Unexpected original running environment');
    }
    const observer = path.join(__dirname, 'windows-runtime-observer.cjs');
    const result = await evaluateRunning(connection,
      `((load) => load(${JSON.stringify(observer)})(load('electron'), ${JSON.stringify({root, output})}))(${loader})`);
    if (!result?.observerInstalled) throw Error('Runtime observer was not installed');
    connection.client.close();
    const exit = once(connection.child, 'exit');
    const timer = setTimeout(() => connection.child.kill(), 35000);
    const [code] = await exit;
    clearTimeout(timer);
    const report = JSON.parse(await fs.readFile(output, 'utf8'));
    report.executableSha256 = actual;
    report.osFirewall = firewall;
    report.processExitCode = code;
    report.ok = report.ok && code === 0;
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
