// Launch only the SHA-pinned, OS-firewalled copy prepared by windows-native-probe.ps1.
const fs = require('node:fs/promises');
const path = require('node:path');
const crypto = require('node:crypto');
const {once} = require('node:events');
const {launchPaused, evaluateEntry} = require('./inspector-driver.cjs');

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
    connection = await launchPaused(executable, ['--disable-gpu'], {
      env, cwd: path.dirname(executable), timeout: 20000,
      onSpawn: child => fs.writeFile(path.join(root, 'probe-pid.txt'), String(child.pid)).catch(() => {}),
    });
    const entry = await evaluateEntry(connection,
      '({platform:process.platform, arch:process.arch, versions:process.versions, requireType:typeof require})');
    if (entry?.platform !== 'win32' || entry.arch !== 'ia32' || entry.requireType !== 'function') {
      throw Error('Unexpected original entry environment');
    }
    await fs.writeFile(path.join(root, 'entry-runtime.json'), JSON.stringify(entry, null, 2));
    const observer = path.join(__dirname, 'windows-runtime-observer.cjs');
    const result = await evaluateEntry(connection,
      `require(${JSON.stringify(observer)})(require('electron'), ${JSON.stringify({root, output})})`);
    if (!result?.observerInstalled) throw Error('Runtime observer was not installed');
    await connection.client.command('Debugger.resume');
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
