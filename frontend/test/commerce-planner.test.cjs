const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../commerce-planner.js'), 'utf8');

function setup(api) {
  const handlers = {};
  function element() {
    return { textContent: '', children: [], append(e) { this.children.push(e); },
      replaceChildren() { this.children = []; this.textContent = ''; } };
  }
  const result = element();
  const button = { disabled: false };
  const form = { addEventListener(name, handler) { handlers[name] = handler; },
    elements: { namedItem: () => ({ checked: false }) }, querySelector: () => button };
  const values = { mode: 'POD_DESIGN', platform: 'TIKTOK_SHOP', market: 'PH', sourceProducts: '30',
    designsPerProduct: '10', imagesPerDesign: '1', shopCount: '6', dailyListingsPerShop: '50',
    dailyBudgetPerShop: '1000', observationDays: '7', targetRoi: '6.5' };
  vm.runInNewContext(source, { document: { getElementById: id => id === 'commerce-plan-form' ? form : id === 'commerce-plan-result' ? result : null,
    createElement: element }, FormData: function() { return Object.entries(values); }, api, Intl });
  return { handlers, result, button };
}
const plan = { designVersions: 300, materialImages: 300, listingDrafts: 300, minimumPublishingDays: 1,
  currency: 'PHP', dailyBudgetAllShops: 6000, observationBudgetCeiling: 42000, stages: ['采集', '审核'],
  schedule: [{ day: 1, listingsPerShop: [50,50,50,50,50,50] }], blockers: ['<img src=x onerror=alert(1)>'], warnings: ['仅预览'] };

test('only calls preview API and renders returned strings as text', async () => {
  const calls = [];
  const s = setup(async (url, options) => { calls.push({ url, options }); return { data: plan }; });
  await s.handlers.submit({ preventDefault() {} });
  assert.equal(calls.length, 1);
  assert.equal(calls[0].url, '/api/v1/commerce-plans/preview');
  const sent = JSON.parse(calls[0].options.body);
  assert.equal(sent.sourceProducts, 30); assert.equal(sent.rightsConfirmed, false);
  assert.equal(s.button.disabled, false);
  assert.ok(JSON.stringify(s.result).includes('尚未创建任务'));
  assert.ok(JSON.stringify(s.result).includes('<img src=x onerror=alert(1)>'));
  assert.ok(!source.includes('innerHTML'));
});
test('editing invalidates an in-flight preview', async () => {
  let finish;
  const s = setup(() => new Promise(resolve => { finish = resolve; }));
  const pending = s.handlers.submit({ preventDefault() {} });
  s.handlers.input();
  finish({ data: plan }); await pending;
  assert.match(s.result.textContent, /配置已变更/);
  assert.equal(s.result.children.length, 0);
  assert.equal(s.button.disabled, false);
});
test('permission failures remain errors, never success previews', async () => {
  const s = setup(async () => { throw new Error('没有权限'); });
  await s.handlers.submit({ preventDefault() {} });
  assert.equal(s.result.textContent, '没有权限');
  assert.equal(s.button.disabled, false);
});

