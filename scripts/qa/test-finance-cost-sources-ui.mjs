import fs from 'node:fs/promises';
import assert from 'node:assert/strict';
const {JSDOM}=await import(process.env.JSDOM_MODULE||'jsdom');
const dom=new JSDOM('<main id="root" class="finance-workspace"></main>',{url:'https://example.test/',runScripts:'outside-only',pretendToBeVisual:true}),w=dom.window,d=w.document;
w.HTMLDialogElement.prototype.showModal=function(){this.open=true;};w.HTMLDialogElement.prototype.close=function(){this.open=false;this.dispatchEvent(new w.Event('close'));};
const groups=[{id:'g',name:'母婴组'},{id:'empty',name:'待运营组'}],shops=[{id:'shop',name:'测试店铺',group_id:'g',region:'PH',platform:'SHOPEE'}],writes=[];
let data={live:true,groupId:'g',groupName:'母婴组',shopId:'shop',sourceName:'2 份成本表',settings:{warehouse:'海外仓-菲律宾'},settingsVersion:1,
  imports:[{id:'file-a',source_name:'基础成本.xlsx',row_count:1},{id:'file-b',source_name:'十月补充.xlsx',row_count:1}],
  costs:[{sku:'EXCEL',warehouse:'海外仓-菲律宾',prices:{'2026-10':10},priceSources:{'2026-10':{id:'file-b',name:'十月补充.xlsx'}}}],manualCosts:[],mappings:{},
  products:[{shop_id:'shop',product_id:'p',sku_id:'v',seller_sku:'',name:'原订单商品',spec:'50片（1包）',image:'https://example.test/product.png'}]};
