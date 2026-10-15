// Calibrate inspector metadata against the exact vendor executable in Node
// mode. This runs only our fixture source, not the original app or its SDK.
const fs = require('node:fs/promises');
const path = require('node:path');
const {launchInspecting, evaluateRunning} = require('./inspector-driver.cjs');
const {inspectSdkScopes} = require('./sdk-scope-inventory.cjs');
const {inspectSdkRegistry} = require('./sdk-registry-inventory.cjs');
const {waitForProcess} = require('./process-outcome.cjs');

async function runMatchingScopeFixture(executable, root, env) {
  const fixture = path.join(root, 'scope-protocol-fixture.cjs');
  await fs.writeFile(fixture, `
globalThis.heyiScopeFixtureReads = 0;
const fixtureRegistry = {
  _sdk_getLoginStatus: async()=>{globalThis.heyiScopeFixtureReads++;return false},
  _sdk_getNetworkStatus: async()=>{globalThis.heyiScopeFixtureReads++;return false},
};
globalThis.heyiScopeFixture = ()=>fixtureRegistry;
const keepAlive = setInterval(()=>{},1000);
const deadline = setTimeout(()=>{process.exitCode=2;clearInterval(keepAlive)},15000);
globalThis.heyiScopeFixtureComplete = ()=>{clearInterval(keepAlive);clearTimeout(deadline)};
`);
  let connection;
  const report = {mode: 'matching-runtime-fixture-only', ok: false,
    originalAppCodeExecuted: null, fixtureSdkMethodsInvoked: null};
  try {
    connection = await launchInspecting(executable, [fixture], {
      env: {...env, ELECTRON_RUN_AS_NODE: '1'}, cwd: root,
    });
    const deadline = Date.now() + 5000;
    while (!(await evaluateRunning(connection, 'typeof globalThis.heyiScopeFixture === "function"'))) {
      if (Date.now() >= deadline) throw Error('Matching runtime fixture unavailable');
      await new Promise(resolve => setTimeout(resolve, 25));
    }
    const state = await evaluateRunning(connection, `({
      versions:process.versions,
      originalAppCodeExecuted:Object.keys(process.mainModule.require('node:module')._cache)
        .some(file=>/[\\\\/]main\\.jsc$/.test(file) ||
          /[\\\\/]app\\.asar[\\\\/](?:main\\.js|heyi[\\\\/]bootstrap\\.cjs)$/.test(file)),
      fixtureMain:process.mainModule.require('node:fs').realpathSync(process.mainModule.filename)===
        process.mainModule.require('node:fs').realpathSync(${JSON.stringify(fixture)})
    })`);
    Object.assign(report, state);
    try {
      report.inventory = await inspectSdkScopes(connection, 'globalThis.heyiScopeFixture', {
        triggerExpression: 'globalThis.heyiScopeFixture()',
      });
    } catch (error) {
      report.inventory = {diagnostic: error.inventoryDiagnostic || {stage: 'fixture-inspection'}};
    }
    try {
      report.registryInventory = await inspectSdkRegistry(connection, {anonymousIsolated: true});
    } catch (error) {
      report.registryInventory = {ok: false, diagnostic: error.inventoryDiagnostic};
    }
    report.fixtureSdkMethodsInvoked = await evaluateRunning(connection, 'globalThis.heyiScopeFixtureReads');
    const outcome = waitForProcess(connection.child, 5000);
    outcome.catch(() => {});
    await evaluateRunning(connection, 'globalThis.heyiScopeFixtureComplete();true');
    connection.client.close();
    report.processOutcome = await outcome;
    report.ok = report.originalAppCodeExecuted === false && report.fixtureMain === true &&
      report.fixtureSdkMethodsInvoked === 0 &&
      report.inventory.scopes?.some(scope =>
        scope.registries?.some(registry => registry.binding === 'fixtureRegistry')) === true &&
      report.registryInventory.ok === true &&
      report.processOutcome.code === 0 && !report.processOutcome.timedOut;
  } catch {
    report.reason = 'matching-runtime-fixture-incomplete';
  } finally {
    connection?.client.close();
    if (connection?.child.exitCode === null) connection.child.kill();
  }
  return report;
}

module.exports = {runMatchingScopeFixture};