function workflowSetup(api,{print=null,route='',getter=true,editor=null}={}) {
  class Element {
    constructor(tag='div'){this.tagName=tag.toUpperCase();this.textContent='';this.children=[];this.handlers={};this.dataset={};this.value='';this.checked=false;this.disabled=false;this.type='';}
    append(...children){this.children.push(...children);}
    replaceChildren(...children){this.children=[...children];this.textContent='';}
    addEventListener(name,handler){this.handlers[name]=handler;}
    dispatchEvent(event){return this.handlers[event.type]?.(event);}
    get options(){return this.children;}
  }
  const values={mode:'POD_DESIGN',platform:'TIKTOK_SHOP',market:'PH',sourceProducts:'30',designsPerProduct:'10',imagesPerDesign:'1',shopCount:'6',dailyListingsPerShop:'50',dailyBudgetPerShop:'0',observationDays:'7',targetRoi:'6.5'};
  const fields=Object.fromEntries(Object.entries(values).map(([key,value])=>[key,Object.assign(new Element('input'),{value})]));
  for(const key of ['rightsConfirmed','supplyConfirmed','productionFilesConfirmed'])fields[key]=Object.assign(new Element('input'),{type:'checkbox'});
  const ids=['commerce-plan-form','commerce-plan-result','commerce-template-select','commerce-template-name','commerce-template-status','commerce-template-refresh','commerce-template-save','commerce-template-new','commerce-template-save-state'];
  const elements=Object.fromEntries(ids.map(id=>[id,new Element()]));
  const form=elements['commerce-plan-form'];form.elements={namedItem:key=>fields[key]};form.reportValidity=()=>true;form.reset=()=>{};form.querySelector=()=>new Element('button');
  elements['commerce-template-name'].value='印花测试';
  const events={},emitted=[];
  const context={document:{getElementById:id=>elements[id]||null,createElement:tag=>new Element(tag)},FormData:function(){return Object.entries(fields).filter(([,v])=>v.type!=='checkbox'&&!v.disabled).map(([k,v])=>[k,v.value]);},CustomEvent:class{constructor(type,{detail}){this.type=type;this.detail=detail;}},api,Intl,location:{hash:route},addEventListener:(name,fn)=>events[name]=fn,dispatchEvent:event=>emitted.push(event)};
  if(getter)context.CommercePrintTemplateSelection=()=>print;
  if(editor)context.CommercePrintTemplateEditor=editor;
  vm.runInNewContext(source,context);
  return {elements,fields,events,emitted,load:()=>elements['commerce-template-refresh'].handlers.click(),choose(id){elements['commerce-template-select'].value=id;elements['commerce-template-select'].handlers.change();},save:()=>elements['commerce-template-save'].handlers.click()};
}
const workflow=(configuration={})=>({templateId:'workflow',snapshotId:'workflow-snapshot',name:'已保存模板',version:3,configuration:{designsPerProduct:4,shopCount:3,shopIds:['old-shop'],shopTargets:[{shopId:'old-shop',market:'MY'}],...configuration}});
test('workflow templates save no shop bindings and do not request the shop list',async()=>{
  const calls=[];let saved;const s=workflowSetup(async(url,options)=>{calls.push(url);saved=JSON.parse(options.body);return {data:{...saved,templateId:'new',version:1}};});
  await s.save();assert.deepEqual(calls,['/api/v1/commerce-templates']);
  assert.deepEqual(saved.configuration.shopIds,[]);assert.deepEqual(saved.configuration.shopTargets,[]);assert.equal(saved.configuration.shopCount,1);assert.equal(saved.configuration.printTemplateSnapshotId,null);
});
test('workflow save links the currently saved print snapshot without saving the print editor itself',async()=>{
  let saved;const s=workflowSetup(async(url,options)=>{assert.equal(url,'/api/v1/commerce-templates');saved=JSON.parse(options.body);return {data:{...saved,templateId:'new',version:1}};},{print:{snapshotId:'print-snapshot',dirty:false,name:'衣服印花'}});
  await s.save();assert.equal(saved.configuration.printTemplateSnapshotId,'print-snapshot');assert.equal(s.elements['commerce-template-select'].value,'new');
});
test('a dirty saved print snapshot blocks outer save before any request',async()=>{
  let requests=0;const s=workflowSetup(async()=>{requests++;},{print:{snapshotId:'print-snapshot',dirty:true}});
  await s.save();assert.equal(requests,0);assert.match(s.elements['commerce-template-status'].textContent,/请点击右上角“保存模板”/);assert.equal(s.elements['commerce-template-save'].disabled,false);
});
test('loading an existing workflow restores design count and dispatches its linked print snapshot',async()=>{
  const s=workflowSetup(async()=>({data:[workflow({printTemplateSnapshotId:'print-old'})]}));await s.load();s.choose('workflow');
  assert.equal(s.fields.designsPerProduct.value,4);assert.equal(s.fields.shopCount.value,'1');assert.equal(s.elements['commerce-template-name'].value,'已保存模板');
  const event=s.emitted.at(-1);assert.equal(event.type,'heyi:workflow-print-template-selected');assert.equal(event.detail.snapshotId,'print-old');
});
test('saving an old workflow clears historic shop bindings while preserving a linked snapshot without an editor getter',async()=>{
  let saved;const s=workflowSetup(async(url,options)=>{if(!options)return {data:[workflow({printTemplateSnapshotId:'print-old'})]};saved=JSON.parse(options.body);return {data:{...saved,version:4}};},{getter:false});
  await s.load();s.choose('workflow');await s.save();assert.equal(saved.templateId,'workflow');assert.equal(saved.expectedVersion,3);
  assert.deepEqual(saved.configuration.shopIds,[]);assert.deepEqual(saved.configuration.shopTargets,[]);assert.equal(saved.configuration.shopCount,1);assert.equal(saved.configuration.printTemplateSnapshotId,'print-old');
});
test('loading an unlinked legacy workflow explicitly clears any unrelated print selection',async()=>{
  const s=workflowSetup(async()=>({data:[workflow()]}));await s.load();s.choose('workflow');assert.equal(s.emitted.at(-1).detail.snapshotId,null);
});
test('a late workflow list cannot repopulate a new company or keep its controls disabled',async()=>{
  let finish;const s=workflowSetup(()=>new Promise(resolve=>finish=resolve));const pending=s.load();s.events['heyi:company-reset']();finish({data:[workflow()]});await pending;
  assert.equal(s.elements['commerce-template-select'].children.length,0);assert.equal(s.elements['commerce-template-status'].textContent,'');assert.equal(s.elements['commerce-template-save'].disabled,false);
});
test('a stale workflow save response after company reset cannot select or announce the old template',async()=>{
  let finish;const s=workflowSetup(()=>new Promise(resolve=>finish=resolve));const pending=s.save();s.events['heyi:company-reset']();finish({data:workflow()});await pending;
  assert.equal(s.elements['commerce-template-select'].children.length,0);assert.equal(s.elements['commerce-template-status'].textContent,'');assert.equal(s.elements['commerce-template-name'].value,'');
});

