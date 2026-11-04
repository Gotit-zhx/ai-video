import fs from 'node:fs/promises';
import assert from 'node:assert/strict';
const {JSDOM}=await import(process.env.JSDOM_MODULE||'jsdom');
const dom=new JSDOM('<main class="finance-workspace"><div id="root"></div></main>',{url:'https://example.test',runScripts:'outside-only'});
const w=dom.window,d=w.document,root=d.querySelector('#root'),reads=[],writes=[];
let holdReads=false;const pending=[];
w.HTMLDialogElement.prototype.showModal=function(){this.open=true;};
w.HTMLDialogElement.prototype.close=function(){this.open=false;this.dispatchEvent(new w.Event('close'));};
let data={files:[{id:'one',source_name:'9月.xlsx',status:'READY',period_start:'2026-09-01',period_end:'2026-09-30',
  row_count:280630,matched_count:100000,unmatched_count:180630,billing_failed_count:707,amount_cny:504467.5}],
  rows:[{import_id:'one',source_row:1,order_id:'586208660231587825',customer:'TEST',warehouse:'HY_PH',amount_cny:1.95,
    transaction_date:'2026-09-01',match_status:'UNMATCHED',match_note:'等待订单同步',billing_status:'计算成功',import_status:'READY'}],
  total:51,page:1,pageSize:50,period:{amount_cny:195,orders:100,shipments:100,conflicts:0},state:{},processing:false,basis:'参考号绑定订单，人民币'};
const request=async(path,options={})=>{
  if(options.method){
    writes.push({path,...options});
    if(path==='/label-costs/import'){
      assert(options.body instanceof w.FormData);
      return {id:options.body.get('file').name,duplicate:options.body.get('file').name==='duplicate.xlsx'};
    }
    return {};
  }
  const url=new URL(path,'https://example.test');reads.push(url);
  const result=url.searchParams.has('detailsOnly')?{rows:data.rows,total:data.total,page:Number(url.searchParams.get('page')),pageSize:50}:data;
  if(holdReads)return new Promise(resolve=>pending.push({resolve,signal:options.signal,result:structuredClone(result)}));
  return structuredClone(result);
};
for(const file of ['incremental-dom.js','finance-label-costs.js'])w.eval(await fs.readFile(new URL('../frontend/'+file,import.meta.url),'utf8'));
const wait=()=>new Promise(r=>setTimeout(r,15));
const button=(text,scope=d)=>[...scope.querySelectorAll('button')].find(b=>b.textContent===text);
async function click(text,scope=d){const b=button(text,scope);assert(b,text);b.click();await wait();}
function field(label,value){const n=d.querySelector(`[aria-label="${label}"]`);assert(n,label);n.value=value;n.dispatchEvent(new w.Event('change'));}
const options={request,canManage:true,groups:[{id:'baby',name:'母婴组'},{id:'perfume',name:'香水组'},{id:'empty',name:'空分组'}],shops:[
  {id:'ph',group_id:'baby',name:'菲律宾店',display_name:'母婴别名',platform:'SHOPEE'},
  {id:'tk',group_id:'baby',name:'母婴 TK 店',platform:'TIKTOK_SHOP'},
  {id:'perfume',group_id:'perfume',name:'香水店',platform:'SHOPEE'},
  {id:'ungrouped',name:'未分组店',platform:'SHOPEE'}],
  filters:{start:'2026-09-01',end:'2026-09-30',groupId:'baby',shopId:'ph'}};
