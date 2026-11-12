import fs from 'node:fs/promises';
import assert from 'node:assert/strict';
const {JSDOM}=await import(process.env.JSDOM_MODULE||'jsdom');
const dom=new JSDOM('<main id="root"></main>',{url:'https://example.test/',runScripts:'outside-only',pretendToBeVisual:true});
const w=dom.window,d=w.document;
w.HTMLDialogElement.prototype.showModal=function(){this.open=true;};w.HTMLDialogElement.prototype.close=function(){this.open=false;this.dispatchEvent(new w.Event('close'));};
const writes=[],groups=[{id:'g',name:'测试分组'}],shops=[{id:'s',name:'测试店铺',group_id:'g',region:'PH',platform:'SHOPEE'}];
const preview={live:true,inputSafety:{enabled:true,pendingCount:0},groupId:'g',shopId:'s',sourceName:'基础.csv',settings:{warehouse:'菲律宾',rules:{unit:'包',basis:'order',missing:'previous'}},settingsVersion:1,
 imports:[{id:'source',source_name:'基础.csv',row_count:1}],costs:[{sku:'PACK',warehouse:'菲律宾',prices:{'2026-11':2}}],
 products:[{shop_id:'s',product_id:'p',sku_id:'v',seller_sku:'PACK',name:'测试商品',spec:'1 包'}],manualCosts:[{id:'manual',sku:'CUSTOM',warehouse:'菲律宾',unit:'包',unitPriceCny:3,version:1}],
 mappings:{'s:p:v:菲律宾':{code:'PACK',warehouse:'菲律宾',factor:1,method:'EXACT_CODE',version:1,groupId:'g'}}};
const options={preview,groups,shops,canManage:true,request:async(path,request={})=>{
 const url=new URL(path,'https://example.test');
 if(request.method){const body=request.body instanceof w.FormData?request.body:JSON.parse(request.body||'{}');writes.push({path:url.pathname,method:request.method,body});return {staged:true,changeId:'pending-'+writes.length,status:'READY',rows:1};}
 if(url.pathname==='/costs')return structuredClone(preview);
 if(url.pathname==='/costs/input-changes')return {items:[],total:0,page:1,pageSize:20};
 throw Error('Unexpected read '+path);
}};
for(const name of ['finance-cost-input-safety.js','finance-costs.js'])w.eval(await fs.readFile(new URL('../../frontend/'+name,import.meta.url),'utf8'));
const settle=()=>new Promise(r=>setTimeout(r,20));
async function click(text,root=d){const n=[...root.querySelectorAll('button')].find(b=>b.textContent.trim()===text||b.textContent.trim().endsWith(text));assert(n,'button '+text);n.click();await settle();}
const field=(name,value,root=d)=>{const n=root.querySelector(`[aria-label="${name}"]`);assert(n,name);n.value=value;n.dispatchEvent(new w.Event('change'));};
const consent=(text,root=d)=>{const label=[...root.querySelectorAll('label')].find(n=>n.textContent.includes(text));assert(label,text);label.querySelector('input').checked=true;};
const evidence=root=>{field('操作原因','已核对人工资料的实际范围',root);field('依据编号或说明','采购核价单-11',root);};
w.FinanceCosts.mount(d.querySelector('#root'),options);await click('商品成本表');
assert([...d.querySelectorAll('[role="tab"]')].some(n=>n.textContent.includes('导入与操作核对')),'正式成本页必须可进入人工核对记录');
await click('导入成本表');let dialog=d.querySelector('dialog');field('成本计价单位','包',dialog);field('编号类型','SKU',dialog);evidence(dialog);
const file=dialog.querySelector('input[type=file]');Object.defineProperty(file,'files',{value:[new w.File(['型号,仓库,月份,单价\nPACK,菲律宾,2026-11,2'], 'cost.csv')]});
await click('确认导入',dialog);const imported=writes.at(-1);assert.equal(imported.path,'/costs/import');const metadata=JSON.parse(imported.body.get('metadata'));assert.equal(metadata.unit,'包');assert.equal(metadata.identifierType,'SKU');assert.equal(metadata.reason,'已核对人工资料的实际范围');
assert(d.body.textContent.includes('已暂存'));assert(!d.body.textContent.includes('已导入 1 份成本表'),'暂存不得宣称有效成本已导入');
await click('成本表设置');evidence(d);await click('保存成本设置');assert(writes.at(-1).path.endsWith('/settings'));assert.equal(writes.at(-1).body.metadata.evidenceRef,'采购核价单-11');assert(d.body.textContent.includes('已暂存'));
await click('SKU 关联');await click('查看 / 调整');dialog=d.querySelector('dialog');evidence(dialog);consent('我已核对销售规格',dialog);await click('保存关联',dialog);assert(writes.at(-1).path.endsWith('/mapping'));assert.equal(writes.at(-1).body.metadata.reason,'已核对人工资料的实际范围');assert(d.body.textContent.includes('已暂存'));
await click('SKU 关联');await click('查看 / 调整');dialog=d.querySelector('dialog');field('关联方式','manual',dialog);field('手动产品型号','NEW-MANUAL',dialog);field('人民币单价','4',dialog);field('成本计价单位','包',dialog);evidence(dialog);consent('我已核对销售规格',dialog);await click('保存关联',dialog);assert(writes.at(-1).path.endsWith('/manual-mapping'));assert.equal(writes.at(-1).body.metadata.evidenceRef,'采购核价单-11');assert(d.body.textContent.includes('已暂存'));
await click('手动管理');await click('修改');dialog=d.querySelector('dialog');evidence(dialog);consent('确认按新单价',dialog);await click('保存手动成本',dialog);assert(writes.at(-1).path.endsWith('/manual/manual'));assert.equal(writes.at(-1).body.metadata.evidenceRef,'采购核价单-11');assert(d.body.textContent.includes('已暂存'));
await click('手动管理');await click('删除');dialog=d.querySelector('dialog');evidence(dialog);await click('确认删除手动成本',dialog);assert.equal(writes.at(-1).method,'DELETE');assert.equal(writes.at(-1).body.metadata.reason,'已核对人工资料的实际范围');assert(d.body.textContent.includes('CUSTOM'),'暂存删除仍保留正式手动成本');
await click('商品成本表');await click('移除文件');dialog=d.querySelector('dialog');evidence(dialog);await click('确认移除文件',dialog);assert.equal(writes.at(-1).method,'DELETE');assert.equal(writes.at(-1).body.metadata.reason,'已核对人工资料的实际范围');assert(d.body.textContent.includes('已暂存'));assert(d.body.textContent.includes('基础.csv'),'暂存删除仍保留正式来源');
await click('导入与操作核对');assert(d.body.textContent.includes('当前范围没有人工成本核对记录'));w.FinanceCosts.reset();dom.window.close();
console.log('Manual cost forms: staged upload/settings/mapping/manual edit/removal carry evidence and preserve active data passed.');
