import fs from 'node:fs/promises';
import assert from 'node:assert/strict';
const {JSDOM}=await import(process.env.JSDOM_MODULE||'jsdom');
const dom=new JSDOM('<!doctype html><div id="finance-page"></div>',{url:'https://example.test/#finance-shops',runScripts:'outside-only',pretendToBeVisual:true});
const w=dom.window,d=w.document,requests=[];
let adConfigured=true,copied='',showPlatform=false,showCreateTime=false;
Object.defineProperty(w.navigator,'clipboard',{value:{writeText:async value=>{copied=value;}}});
w.currentUser={roles:['STAFF'],permissions:['finance:manage']};
w.matchMedia=()=>({matches:false});w.ResizeObserver=class{observe(){}disconnect(){}};
w.HTMLDialogElement.prototype.showModal=function(){this.open=true;};
w.HTMLDialogElement.prototype.close=function(){this.open=false;this.dispatchEvent(new w.Event('close'));};
w.echarts={init:()=>({setOption(){},resize(){},dispose(){},getWidth(){return 1;},getHeight(){return 1;}})};
let groups=[{id:'clothes',name:'服饰',shop_count:1},{id:'shoes',name:'鞋靴',shop_count:1}];
const shops=[{id:'shop1',name:'服饰店',platform:'SHOPEE',currency:'PHP',timezone:'Asia/Manila',group_id:'clothes',enabled:true},
  {id:'shop2',name:'鞋店',platform:'TIKTOK_SHOP',currency:'PHP',timezone:'Asia/Bangkok',group_id:'shoes',enabled:true},
  {id:'shop3',name:'未分组店',platform:'SHOPEE',currency:'PHP',timezone:'Asia/Manila',enabled:true}];
