const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');

function fixture() {
  let module='CAPTURE',org='org';
  const requests=[];
  const window={};
  const context=vm.createContext({window,commerceDataModule:()=>module});
  vm.runInContext(fs.readFileSync(require.resolve('../product-listing-templates.js'),'utf8'),context);
  // init attaches DOM controls; the API context is injected before its event binding.
  const elements=new Map();
  const element=()=>({addEventListener(){},close(){},querySelectorAll:()=>[],dataset:{}});
  context.document={getElementById:id=>{if(!elements.has(id))elements.set(id,element());return elements.get(id);},querySelectorAll:()=>[]};
  window.ProductListingTemplates.init({
    getUser:()=>({organizationId:org}),canEdit:()=>true,
    api:(path,options)=>new Promise((resolve,reject)=>requests.push({path,options,resolve,reject})),
  });
  return {api:window.ProductListingTemplates,requests,module:value=>module=value,org:value=>org=value};
}
function response(name) {
  return {data:[{platform:'SHOPEE',name},{platform:'TIKTOK_SHOP',name}]};
}
test('configuration caches are independent for every module and pin request headers',async()=>{
  const f=fixture();
  for(const module of ['CAPTURE','FISSION','TEMPLATES','ADS','ASSETS']) {
    f.module(module);
    assert.equal(f.api.get('SHOPEE'),null);
    const pending=f.api.ensure();
    const request=f.requests.at(-1);
    assert.equal(request.options.headers['X-Commerce-Module'],module);
    request.resolve(response(module));
    await pending;
    assert.equal(f.api.get('SHOPEE').name,module);
    const count=f.requests.length;
    await f.api.ensure();
    assert.equal(f.requests.length,count);
  }
});
test('a late old-module response cannot overwrite a new module configuration',async()=>{
  const f=fixture();
  const capture=f.api.ensure();
  f.module('FISSION');
  const fission=f.api.ensure();
  f.requests[1].resolve(response('Fission'));
  await fission;
  f.requests[0].resolve(response('Capture'));
  await assert.rejects(capture,/已变更/);
  assert.equal(f.api.get('SHOPEE').name,'Fission');
});
test('a late old-organization response cannot populate another organization',async()=>{
  const f=fixture();
  const first=f.api.ensure();
  f.org('another');
  const second=f.api.ensure();
  f.requests[1].resolve(response('Another'));
  await second;
  f.requests[0].resolve(response('Old'));
  await assert.rejects(first,/已变更/);
  assert.equal(f.api.get('SHOPEE').name,'Another');
});