test('unified save archives a new print draft first and links its returned snapshot with the frozen design count',async()=>{
  let finishPrint,body;const calls=[];
  const print={snapshotId:null,dirty:true,name:'未保存版型'};
  const editor={getState:()=>({...print,hasContent:true,busy:false}),save:async({name})=>{
    calls.push('print');assert.equal(name,'印花测试');
    await new Promise(resolve=>finishPrint=resolve);
    Object.assign(print,{snapshotId:'first-print-snapshot',dirty:false,name});
    return {...print};
  }};
  const s=workflowSetup(async(url,options)=>{
    calls.push('workflow');assert.equal(url,'/api/v1/commerce-templates');body=JSON.parse(options.body);
    return {data:{...body,templateId:'first-workflow',version:1}};
  },{print,editor});
  s.fields.designsPerProduct.value='7';
  const pending=s.save();
  assert.deepEqual(calls,['print']);
  assert.equal(s.elements['commerce-template-name'].disabled,true);assert.equal(s.fields.designsPerProduct.disabled,true);
  assert.equal(s.elements['commerce-template-save-state'].dataset.state,'busy');
  await s.save();assert.deepEqual(calls,['print'],'a second save click must not archive another print draft');
  finishPrint();await pending;
  assert.deepEqual(calls,['print','workflow']);assert.equal(body.configuration.printTemplateSnapshotId,'first-print-snapshot');
  assert.equal(body.configuration.designsPerProduct,7,'disabled controls are excluded from FormData but the frozen count must survive');
  assert.equal(body.name,'印花测试');assert.deepEqual(body.configuration.shopIds,[]);
  assert.equal(s.elements['commerce-template-name'].disabled,false);assert.equal(s.fields.designsPerProduct.disabled,false);
  assert.equal(s.elements['commerce-template-save-state'].dataset.state,'saved');
});

