import fs from 'node:fs/promises';
import assert from 'node:assert/strict';
const {JSDOM}=await import(process.env.JSDOM_MODULE||'jsdom');
const dom=new JSDOM('<main id="root"></main>',{url:'https://example.test/',runScripts:'outside-only',pretendToBeVisual:true});
const w=dom.window,d=w.document;
w.HTMLDialogElement.prototype.showModal=function(){this.open=true;};
w.HTMLDialogElement.prototype.close=function(){this.open=false;this.dispatchEvent(new w.Event('close'));};
const file=new URL('../../frontend/finance-cost-input-safety.js',import.meta.url);
w.eval(await fs.readFile(file,'utf8').catch(error=>{if(error.code==='ENOENT')return '';throw error;}));
assert.equal(typeof w.FinanceCostInputSafety?.mount,'function','人工成本输入必须具备独立核对入口');
const writes=[],reads=[],timers=[];let failRead=false,delayConfirm=false,finishConfirm,changeCallbacks=0;
const realTimeout=w.setTimeout.bind(w),realClear=w.clearTimeout.bind(w);
w.setTimeout=(fn,delay,...args)=>delay>=1000?(timers.push(()=>fn(...args)),10000+timers.length):realTimeout(fn,delay,...args);
w.clearTimeout=id=>{if(id<10000)realClear(id);};
const make=(id,extra={})=>({id,type:'IMPORT',status:'READY',sourceName:'十一月成本.xlsx',submittedBy:'u',version:1,createdAt:'2026-11-12T03:00:00Z',groupId:'g',shopId:'',canConfirm:true,canReject:true,canRevise:true,canUndo:false,requiresIndependentReview:false,
 metadata:{sourceKey:'采购成本来源-A',unit:'包',identifierType:'SKU',reason:'补充十一月价格',evidenceRef:'采购核价单-11'},
 validation:{issues:[{code:'SOURCE_UNVERIFIED',severity:'REVIEW',message:'需要核对来源',source:'人工上传成本表',action:'提供采购核价依据'}],changedCells:1,unchangedCells:0},
 diff:[{sku:'PACK',warehouse:'菲律宾',month:'2026-11',oldPrice:null,newPrice:2,kind:'ADD',oldSource:'此前已核价来源-A',newSource:'本次新核价来源-B'}],
 impact:{scopeShops:2,scopeOrderCount:80,hasHistorical:true,coverage:'SCOPE_UPPER_BOUND',amountChange:null},actualScope:'本分组相关店铺的历史及后续订单',...extra});
