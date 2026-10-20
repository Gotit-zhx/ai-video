const test = require('node:test');
const assert = require('node:assert/strict');
const {classifyFailure, legacyBoundarySafe} = require('./windows-adapter-contract.cjs');

test('original SDK diagnostics retain a fixed cause and source offset without original values', () => {
  const error = new TypeError("Cannot read properties of undefined (reading 'userId')");
  error.stack = 'TypeError: private message\n at f (C:\\private\\account\\resources\\app.asar\\main.jsc:1:1234)';
  assert.deepEqual(classifyFailure(error), {kind: 'TypeError', category: 'missing-userId',
    originalMainLocation: {line: 1, column: 1234}});
  const unknown = new Error('private-cookie-value');
  unknown.stack = 'Error: private-cookie-value\n at C:\\private\\unknown.js:2:3';
  assert.deepEqual(classifyFailure(unknown), {kind: 'Error', category: 'unclassified'});
  assert.deepEqual(classifyFailure(new SyntaxError('Unexpected token in JSON')),
    {kind: 'SyntaxError', category: 'invalid-json-input'});
  assert.deepEqual(classifyFailure(undefined), {kind: 'other', category: 'unclassified'});
});

test('legacy diagnostics permit only verified encoding or signed-out identity rejection', () => {
  const rejected = {textSDKOutcome: 'rejected', interceptedNativeSends: 0, interceptedHTTPCalls: 0,
    textSDKFailure: {category: 'missing-userId'}};
  assert.equal(legacyBoundarySafe(rejected), true);
  assert.equal(legacyBoundarySafe({...rejected, textSDKOutcome: 'timed-out'}), false);
  assert.equal(legacyBoundarySafe({...rejected, interceptedNativeSends: 1}), false);
  assert.equal(legacyBoundarySafe({...rejected, interceptedHTTPCalls: 1}), false);
  assert.equal(legacyBoundarySafe({...rejected, textSDKFailure: {category: 'unclassified'}}), false);
  assert.equal(legacyBoundarySafe({...rejected, textSDKOutcome: 'settled'}), false);
  assert.equal(legacyBoundarySafe({...rejected, textSDKOutcome: 'settled',
    interceptedNativeSends: 1, textPacketMatches: true}), true);
});