test('an inner print save failure preserves the draft and never creates an unlinked workflow',async()=>{
  let requests=0,innerSaves=0;const print={snapshotId:null,dirty:true};
  const editor={getState:()=>({...print,hasContent:true,busy:false}),save:async()=>{innerSaves++;throw new Error('四面效果归档失败');}};
  const s=workflowSetup(async()=>{requests++;},{print,editor});await s.save();
  assert.equal(innerSaves,1);assert.equal(requests,0);assert.equal(print.snapshotId,null);assert.equal(print.dirty,true);
  assert.equal(s.elements['commerce-template-status'].textContent,'四面效果归档失败');
  assert.equal(s.elements['commerce-template-save'].disabled,false);assert.equal(s.fields.designsPerProduct.disabled,false);
  assert.equal(s.elements['commerce-template-save-state'].dataset.state,'dirty');
});

test('retrying a failed outer save reuses the already archived clean print snapshot',async()=>{
  let archives=0,innerSaves=0;const submitted=[];const print={snapshotId:'previous-print',dirty:false,name:'已保存模板'};
  const editor={getState:()=>({...print,hasContent:true,busy:false}),save:async({name})=>{
    innerSaves++;
    if(print.dirty||!print.snapshotId){archives++;Object.assign(print,{snapshotId:'archived-once',dirty:false,name});}
    return {...print};
  }};
  const s=workflowSetup(async(url,options)=>{
    if(!options)return {data:[workflow({printTemplateSnapshotId:'previous-print'})]};
    const body=JSON.parse(options.body);submitted.push(body);
    if(submitted.length===1)throw new Error('模板配置暂时无法保存');
    return {data:{...body,version:4}};
  },{print,editor});
  await s.load();s.choose('workflow');assert.equal(s.elements['commerce-template-save-state'].dataset.state,'saved');
  print.dirty=true;
  await s.save();assert.match(s.elements['commerce-template-status'].textContent,/暂时无法保存/);
  assert.equal(print.snapshotId,'archived-once');assert.equal(print.dirty,false);
  assert.equal(s.elements['commerce-template-save-state'].dataset.state,'dirty','a clean inner snapshot does not mean the failed outer template has been saved');
  assert.equal(s.elements['commerce-template-save-state'].textContent,'有未保存修改');
  await s.save();
  assert.equal(innerSaves,2);assert.equal(archives,1,'the editor can return its clean archived snapshot without adding another version');
  assert.equal(submitted.length,2);assert.deepEqual(submitted[1],submitted[0]);
  assert.equal(submitted[1].configuration.printTemplateSnapshotId,'archived-once');
  assert.equal(s.elements['commerce-template-select'].value,'workflow');assert.equal(s.elements['commerce-template-save-state'].dataset.state,'saved');
});

test('an empty unified editor cannot save an unlinked workflow',async()=>{
  let innerSaves=0,requests=0;
  const editor={getState:()=>({hasContent:false,snapshotId:null,dirty:false,busy:false}),save:async()=>{innerSaves++;}};
  const s=workflowSetup(async()=>{requests++;},{editor});await s.save();
  assert.equal(innerSaves,0);assert.equal(requests,0);assert.match(s.elements['commerce-template-status'].textContent,/先上传商品图片/);
  assert.equal(s.elements['commerce-template-name'].disabled,false);assert.equal(s.fields.designsPerProduct.disabled,false);
});

