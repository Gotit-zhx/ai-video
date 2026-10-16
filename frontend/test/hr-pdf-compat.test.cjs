const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../hr-resume-preview.js'), 'utf8');

test('PDF module and worker stay on the same compatible build even after legacy installs polyfills', () => {
  for (const modern of [false, true]) {
    const Promise = modern ? {withResolvers() {}, try() {}} : {};
    const env = vm.createContext({Promise});
    vm.runInContext(source.replace('globalThis.createHrResumePreview=',
      'globalThis.selectedPdfPath=()=>pdfBase;globalThis.createHrResumePreview='), env);
    const expected = modern ? '/vendor/pdfjs' : '/vendor/pdfjs-legacy';
    assert.equal(env.selectedPdfPath(), expected);
    Promise.withResolvers = () => {};Promise.try = () => {};
    assert.equal(env.selectedPdfPath(), expected, 'second preview must not switch workers after polyfill');
  }
});
