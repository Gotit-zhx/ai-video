const {test}=require('node:test');
const assert=require('node:assert/strict');
const vm=require('node:vm');
const fs=require('node:fs');
const path=require('node:path');
function harness(){
  const nodes=new Map(),selected=[];let org='org-a',resolve,reject;
  const get=id=>{if(!nodes.has(id))nodes.set(id,{value:'',textContent:'',hidden:false,disabled:false,removeAttribute(name){delete this[name];},addEventListener(name,fn){this[name]=fn;}});return nodes.get(id);};
  const ctx={module:{exports:{}},document:{getElementById:get},FormData:class{append(){}},AbortSignal};
  vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../product-bulk-size-chart.js'),'utf8'),ctx);
  const api=ctx.module.exports;
  api.init({getOrganization:()=>org,canPublish:()=>true,changed(){},selected:key=>selected.push(key),api:()=>new Promise((a,b)=>{resolve=a;reject=b;})});
  api.open(org,'batch-a');api.render();
  return {api,get,selected,org(value){org=value;},resolve(value){resolve({data:{storageKey:value}});},reject(){reject(Error('network error'));}};
}
const file={type:'image/png',size:1234},key='a'.repeat(64)+'/'+'b'.repeat(64);
test('optional chart is empty by default and clearing restores original source',async()=>{
  const h=harness();assert.equal(h.api.key(),'');const task=h.api.upload(file);assert.equal(h.api.isUploading(),true);
  h.resolve(key);await task;assert.equal(h.api.key(),key);assert.deepEqual(h.selected,[key]);
  h.get('product-bulk-size-chart-remove').click();assert.equal(h.api.key(),'');assert.deepEqual(h.selected,[key,'']);
});
test('late upload cannot leak across organizations or replace a new batch',async()=>{
  for(const changed of ['organization','batch','close']){
    const h=harness(),pending=h.api.upload(file);
    if(changed==='organization'){h.org('org-b');h.api.open('org-b','batch-b');}
    if(changed==='batch')h.api.open('org-a','batch-b');
    if(changed==='close')h.api.close();
    h.resolve(key);await pending;assert.equal(h.api.key(),'');assert.deepEqual(h.selected,[]);
  }
});
test('failed replacement keeps the selected chart and restores submission',async()=>{
  const h=harness();h.api.open('org-a','batch-a',[{payload:{sizeChartImageKey:key}}]);h.api.render();
  const task=h.api.upload(file);h.reject();await task;assert.equal(h.api.key(),key);assert.equal(h.api.isUploading(),false);
  assert.match(h.get('product-bulk-size-chart-error').textContent,/network/);
});
test('invalid files and other platforms never upload',async()=>{
  const h=harness();await h.api.upload({...file,type:'image/svg+xml'});assert.equal(h.api.isUploading(),false);
  h.api.render({platform:'TIKTOK_SHOP',busy:false,editable:true});await h.api.upload(file);assert.equal(h.api.isUploading(),false);
});