const records=[
  {id:'record1',shop_id:'shop1',platform:'SHOPEE',external_id:'261022ABCD1234',record_date:'2026-10-22T00:00:00.000Z',create_time:'2026-08-28T05:27:54Z',order_created_time:'2026-08-28T05:27:54Z',status:'PROCESSED'},
  {id:'record2',shop_id:'shop2',platform:'TIKTOK_SHOP',external_id:'576123456789012345',record_date:'2026-10-22T00:00:00+08:00',create_time:'2026-10-21T18:06:09Z',status:'AWAITING_COLLECTION'},
  {id:'record3',shop_id:'shop2',external_id:'A3',record_date:'2026-10-21',create_time:'invalid-time',status:'SHIPPED'},
  {id:'record4',external_id:'A4',record_date:null,status:'NEW_PLATFORM_STATUS'}
];
records[0].product_sku_values=['YUXI-BPants-L','（YUXI-BPants-XXL）*2'];records[0].product_skus=records[0].product_sku_values.join('\n');records[0].product_cost_cny=36.54;
records[1].order_type_label='商家承担的免费样品单';
const costLinks=[];w.FinanceCosts={openOrders:value=>costLinks.push(value),reset(){}};
w.api=async(path,options={})=>{
  const url=new URL(path,'https://example.test'),method=options.method||'GET',body=options.body?JSON.parse(options.body):{};
  requests.push({url,method,body});const endpoint=url.pathname.replace('/api/v1/finance','');
  if(endpoint==='/shops')return {data:{items:shops,sync:[],groups}};
  if(endpoint==='/ads')return {data:[]};
  if(endpoint==='/ads/application')return {data:{configured:adConfigured,managed:true}};
  if(endpoint==='/ads/authorize')return {data:{authorizationUrl:'https://business-api.tiktok.com/portal/auth?state=opaque',expiresAt:'2026-10-22T20:00:00Z'}};
  if(endpoint==='/groups'&&method==='GET')return {data:groups};
  if(endpoint==='/groups'&&method==='POST'){groups=[...groups,{id:'new',name:body.name,shop_count:0}];return {data:{id:'new'}};}
  if(endpoint.startsWith('/groups/')){const id=endpoint.split('/').at(-1);groups=method==='DELETE'?groups.filter(g=>g.id!==id):groups.map(g=>g.id===id?{...g,name:body.name}:g);return {data:'已保存'};}
  if(endpoint==='/shops/authorize'){assert(body.groupId,'OAuth requires a group');return {data:{authorizationUrl:'https://official.test/authorize?state=opaque',expiresAt:'2026-10-22T20:00:00Z'}};}
  const fields=[{id:'record_date',label:'结算日期',type:'date'},{id:'order_created_time',label:'下单时间（店铺时区）',type:'datetime'},{id:'platform',label:'平台',type:'text'},{id:'shop_id',label:'店铺',type:'text'},{id:'external_id',label:'记录编号',type:'text'},{id:'order_id',label:'订单编号',type:'text'},{id:'status',label:'状态',type:'text'},{id:'currency',label:'币种',type:'text'},{id:'amount',label:'原币金额',type:'money'},{id:'create_time',label:'平台创建时间（UTC）',type:'datetime'}].map(f=>({...f,group:'主要字段'})),page=Number(url.searchParams.get('page')||1);
  fields.splice(5,0,{id:'product_skus',label:'商品 SKU',type:'text',group:'主要字段'},{id:'product_cost_cny',label:'商品成本（人民币）',type:'money',group:'商品成本'});
  const settings=view=>{const visible=fields.filter(f=>(showPlatform||f.id!=='platform')&&(showCreateTime||f.id!=='create_time')).map(f=>f.id);return {view,fields,visible,exported:fields.map(f=>f.id),defaults:{visible,exported:fields.map(f=>f.id)}};};
  if(endpoint.startsWith('/fields/'))return {data:method==='PUT'?'已保存':settings(endpoint.split('/').at(-1))};
  if(endpoint.startsWith('/records/'))return {data:{...records.find(r=>r.id===endpoint.split('/').at(-1)),kind:'ORDER'}};
  const common={filters:{...Object.fromEntries(url.searchParams),currency:'PHP',displayCurrency:'PHP'},groups,shops,sync:[],currencies:['PHP'],
    totals:[{kind:'SETTLEMENT',amount:1200,records:3},{kind:'BILL',amount:1200,records:1},{kind:'PENDING',amount:300,records:2}],
    ranking:[],statuses:[],fees:[],trend:[],basis:'测试数据',updatedAt:'2026-10-22T00:00:00Z',hourly:[],recent:records,
    productCosts:{scope:'ORDER',basis:'测试订单成本口径',total:{known_amount_cny:88.75,amount_cny:null,ready_count:3,pending_count:1,estimated_count:2,excluded_count:0},shops:[{shop_id:'shop2',known_amount_cny:88.75,amount_cny:null,pending_count:1}]}};
  if(endpoint==='/workspace/query'){
    const kind=url.searchParams.get('view')==='reconciliation'?'RECONCILIATION':url.searchParams.get('kind')||'ORDER';
    const recordPage={items:records.map((r,i)=>({...r,kind,order_id:i===3?'':r.external_id,currency:'PHP',amount:[203,226,kind==='SETTLEMENT'?0:538,null][i]})),total:51,page,pageSize:50,fieldSettings:settings(kind)};
    return {data:{...common,records:recordPage}};
  }
  if(['/overview','/screen'].includes(endpoint))return {data:common};
  if(['/records','/reconciliation'].includes(endpoint)){const kind=endpoint==='/reconciliation'?'RECONCILIATION':url.searchParams.get('kind')||'ORDER';return {data:{...common,items:records.map((r,i)=>({...r,kind,order_id:i===3?'':r.external_id,currency:'PHP',amount:[203,226,kind==='SETTLEMENT'?0:538,null][i]})),total:51,page,pageSize:50,fieldSettings:settings(kind)}};}
  throw Error('Unexpected API '+endpoint);
};
for(const file of ['incremental-dom.js','incremental-charts.js','finance-workspace.js'])w.eval(await fs.readFile(new URL('../../frontend/'+file,import.meta.url),'utf8'));
const button=(text,scope=d)=>[...scope.querySelectorAll('button')].find(n=>n.textContent.trim()===text);
async function ready(){for(let i=0;i<200;i++){await new Promise(r=>setTimeout(r,5));if(d.querySelector('#finance-page').getAttribute('aria-busy')==='false')return;}throw Error('UI timeout');}
async function tick(){await new Promise(r=>setTimeout(r,15));}
async function choose(label,value){const select=d.querySelector(`[aria-label="${label}"]`);assert(select,label);select.value=value;select.dispatchEvent(new w.Event('change'));await ready();}