const context={shops,groups,canManage:true,request:async(path,options={})=>{
  const url=new URL(path,'https://example.test'),body=options.body instanceof w.FormData?options.body:options.body?JSON.parse(options.body):null;
  if(options.method){writes.push({path:url.pathname,method:options.method,body});
    if(path.endsWith('/manual-mapping')){
      const item={id:'manual-one',manualId:'manual-one',sku:body.code,warehouse:body.warehouse,unit:body.unit,unitPriceCny:body.unitPriceCny,version:1};
      data.manualCosts.push(item);data.mappings['shop:p:v:'+body.warehouse]={...body,manualCostId:item.id,groupId:'g',version:1};return {id:item.id};
    }
    if(path.includes('/files/')){data.imports=data.imports.filter(f=>!path.endsWith('/'+f.id));return {};}
    if(path.includes('/manual/')){
      if(options.method==='DELETE')data.manualCosts=[];else Object.assign(data.manualCosts[0],body,{version:2});return {};
    }
    if(path==='/costs/import'){assert(body instanceof w.FormData);assert.equal(body.get('groupId'),'g');return {rows:1};}
    throw Error('Unexpected mutation '+path);
  }
  if(url.pathname==='/costs'){
    if(url.searchParams.get('groupId')==='empty')return {live:true,shopId:'',groupId:'empty',costs:[],manualCosts:[],imports:[],products:[],mappings:{}};
    return structuredClone(data);
  }
  throw Error('Unexpected read '+path);
}};
w.eval(await fs.readFile(new URL('../../frontend/finance-costs.js',import.meta.url),'utf8'));
const settle=()=>new Promise(r=>setTimeout(r,25));
async function click(text,root=d){const n=[...root.querySelectorAll('button')].find(n=>n.textContent.trim()===text||n.textContent.trim().endsWith(text));assert(n,text);n.click();await settle();}
function field(label,value,root=d){const n=root.querySelector(`[aria-label="${label}"]`);assert(n,label);n.value=value;n.dispatchEvent(new w.Event('change'));return n;}
function consent(root,text){const label=[...root.querySelectorAll('label')].find(n=>n.textContent.includes(text));assert(label,text);label.querySelector('input').checked=true;}
w.FinanceCosts.mount(d.querySelector('#root'),{...context,preview:structuredClone(data)});
await click('商品成本表');
assert.equal(d.querySelector('[aria-label="成本分组"]').value,'g');assert.equal(d.querySelectorAll('.fc-file').length,2);
assert(d.querySelector('tbody').textContent.includes('十月补充.xlsx'),'price source is visible');
await click('手动管理');assert(d.body.textContent.includes('尚无手动成本'));
await click('去 SKU 关联添加');
const expanded=d.querySelector('.fc-spu-parent').getAttribute('aria-expanded');
d.querySelector('.fc-spu-image').click();await settle();
assert.equal(d.querySelector('.fc-image-view img').src,'https://example.test/product.png');
d.querySelector('.fc-image-view').close();
assert.equal(d.querySelector('.fc-spu-parent').getAttribute('aria-expanded'),expanded,'zoom does not collapse the product');
await click('核对关联');
let dialog=d.querySelector('dialog');field('关联方式','manual',dialog);
field('手动产品型号','CUSTOM-PACK',dialog);field('人民币单价','2.75',dialog);field('成本计价单位','包',dialog);field('成本单位换算数量','1.5',dialog);
dialog.querySelector('.fc-mapping-image').click();await settle();assert.equal(d.querySelectorAll('dialog').length,2);
d.querySelector('.fc-image-view').close();assert.equal(dialog.querySelector('[aria-label="人民币单价"]').value,'2.75','closing nested zoom preserves the manual form');
consent(dialog,'我已核对');await click('保存关联',dialog);
const save=writes.find(x=>x.path.endsWith('/manual-mapping'));assert.equal(save.method,'POST');assert.equal(save.body.factor,'1.5');assert.equal(save.body.unitPriceCny,'2.75');assert.equal(save.body.warehouse,'海外仓-菲律宾');
await click('手动管理');assert(d.querySelector('tbody').textContent.includes('CUSTOM-PACK'));assert(d.querySelector('tbody').textContent.includes('¥ 2.75'));
if(process.env.FINANCE_SOURCES_PREVIEW_DIR){
  await fs.mkdir(process.env.FINANCE_SOURCES_PREVIEW_DIR,{recursive:true});
  const css=(await Promise.all(['finance-workspace.css','finance-costs.css'].map(f=>fs.readFile(new URL('../../frontend/'+f,import.meta.url),'utf8')))).join('\n');
  const html=title=>`<!doctype html><meta charset="utf-8"><title>${title}</title><style>${css}body{margin:0;padding:24px;background:#f5f7fc;color:#25334d;font:14px/1.5 -apple-system,BlinkMacSystemFont,"PingFang SC",sans-serif}</style>${d.body.innerHTML}`;
  await fs.writeFile(process.env.FINANCE_SOURCES_PREVIEW_DIR+'/manual.html',html('手动成本 · 测试数据'));
  await click('成本表');await fs.writeFile(process.env.FINANCE_SOURCES_PREVIEW_DIR+'/files.html',html('分组成本文件 · 测试数据'));
  await click('SKU 关联');await click('查看 / 调整');dialog=d.querySelector('dialog');field('关联方式','manual',dialog);field('手动产品型号','CUSTOM-NEW',dialog);
  await fs.writeFile(process.env.FINANCE_SOURCES_PREVIEW_DIR+'/mapping.html',html('手动关联 · 测试数据'));dialog.close();
}
await click('成本表');await click('移除文件',d.querySelector('[data-source-id="file-b"]'));await click('确认移除文件',d.querySelector('dialog'));
assert.equal(writes.at(-1).path,'/costs/groups/g/files/file-b');assert.equal(d.querySelectorAll('.fc-file').length,1);
await click('手动管理');assert(d.querySelector('tbody').textContent.includes('CUSTOM-PACK'),'Excel removal leaves manual table intact');
await click('成本表');await click('导入成本表');dialog=d.querySelector('dialog');
const input=dialog.querySelector('input[type=file]');assert(input.multiple);
Object.defineProperty(input,'files',{value:[new w.File(['a'],'a.xlsx'),new w.File(['b'],'b.xlsx')]});
await click('确认导入',dialog);assert.equal(writes.filter(x=>x.path==='/costs/import').length,2);
await click('手动管理');assert(d.querySelector('tbody').textContent.includes('CUSTOM-PACK'),'multi-file import leaves manual table intact');
await click('修改',d.querySelector('tbody'));dialog=d.querySelector('dialog');field('人民币单价','3.125',dialog);consent(dialog,'确认按新单价');await click('保存手动成本',dialog);
assert.equal(writes.at(-1).body.version,1);assert(d.querySelector('tbody').textContent.includes('3.125'));
await click('删除',d.querySelector('tbody'));dialog=d.querySelector('dialog');assert(dialog.textContent.includes('回到待补齐'));await click('确认删除手动成本',dialog);
assert.equal(writes.at(-1).path,'/costs/groups/g/manual/manual-one');assert.equal(writes.at(-1).body.version,2);assert(d.body.textContent.includes('尚无手动成本'));
await click('SKU 关联');assert(d.body.textContent.includes('成本已移除'),'deleted manual association is no longer shown as linked');
field('成本分组','empty');await settle();await click('成本表');
assert.equal(d.querySelector('[aria-label="成本分组"]').value,'empty');assert.equal(d.querySelector('[aria-label="财务店铺"]').options.length,0);
assert(!d.body.textContent.includes('基础成本.xlsx'),'costs do not leak into another group without shops');
assert(![...d.querySelectorAll('button')].find(n=>n.textContent==='导入成本表').disabled,'groups without shops can still maintain cost files');
w.FinanceCosts.reset();w.FinanceCosts.mount(d.querySelector('#root'),{...context,canManage:false,preview:structuredClone(data)});
assert(![...d.querySelectorAll('button')].some(n=>n.textContent==='移除文件'||n.textContent==='导入成本表'));
await click('手动管理');assert(![...d.querySelectorAll('button')].some(n=>n.textContent==='去 SKU 关联添加'));
dom.window.close();console.log('Group cost sources UI: multi-file upload/removal, independent CNY manual mapping/edit/delete, image zoom without losing form state, group isolation and read-only access passed.');
