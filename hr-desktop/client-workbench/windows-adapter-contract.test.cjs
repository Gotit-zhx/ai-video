const test = require('node:test');
const assert = require('node:assert/strict');
const {classifyFailure} = require('./windows-adapter-contract.cjs');

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