w.FinanceLabelCosts.mount(root,options);await wait();
const shopValues=()=>[...d.querySelector('[aria-label="账单匹配店铺"]').options].map(n=>n.value);
assert.deepEqual(shopValues(),['','ph','tk']);assert.equal(d.querySelector('[aria-label="账单匹配店铺"]').selectedOptions[0].textContent,'母婴别名');
assert(root.textContent.includes('已完成 · 有待处理项'));assert(!root.textContent.includes('计费失败'));
assert(root.textContent.includes('504,467.50'));assert(root.textContent.includes('待同步匹配'));
const retained=root.querySelector('tbody tr');
await click('刷新');assert.equal(root.querySelector('tbody tr'),retained,'unchanged polls retain file rows');
await click('查看明细');assert.equal(reads.at(-1).searchParams.get('fileId'),'one');
assert.equal(reads.at(-1).searchParams.get('detailsOnly'),'true','file switching must not recompute the unchanged financial summary');
for(let i=0;i<3;i++){
  await click('查看全部');assert(!reads.at(-1).searchParams.has('fileId'));assert(!button('查看明细').disabled);
  await click('查看明细');assert.equal(reads.at(-1).searchParams.get('fileId'),'one');assert(!button('查看全部').disabled);
}
holdReads=true;
button('查看全部').click();await wait();
assert(!d.querySelector('.fl-read-progress').hidden,'a delayed interactive request shows visible progress');
assert(root.textContent.includes('586208660231587825'),'existing records stay visible while loading');
button('待匹配').click();await wait();
assert(pending[0].signal.aborted,'a newer filter cancels the obsolete request');
assert.equal(reads.at(-1).searchParams.get('matchStatus'),'UNMATCHED');
pending[1].resolve(pending[1].result);await wait();
pending[0].resolve({...pending[0].result,rows:[{...data.rows[0],order_id:'STALE-RESPONSE'}]});await wait();
assert(!root.textContent.includes('STALE-RESPONSE'),'late responses cannot replace the selected filter');
assert(d.querySelector('.fl-read-progress').hidden);holdReads=false;
for(const [label,value]of [['待匹配','UNMATCHED'],['已匹配','MATCHED'],['全部记录','']]){
  await click(label);assert.equal(reads.at(-1).searchParams.get('matchStatus')||'',value);
  assert.equal(button(label).getAttribute('aria-pressed'),'true');
  assert.equal(d.querySelector('[aria-label="匹配状态"]').value,value);
}
await click('下一页');assert.equal(reads.at(-1).searchParams.get('page'),'2');
field('匹配状态','UNMATCHED');await wait();assert.equal(reads.at(-1).searchParams.get('matchStatus'),'UNMATCHED');
assert.equal(reads.at(-1).searchParams.get('page'),'1');
field('贴单订单编号','586208660231587825');await wait();assert.equal(reads.at(-1).searchParams.get('numbers'),'586208660231587825');
field('账单匹配店铺','ph');await wait();field('账单所属分组','baby');await wait();
assert.equal(d.querySelector('[aria-label="账单匹配店铺"]').value,'');assert(!reads.at(-1).searchParams.has('shopId'));
holdReads=true;pending.length=0;
field('账单匹配店铺','ph');field('账单所属分组','perfume');
assert.deepEqual(shopValues(),['','perfume'],'group changes narrow shops before the query returns');
assert.equal(d.querySelector('[aria-label="账单匹配店铺"]').value,'');
assert.equal(reads.at(-1).searchParams.get('groupId'),'perfume');assert(!reads.at(-1).searchParams.has('shopId'));
field('账单所属分组','UNGROUPED');assert.deepEqual(shopValues(),['','ungrouped']);
field('账单所属分组','empty');assert.deepEqual(shopValues(),['']);
field('账单所属分组','');assert.deepEqual(shopValues(),['','ph','tk','perfume','ungrouped']);
for(const read of [...pending].reverse())read.resolve(read.result);
holdReads=false;await wait();assert.deepEqual(shopValues(),['','ph','tk','perfume','ungrouped'],'late responses cannot restore the previous group');
const previousReads=reads.length;field('结算开始日期','2026-10-01');await wait();
assert(root.textContent.includes('开始日期不能晚于结束日期'));assert.equal(reads.length,previousReads);
await click('导入月度账单');
const dialog=d.querySelector('dialog'),input=dialog.querySelector('input[type=file]');assert(input.multiple);
Object.defineProperty(input,'files',{value:[new w.File(['a'],'new.xlsx'),new w.File(['b'],'duplicate.xlsx')]});
await click('上传并自动匹配',dialog);
assert.equal(writes.filter(r=>r.path==='/label-costs/import').length,2);assert(root.textContent.includes('跳过重复文件 1 份'));
data.processing=true;data.files[0].status='PARSING';data.files[0].row_count=1500;
await click('刷新');assert(d.querySelector('.fl-content progress'));assert(root.textContent.includes('已读取 1500 行'));
assert(!button('移除'),'active ingestion cannot be deleted halfway through validation');
data.processing=false;data.files[0].status='FAILED';data.files[0].error_message='第 500 行金额不一致';
await click('刷新');assert(!d.querySelector('.fl-content progress'));assert(d.querySelector('.fl-read-progress').hidden);assert(root.textContent.includes('第 500 行金额不一致'));
await click('重试');assert(writes.at(-1).path.endsWith('/one/retry'));
data.files[0].status='READY';data.files[0].unmatched_count=0;data.files[0].error_message=null;
data.rows[0].match_status='MATCHED';data.rows[0].amount_cny=0;data.rows[0].billing_status='计费失败';
await click('刷新');assert(!root.textContent.includes('有待处理项'));assert(!root.textContent.includes('计费失败'));
assert(root.textContent.includes('¥ 0.00'));assert(root.querySelector('tbody tr:last-child'));
await click('移除');await click('确认移除',d.querySelector('dialog'));assert.equal(writes.at(-1).method,'DELETE');
await click('重新匹配');assert.equal(writes.at(-1).path,'/label-costs/rematch');
w.FinanceLabelCosts.reset();w.FinanceLabelCosts.mount(root,{...options,canManage:false,filters:{...options.filters,platform:'TIKTOK_SHOP'}});await wait();
assert.deepEqual(shopValues(),['','tk'],'inherited platform and group restrictions intersect');
assert.equal(d.querySelector('[aria-label="账单匹配店铺"]').value,'');assert(!reads.at(-1).searchParams.has('shopId'),'invalid restored shops are cleared before querying');
assert(!button('导入月度账单'));assert(!button('移除'));assert(!button('重试'));assert(!button('重新匹配'));
w.FinanceLabelCosts.reset();w.close();
console.log('PASS invoice UI: asynchronous status/progress/errors, multi-file dedupe, filters, stable DOM, removals and read-only access.');