test('a busy editor cannot be saved or start an outer workflow request',async()=>{
  let requests=0,innerSaves=0;
  const editor={getState:()=>({hasContent:true,snapshotId:'previous',dirty:true,busy:true}),save:async()=>{innerSaves++;}};
  const s=workflowSetup(async()=>{requests++;},{editor});await s.save();
  assert.equal(innerSaves,0);assert.equal(requests,0);assert.match(s.elements['commerce-template-status'].textContent,/仍在处理中/);
  assert.equal(s.elements['commerce-template-save-state'].dataset.state,'busy');
  assert.equal(s.elements['commerce-template-name'].disabled,false);assert.equal(s.fields.designsPerProduct.disabled,false);
});

test('company reset unlocks header fields while an inner save is pending and prevents its outer continuation',async()=>{
  let finishPrint,requests=0;const print={snapshotId:null,dirty:true};
  const editor={getState:()=>({...print,hasContent:true,busy:false}),save:()=>new Promise(resolve=>finishPrint=resolve)};
  const s=workflowSetup(async()=>{requests++;},{print,editor});const pending=s.save();
  assert.equal(s.elements['commerce-template-name'].disabled,true);assert.equal(s.fields.designsPerProduct.disabled,true);
  s.events['heyi:company-reset']();
  for(const id of ['commerce-template-name','commerce-template-save','commerce-template-select','commerce-template-refresh','commerce-template-new'])assert.equal(s.elements[id].disabled,false,id);
  assert.equal(s.fields.designsPerProduct.disabled,false);assert.equal(s.elements['commerce-template-save-state'].dataset.state,'empty');
  finishPrint({snapshotId:'old-company-print'});await pending;
  assert.equal(requests,0);assert.equal(s.elements['commerce-template-name'].value,'');
  assert.equal(s.elements['commerce-template-status'].textContent,'');assert.equal(s.elements['commerce-template-select'].children.length,0);
  assert.equal(s.fields.designsPerProduct.disabled,false);
});

test('company reset unlocks header fields while an outer save is pending and ignores the old response',async()=>{
  let finishOuter;const print={snapshotId:'saved-print',dirty:false};
  const editor={getState:()=>({...print,hasContent:true,busy:false}),save:async()=>({...print})};
  const s=workflowSetup(()=>new Promise(resolve=>finishOuter=resolve),{print,editor});const pending=s.save();
  await new Promise(resolve=>setImmediate(resolve));assert.equal(typeof finishOuter,'function');
  assert.equal(s.elements['commerce-template-name'].disabled,true);assert.equal(s.fields.designsPerProduct.disabled,true);
  s.events['heyi:company-reset']();finishOuter({data:workflow({printTemplateSnapshotId:'old-company-print'})});await pending;
  assert.equal(s.elements['commerce-template-name'].disabled,false);assert.equal(s.fields.designsPerProduct.disabled,false);
  assert.equal(s.elements['commerce-template-name'].value,'');assert.equal(s.elements['commerce-template-status'].textContent,'');
  assert.equal(s.elements['commerce-template-select'].children.length,0);assert.equal(s.elements['commerce-template-save-state'].dataset.state,'empty');
});

test('mixed-currency preview displays separate groups and never renders missing budgets as zero',async()=>{
  const s=setup(async()=>({data:{...plan,currency:null,dailyBudgetAllShops:null,observationBudgetCeiling:null,budgetGroups:[{currency:'MYR',shopCount:1,dailyBudgetAllShops:100,observationBudgetCeiling:700,budgetConfigured:true},{currency:'THB',shopCount:1,dailyBudgetAllShops:null,observationBudgetCeiling:null,budgetConfigured:false}]}}));
  await s.handlers.submit({preventDefault(){}});
  const rendered=JSON.stringify(s.result);
  assert.match(rendered,/MYR · 1 家店铺：每日预算上限/);
  assert.match(rendered,/THB · 1 家店铺：广告预算未配置/);
  assert.doesNotMatch(rendered,/全店每日预算上限/);
});
