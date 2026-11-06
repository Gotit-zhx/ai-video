import fs from 'node:fs/promises';
import assert from 'node:assert/strict';
const {JSDOM}=await import(process.env.JSDOM_MODULE||'jsdom');
const dom=new JSDOM('<main>千易界面测试 · 示例数据</main>',{url:'https://example.test',runScripts:'outside-only'});
const w=dom.window,d=w.document,calls=[];
w.HTMLDialogElement.prototype.showModal=function(){this.open=true;};
w.HTMLDialogElement.prototype.close=function(){this.open=false;this.dispatchEvent(new w.Event('close'));};
let config={configured:true,hasSecret:true,environment:'CN',app_id:'test-app',sync_start:'2026-08-01',enabled:false,status:'PAUSED',bound_count:1,shop_count:2},deferred;
const request=async(path,options={})=>{
  const body=options.body?JSON.parse(options.body):null;calls.push({path,options,body});
  if(path==='/qianyi'&&options.method==='PUT'){config={...config,app_id:body.appId};return '公司千易配置已保存';}
  if(path.startsWith('/qianyi/shops?'))return [{id:'local',name:'示例母婴店',remote_shop_name:'千易母婴店',status:'SUCCESS'}];
  if(path.startsWith('/qianyi/returns/detail?'))return {shop_id:'local',shop_name:'示例母婴店',return_number:'R1',order_id:'586208660231587825',cost_status:'WAIT_COST_BASIS',amount_cny:null,sourceFields:{returnNumber:'R1',onlineOrderNumber:'586208660231587825',returnStatus:'FINISH',refundStatus:'WAIT_PAYMENT',returnSkuList:[{sku:'SKU-A',orderSkuId:'987654321987654321',quantity:2,goodQuantity:1,badQuantity:1}]},costMovements:[],costBasis:'仅使用已确认的千易库存成本'};
  if(path.startsWith('/qianyi/returns?')){
    if(deferred){const current=deferred;deferred=null;return new Promise(resolve=>current.resolve=resolve);}
    const q=new URL(path,'https://test').searchParams,page=Number(q.get('page'));
    return {total:51,page,pageSize:50,items:[{shop_id:'local',shop_name:'示例母婴店',platform:'TIKTOK_SHOP',return_number:page===1?'R1':'R2',order_id:'586208660231587825',return_state:'FINISH',refund_state:q.get('refundStatus')||'',aftersale_type:'SALES_RETURN',cost_status:'WAIT_COST_BASIS',amount_cny:null,source_created_at:1788210000000}],basis:'千易售后接口数据'};
  }
  if(path.endsWith('/sync'))return '已安排同步';
  return structuredClone(config);
};
w.eval(await fs.readFile(new URL('../../frontend/finance-qianyi.js',import.meta.url),'utf8'));
const settle=()=>new Promise(resolve=>setTimeout(resolve,10));
const current=()=>[...d.querySelectorAll('dialog')].at(-1);
const button=text=>[...current().querySelectorAll('button')].find(n=>n.textContent===text);
const field=label=>current().querySelector(`[aria-label="${label}"]`);
async function click(text){assert(button(text),text);button(text).click();await settle();}
async function change(label,value){field(label).value=value;field(label).dispatchEvent(new w.Event('change'));await settle();}
const context={request,shops:[{id:'local',name:'示例母婴店'}],groups:[{id:'baby',name:'母婴'}]};
await w.FinanceQianyi.openCompany(context);
assert(!field('对应千易店铺'));assert(current().textContent.includes('公司只需配置一次'));
field('千易 App Secret').value='not-a-real-secret';await click('保存全局配置');
assert.equal(calls.find(c=>c.options.method==='PUT').body.appSecret,'not-a-real-secret');assert.equal(field('千易 App Secret').value,'');assert.equal(w.localStorage.length,0);
await click('保存全局配置');assert.equal(calls.filter(c=>c.options.method==='PUT').at(-1).body.appSecret,'');
await click('查看千易售后');assert(!current().textContent.includes('退货成本冲回'));assert(!field('成本状态'));assert(current().textContent.includes('千易未返回'));
assert.equal(field('日期口径').value,'RECEIPT');assert.equal(field('退回物流状态').value,'SIGNED');assert.equal(field('退货状态').value,'FINISH');
await change('退货状态','FINISH');await change('退款状态','WAIT_PAYMENT');
let query=new URL(calls.at(-1).path,'https://test').searchParams;assert.equal(query.get('returnStatus'),'FINISH');assert.equal(query.get('refundStatus'),'WAIT_PAYMENT');
await change('日期口径','RECEIPT');field('开始日期').value='2026-09-01';field('结束日期').value='2026-09-30';field('平台订单号 / 千易售后单号').value='586208660231587825';await click('查询');
query=new URL(calls.at(-1).path,'https://test').searchParams;assert.equal(query.get('dateField'),'RECEIPT');assert.equal(query.get('numbers'),'586208660231587825');assert.equal(query.get('start'),'2026-09-01');
await click('下一页');assert(new URL(calls.at(-1).path,'https://test').searchParams.get('page')==='2');assert(button('下一页').disabled);
await click('上一页');await click('查看千易数据');assert(current().textContent.includes('SKU-A'));assert(current().textContent.includes('987654321987654321'));assert(current().textContent.includes('查看千易接口原始字段'));assert(current().textContent.includes('WAIT_PAYMENT'));
await click('关闭');
const stale={};deferred=stale;button('查询').click();await settle();await change('退款状态','FINISH');stale.resolve({total:1,page:1,items:[{return_number:'STALE'}]});await settle();assert(!current().textContent.includes('STALE'));
await click('清除日期');query=new URL(calls.at(-1).path,'https://test').searchParams;assert.equal(query.get('start'),'');assert.equal(query.get('end'),'');
const closing={};deferred=closing;button('查询').click();await settle();await click('关闭');closing.resolve({total:0,page:1,items:[]});await settle();assert.equal(d.querySelectorAll('dialog').length,1);
await click('关闭');assert(!d.querySelector('dialog'));w.close();
console.log('PASS Qianyi UI: company config, write-only secret, status/date/number filters, pagination, native evidence, unknown cost, stale responses and close safety.');