w.FinanceWorkspace.enter('orders');await ready();
assert(d.querySelector('thead').textContent.includes('下单日期（店铺时区）'));
assert.equal(requests.filter(r=>r.url.pathname.endsWith('/overview')).length,0);
await choose('订单范围','SETTLED');
let row=d.querySelector('tbody tr');assert.equal(row.querySelector('.fi-record-value').textContent,'2026-10-22');
assert.equal(row.querySelector('time').textContent,'2026-08-28 13:27:54');assert(row.querySelector('time').title.includes('Asia/Manila'));
assert(d.querySelector('thead').textContent.includes('首次平台结算日期'));assert(d.querySelector('thead').textContent.includes('下单时间（店铺时区）'));
const stable=row;button('刷新').click();await ready();assert.equal(d.querySelector('tbody tr'),stable);
let input=d.querySelector('.fi-number-search textarea');input.value='585766520348574952\n586208660231587825，585766520348574952';
input.closest('form').dispatchEvent(new w.Event('submit',{cancelable:true}));await ready();
let query=requests.filter(r=>r.url.pathname.endsWith('/workspace/query')).at(-1).url.searchParams;
assert.equal(query.get('numbers'),'585766520348574952,586208660231587825');assert.equal(query.get('page'),'1');
await choose('店铺','shop1');query=requests.filter(r=>r.url.pathname.endsWith('/workspace/query')).at(-1).url.searchParams;
assert.equal(query.get('shopId'),'shop1');assert.equal(query.get('numbers'),'585766520348574952,586208660231587825');
button('下一页').click();await ready();assert.equal(requests.at(-1).url.searchParams.get('page'),'2');
button('清除').click();await ready();assert.equal(requests.at(-1).url.searchParams.get('numbers'),'');assert.equal(requests.at(-1).url.searchParams.get('page'),'1');
for(const route of ['settlements','reconciliation','fee-records']){w.FinanceWorkspace.enter(route);await ready();assert(d.querySelector('.fi-number-search textarea'),route+' supports identifier search');assert([...d.querySelectorAll('thead')].some(n=>n.textContent.includes('下单时间')),route+' displays order placement separately');}
// Exports use the table's full query, even after background refresh/navigation changes the page.
const downloads=[],exportRequests=[];let completeExport;
w.URL.createObjectURL=()=> 'blob:finance-export';w.URL.revokeObjectURL=()=>{};
w.HTMLAnchorElement.prototype.click=function(){downloads.push(this.download);};
w.fetch=(path,options)=>new Promise((resolve,reject)=>{
  exportRequests.push({url:new URL(path,'https://example.test'),options});completeExport=resolve;
  options.signal.addEventListener('abort',()=>reject(new w.DOMException('Aborted','AbortError')));
});
w.FinanceWorkspace.enter('settlements');await ready();
await choose('店铺分组','clothes');await choose('平台','SHOPEE');await choose('店铺','shop1');
for(const [kind,label] of [['SETTLEMENT','结算明细'],['BILL','结算账单'],['PENDING','待结算']]){
  [...d.querySelectorAll('.fi-settlement-totals button')].find(b=>b.textContent.startsWith(label+'合计')).click();await ready();
  input=d.querySelector('.fi-number-search textarea');input.value='00123456789012345678';
  input.closest('form').dispatchEvent(new w.Event('submit',{cancelable:true}));await ready();
  button('下一页').click();await ready();
  const current=requests.filter(r=>r.url.pathname.endsWith('/workspace/query')).at(-1).url.searchParams;
  button('导出 Excel').click();await tick();const modal=d.querySelector('dialog');
  assert.equal(modal.querySelector('[aria-label="导出类型"]').value,kind);
  assert(modal.textContent.includes('不限当前页'));assert(modal.textContent.includes('00123456789012345678'));
  const submit=button('下载报表',modal);submit.click();await tick();submit.click();
  assert(submit.disabled);assert(!modal.querySelector('.fi-loading').hidden);
  assert.equal(exportRequests.length,downloads.length+1,'repeated clicks do not send duplicate requests');
  const selection=exportRequests.at(-1).url.searchParams;
  for(const key of ['start','end','shopId','groupId','platform','currency','displayCurrency','fxSnapshot','numbers'])assert.equal(selection.get(key),current.get(key),key);
  assert.equal(selection.get('view'),kind);assert.equal(selection.get('format'),'xlsx');assert(!selection.has('page'));assert(!selection.has('pageSize'));
  completeExport({ok:true,blob:async()=>new w.Blob(['test workbook'])});await tick();
  assert.equal(downloads.at(-1),`${label}-${current.get('start')}-${current.get('end')}.xlsx`);assert(!modal.isConnected);
}
button('导出 Excel').click();await tick();
let modal=d.querySelector('dialog');button('下载报表',modal).click();await tick();
completeExport({ok:false,json:async()=>({message:'导出超过限制，请缩小范围'})});await tick();
assert(modal.querySelector('.fi-feedback').textContent.includes('请缩小范围'));
assert(!button('下载报表',modal).disabled);assert(modal.querySelector('.fi-loading').hidden);
button('下载报表',modal).click();await tick();button('取消',modal).click();await tick();assert(exportRequests.at(-1).options.signal.aborted);
button('导出 Excel').click();await tick();modal=d.querySelector('dialog');
const captured=modal.querySelector('p').textContent;
await choose('店铺','shop2');button('下载报表',modal).click();await tick();
assert.equal(exportRequests.at(-1).url.searchParams.get('shopId'),'shop1');assert.equal(modal.querySelector('p').textContent,captured);
button('取消',modal).click();await tick();
w.FinanceWorkspace.enter('orders');await ready();
if(process.env.FINANCE_DATE_PREVIEW_PATH){const css=await fs.readFile(new URL('../../frontend/finance-workspace.css',import.meta.url),'utf8');await fs.writeFile(process.env.FINANCE_DATE_PREVIEW_PATH,`<!doctype html><meta charset="utf-8"><title>下单与结算日期核对 · 测试数据</title><style>${css}body{margin:0;padding:28px;background:#f5f7fc;font:14px/1.5 -apple-system,BlinkMacSystemFont,"PingFang SC",sans-serif}</style><h2>下单与结算日期核对 · 测试数据</h2>${d.querySelector('#finance-page').outerHTML}`);}
w.FinanceWorkspace.leave();dom.window.close();console.log('Order dates, batch search, settlement exports, frozen filters, download state, errors and cancellation passed.');
