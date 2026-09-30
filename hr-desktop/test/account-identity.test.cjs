const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs/promises');
const os=require('node:os');
const path=require('node:path');
const vm=require('node:vm');
const {resolveAccount,bindAccount,identityKey,sampleAccountIdentity}=require('../src/account-identity.cjs');
test('same recruiter reuses history after cookie rotation while other users stay isolated',async t=>{
  const base=await fs.mkdtemp(path.join(os.tmpdir(),'heyi-identity-'));
  t.after(()=>fs.rm(base,{recursive:true,force:true}));
  const owner='a'.repeat(64),legacy='b'.repeat(64);
  await fs.mkdir(path.join(base,'accounts',owner,legacy),{recursive:true});
  const first=await resolveAccount(base,owner,'123',legacy);
  await bindAccount(first.root,first.identity);
  assert.equal((await resolveAccount(base,owner,'123','c'.repeat(64))).root,first.root);
  assert.notEqual((await resolveAccount(base,owner,'456',legacy)).root,first.root);
  assert.notEqual(identityKey('d'.repeat(64),'123'),first.identity);
});
test('ambiguous bindings fail closed, without choosing a different history',async t=>{
  const base=await fs.mkdtemp(path.join(os.tmpdir(),'heyi-identity-'));
  t.after(()=>fs.rm(base,{recursive:true,force:true}));
  const owner='a'.repeat(64),identity=identityKey(owner,'123');
  for(const key of ['b','c']){
    const root=path.join(base,'accounts',owner,key.repeat(64));
    await fs.mkdir(root,{recursive:true});await bindAccount(root,identity);
  }
  await assert.rejects(resolveAccount(base,owner,'123','d'.repeat(64)),/多份历史/);
});
test('official profile sampler rejects missing or imprecise IDs',()=>{
  for(const id of [undefined,0,Number.MAX_SAFE_INTEGER+2]){
    const sample=vm.runInNewContext(`(${sampleAccountIdentity})()`,{
      document:{querySelector:()=>({__vue__:{user$:{userId:id,wt2:'synthetic'}}})}});
    assert.equal(sample,null);
  }
});
