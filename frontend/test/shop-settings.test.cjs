const test=require('node:test'),assert=require('node:assert/strict');
const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');

function setup({status='ACTIVE',platform='TIKTOK_SHOP',region='VN',current=null}={}){
  const nodes={},calls=[];
  const element=()=>({value:'',children:[],handlers:{},disabled:false,textContent:'',
    append(child){this.children.push(child);if(this.id==='shop'&&this.children.length===1)this.value=child.value;},
    replaceChildren(){this.children=[];this.value='';},addEventListener(type,handler){this.handlers[type]=handler;}});
  for(const id of ['form','shop','status','refresh','template','save','groupName','environment','dailyListingLimit']){nodes[id]=element();nodes[id].id=id;}
  nodes.form.querySelector=()=>nodes.save;nodes.form.elements={namedItem:id=>nodes[id]};
  const templates=[
    {snapshotId:'mixed',name:'混合店铺',version:1,configuration:{platform:'SHOPEE',market:'PH',shopIds:['shop','another']}},
    {snapshotId:'not-selected',name:'其他店铺',version:1,configuration:{platform:'TIKTOK_SHOP',market:'VN',shopIds:['another']}},
    {snapshotId:'legacy',name:'旧模板',version:1,configuration:{platform:'SHOPEE',market:'MY'}}
  ];
  vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../shop-settings.js'),'utf8'),{
    document:{getElementById:id=>nodes[id.replace('shop-settings-','')],createElement:element},
    encodeURIComponent,hasAnyPermission:()=>true,
    api:async url=>{calls.push(url);return {data:url==='/api/v1/shops'
      ?[{id:'shop',name:'越南店',status,platform,region}]
      :url==='/api/v1/commerce-templates'?templates
      :{version:2,groupName:'A',environment:'TEST',dailyListingLimit:30,defaultTemplateSnapshotId:current}};}
  });
  return {nodes,calls,load:()=>nodes.refresh.handlers.click()};
}

test('default templates follow selected shop membership across platform and market',async()=>{
  const s=setup();await s.load();
  assert.deepEqual(s.nodes.template.children.map(o=>o.value),['','mixed','legacy']);
  assert.equal(s.nodes.save.disabled,false);
  assert.ok(s.calls.every(url=>!url.includes('publish')));
});
test('inactive and unsupported shops do not offer a new default template',async()=>{
  for(const config of [{status:'EXPIRED'},{platform:'AMAZON'},{region:'US'}]){
    const s=setup(config);await s.load();
    assert.deepEqual(s.nodes.template.children.map(o=>o.value),['']);
  }
});
test('saved historical default remains visible if it is no longer selectable',async()=>{
  const s=setup({current:'not-selected'});await s.load();
  assert.equal(s.nodes.template.value,'not-selected');
  assert.equal(s.nodes.template.children.at(-1).textContent,'当前固定的历史模板版本');
});
