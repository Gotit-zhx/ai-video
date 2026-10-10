const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../app.js'), 'utf8');
const c = vm.createContext({});
vm.runInContext(source.slice(source.indexOf('function productPublishRemoteStatus('),
  source.indexOf('function productPublishWorkflowError(')), c);

test('verified TikTok drafts and Shopee unlisted products show successful pending listing', () => {
  for (const remoteStatus of ['DRAFT', 'UNLIST']) {
    const result = c.productPublishOutcome({executionMode:'LIVE',
      publication:{dryRun:false,remoteStatus}});
    assert.equal(result.kind, 'unlisted');
    assert.equal(result.stepText, '已上传至待上架');
  }
});

test('dry runs and unknown platform results cannot appear as successful draft uploads', () => {
  assert.equal(c.productPublishOutcome({executionMode:'DRY_RUN',
    publication:{dryRun:true,remoteStatus:'DRAFT'}}).kind, 'dry-run');
  assert.equal(c.productPublishOutcome({executionMode:'LIVE',
    publication:{dryRun:false,remoteStatus:''}}).kind, 'submitted');
});
