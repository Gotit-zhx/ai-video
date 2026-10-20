const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const {nativeReadiness, waitForOriginalReady} = require('./windows-native-ready.cjs');

test('dispatcher and a generic Chromium window do not mean native initialization is complete', () => {
  let ready = false;
  const windows = [{name: undefined, isDestroyed: () => false,
    webContents: {isDestroyed: () => false}, isVisible: () => {throw Error('visibility is not a gate');}}];
  const context = {process: {mainModule: {require: name => {
    assert.equal(name, 'electron');
    return {app: {isReady: () => ready}, BrowserWindow: {getAllWindows: () => windows}};
  }}}};
  const sample = () => vm.runInNewContext(`(${nativeReadiness.toString()})()`, context);
  assert.equal(sample(), 'electron');
  ready = true;
  assert.equal(sample(), 'dispatcher');
  context.$socket = {handleEvent() {}};
  assert.equal(sample(), 'native-window');
  windows[0].name = 'login';
  assert.equal(sample(), 'ready', 'a hidden named window is sufficient; no official popup or login required');
  windows[0].webContents.isDestroyed = () => true;
  assert.equal(sample(), 'native-window');
});

test('waits across delayed named-window initialization and reports the actual incomplete phase', async () => {
  const stages = ['dispatcher', 'native-window', 'native-window', 'ready'];
  const observed = [];
  const connection = {child: {exitCode: null, signalCode: null},
    client: {command: async () => ({result: {value: stages.shift()}})}};
  await waitForOriginalReady(connection, {timeout: 1000, onProgress: stage => observed.push(stage)});
  assert.deepEqual(observed, ['dispatcher', 'native-window']);
  connection.client.command = async () => ({result: {value: 'native-window'}});
  await assert.rejects(waitForOriginalReady(connection, {timeout: 1}),
    error => error.nativeBindingDiagnostic.stage === 'native-window');
  connection.child.exitCode = 0;
  await assert.rejects(waitForOriginalReady(connection), /exited/);
});