let items=[make('draft-a',{canRevise:false,metadata:{sourceKey:'采购成本来源-A',unit:'包',identifierType:'SPU',spuUniform:true,reason:'补充统一规格成本',evidenceRef:'核价凭据-11'}}),make('draft-risk',{status:'REVIEW',requiresIndependentReview:true,canConfirm:false,canRevise:false,sourceName:'冲突文件.xlsx'}),make('done',{status:'APPLIED',canConfirm:false,canRevise:false,canUndo:true})];
const options={groupId:'g',shopId:'',canManage:true,onChanged:async()=>{changeCallbacks++;},request:async(path,request={})=>{
 const url=new URL(path,'https://example.test'),method=request.method||'GET';
 if(method==='GET'){
  reads.push(path);if(failRead)throw Error('核对记录读取失败');
  if(url.pathname==='/costs/input-changes')return {items:structuredClone(items),total:items.length,page:1,pageSize:20};
  const id=url.pathname.split('/').at(-1);return structuredClone(items.find(v=>v.id===id));
 }
 const body=JSON.parse(request.body||'{}');writes.push({path:url.pathname,method,body});
 const id=url.pathname.split('/').at(-2),item=items.find(v=>v.id===id);
 if(url.pathname.endsWith('/confirm')){assert.equal(body.version,item.version);const apply=()=>{Object.assign(item,{status:'APPLIED',version:2,canConfirm:false,canRevise:false,canUndo:true});return structuredClone(item);};if(delayConfirm)return new Promise(resolve=>{finishConfirm=()=>resolve(apply());});return apply();}
 if(url.pathname.endsWith('/revise')){assert.equal(body.version,item.version);Object.assign(item,{metadata:{...item.metadata,...body.metadata},version:item.version+1});return structuredClone(item);}
 if(url.pathname.endsWith('/reject')){assert.equal(body.version,item.version);item.status='REJECTED';return structuredClone(item);}
 if(url.pathname.endsWith('/undo')){assert.equal(body.version,item.version);return {staged:true,changeId:'undo-draft',status:'REVIEW'};}
 throw Error('Unexpected mutation '+path);
}};
const settle=()=>new Promise(r=>setTimeout(r,20));
const click=async(text,root=d)=>{const n=[...root.querySelectorAll('button')].find(b=>b.textContent.trim()===text);assert(n,'button '+text);n.click();await settle();};
const row=id=>d.querySelector(`[data-change-id="${id}"]`);
const field=(label,value,root=d)=>{const n=root.querySelector(`[aria-label="${label}"]`);assert(n,label);n.value=value;n.dispatchEvent(new w.Event('input'));n.dispatchEvent(new w.Event('change'));};
const check=(label,root=d)=>{const n=root.querySelector(`[aria-label="${label}"]`);assert(n,label);n.checked=true;n.dispatchEvent(new w.Event('change'));};
let view=w.FinanceCostInputSafety.mount(d.querySelector('#root'),options);await settle();
assert(d.body.textContent.includes('十一月成本.xlsx'));assert(d.body.textContent.includes('等待其他财务人员复核'));
await click('查看',row('draft-a'));
let dialog=d.querySelector('dialog');assert(dialog.textContent.includes('成本计价单位'),'详情必须展示计价单位供独立复核');assert(dialog.textContent.includes('采购成本来源-A'),'详情必须展示来源标识');assert(dialog.textContent.includes('编号类型'),'详情必须展示SKU/SPU类型');assert(dialog.textContent.includes('校验摘要'),'详情必须展示变更计数及风险');assert(dialog.textContent.includes('SPU 统一成本声明'),'不能修改资料的复核者也必须看到SPU适用声明');assert(dialog.textContent.includes('此前已核价来源-A')&&dialog.textContent.includes('本次新核价来源-B'),'改价必须同时显示旧新来源');assert(dialog.textContent.includes('人工上传成本表'));assert(dialog.textContent.includes('提供采购核价依据'));assert(dialog.textContent.includes('历史及后续订单'));
await click('确认应用',dialog);assert.equal(writes.length,0,'未明确确认实际范围不得写入');
check('我已核对实际影响范围',dialog);check('我已确认会影响历史及后续订单',dialog);
field('操作原因','核对采购凭据后补充',dialog);field('依据编号或说明','采购核价单-202611',dialog);
await click('确认应用',dialog);
assert.equal(writes.length,1);assert(writes[0].path.endsWith('/draft-a/confirm'));assert.equal(writes[0].body.acknowledgeImpact,true);assert.equal(writes[0].body.acknowledgeHistorical,true);assert.equal(writes[0].body.evidenceRef,'采购核价单-202611');
await click('查看',row('draft-risk'));dialog=d.querySelector('dialog');
assert(![...dialog.querySelectorAll('button')].some(b=>/确认应用|复核并应用/.test(b.textContent)),'高风险提交者不得自审');
await click('关闭',dialog);
failRead=true;await click('刷新');assert(d.body.textContent.includes('十一月成本.xlsx'),'查询失败保留上一份核对结果');assert(d.body.textContent.includes('核对记录读取失败'));failRead=false;
const before=writes.length;for(const timer of timers.splice(0,1))timer();await settle();assert.equal(writes.length,before,'轮询只查询，不重复提交');
view.dispose();
items=[make('revise')];view=w.FinanceCostInputSafety.mount(d.querySelector('#root'),options);await settle();await click('查看',row('revise'));dialog=d.querySelector('dialog');await click('补充资料',dialog);
dialog=d.querySelector('dialog');field('成本计价单位','套',dialog);field('编号类型','SPU',dialog);field('操作原因','确认同一 SPU 各规格统一成本',dialog);field('依据编号或说明','核价单-2',dialog);check('确认 SPU 适用规格使用统一成本',dialog);check('明确修订已有价格',dialog);
await click('保存并重新校验',dialog);
const revised=writes.at(-1);assert(revised.path.endsWith('/revise/revise'));assert.equal(revised.body.version,1);assert.equal(revised.body.metadata.unit,'套');assert.equal(revised.body.metadata.identifierType,'SPU');assert.equal(revised.body.metadata.spuUniform,true);assert.equal(revised.body.metadata.revision,true);
view.dispose();
items=[make('done',{status:'APPLIED',canConfirm:false,canRevise:false,canUndo:true})];view=w.FinanceCostInputSafety.mount(d.querySelector('#root'),options);await settle();await click('查看',row('done'));dialog=d.querySelector('dialog');await click('撤销本次操作',dialog);dialog=d.querySelector('dialog');field('操作原因','本次资料有误，申请恢复',dialog);field('依据编号或说明','更正单-1',dialog);await click('生成撤销核对单',dialog);assert(writes.at(-1).path.endsWith('/done/undo'));assert.equal(writes.at(-1).body.version,1);
view.dispose();
items=[make('read-only',{sourceName:'<img src=x onerror=alert(1)>.xlsx'})];view=w.FinanceCostInputSafety.mount(d.querySelector('#root'),{...options,canManage:false});await settle();await click('查看',row('read-only'));dialog=d.querySelector('dialog');assert(dialog.textContent.includes('<img src=x onerror=alert(1)>.xlsx'));assert.equal(d.querySelectorAll('img').length,0);assert(![...dialog.querySelectorAll('button')].some(b=>/确认应用|补充资料|驳回|撤销本次/.test(b.textContent)));
view.dispose();
items=[make('late')];delayConfirm=true;view=w.FinanceCostInputSafety.mount(d.querySelector('#root'),options);await settle();await click('查看',row('late'));dialog=d.querySelector('dialog');check('我已核对实际影响范围',dialog);check('我已确认会影响历史及后续订单',dialog);await click('确认应用',dialog);assert.equal(typeof finishConfirm,'function');const callbacksBefore=changeCallbacks;view.dispose();d.querySelector('#root').textContent='已切换到新的页面';finishConfirm();await settle();assert.equal(changeCallbacks,callbacksBefore,'组件已销毁，旧确认结果不得刷新新页面上下文');assert(d.body.textContent.includes('已切换到新的页面'));dom.window.close();
console.log('Manual cost input verification UI: scope acknowledgements, independent review, metadata correction, undo, read-only roles, safe text and GET-only refresh passed.');
