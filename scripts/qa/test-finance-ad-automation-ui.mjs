import fs from 'node:fs/promises';
import assert from 'node:assert/strict';
import {createAdvertisingFixture} from './fixtures/finance-advertising.mjs';
const {JSDOM}=await import(process.env.JSDOM_MODULE||'jsdom');
const dom=new JSDOM('<main id="finance-page"></main>',{url:'https://example.test/#finance-shops',runScripts:'outside-only',pretendToBeVisual:true});
const w=dom.window,d=w.document,root=d.querySelector('main'),fixture=createAdvertisingFixture(),requests=[];
w.currentUser={organizationId:'qa',userId:'admin',permissions:['finance:view','finance:manage']};
w.matchMedia=()=>({matches:false});w.ResizeObserver=class{observe(){}disconnect(){}};
w.echarts={init:()=>({setOption(){},resize(){},dispose(){}})};
w.HTMLDialogElement.prototype.showModal=function(){this.open=true;};w.HTMLDialogElement.prototype.close=function(){this.open=false;this.dispatchEvent(new w.Event('close'));};
Object.defineProperty(w.navigator,'clipboard',{value:{writeText:async()=>{}}});
fixture.shops[1].platform='TIKTOK_SHOP';
fixture.accounts[0]={advertiser_id:'100001',name:'跨店账户',shop_id:'',currency:'PHP',timezone:'Asia/Manila',enabled:true,status:'PARTIAL',discovery_status:'SUCCESS',requestedDays:31,
  stores:[{store_id:'700001',store_name:'平台店 A',shop_id:'a',match_status:'MATCHED',report_eligible:true,active:true},
    {store_id:'700002',store_name:'平台店 B',shop_id:'b',match_status:'MATCHED',report_eligible:true,active:true},
    {store_id:'700003',store_name:'未连接财务店',shop_id:'',match_status:'UNMATCHED',report_eligible:true,active:true}],
  streams:[{channel:'BASIC',store_id:'',status:'SUCCESS'},{channel:'GMV',store_id:'700001',status:'SUCCESS'},
    {channel:'GMV',store_id:'700002',status:'ERROR',error_message:'读取权限不足，自动重试'}],
  coverage:[{channel:'BASIC',store_id:'',covered_days:31,amount:0},{channel:'GMV',store_id:'700001',covered_days:31,amount:999},
    {channel:'GMV',store_id:'700002',covered_days:7,amount:0}]};
w.api=async(path,options={})=>{
  const u=new URL(path,'https://example.test'),q=Object.fromEntries(u.searchParams),endpoint=u.pathname.replace('/api/v1/finance','');requests.push({endpoint,q,options});
  if(endpoint==='/shops')return {data:{items:fixture.shops,groups:fixture.groups,sync:[],deletions:[]}};
  if(endpoint==='/ads')return {data:fixture.accounts};
  if(endpoint==='/ads/application')return {data:{configured:true}};
  if(endpoint==='/ads/authorize')return {data:{url:'https://example.test/authorize',expiresAt:'2026-12-01T00:00:00Z'}};
  if(endpoint==='/overview')return {data:fixture.overview(q)};
  if(endpoint==='/records')return {data:fixture.records(q)};
  throw Error('Unexpected endpoint '+endpoint);
};
for(const name of ['incremental-dom.js','incremental-charts.js','finance-workspace.js'])w.eval(await fs.readFile(new URL('../../frontend/'+name,import.meta.url),'utf8'));
const wait=async f=>{for(let i=0;i<200;i++){if(f())return;await new Promise(r=>setTimeout(r,5));}throw Error('UI timeout');};
const ready=()=>wait(()=>root.getAttribute('aria-busy')==='false');
const button=(text,host=d)=>[...host.querySelectorAll('button')].find(n=>n.textContent.trim()===text);
const card=text=>root.querySelector(`[data-update-key="${text}"]`);
const select=async(label,value)=>{const node=d.querySelector(`[aria-label="${label}"]`);node.value=value;node.dispatchEvent(new w.Event('change'));await ready();};
try{
  w.FinanceWorkspace.enter('shops');await ready();
  await select('店铺分组','clothes');assert(card('广告账户').textContent.includes('跨店账户'),'multi-shop account remains visible in every matching group');
  assert(card('广告账户').textContent.includes('待连接对应财务店铺'));assert(card('广告账户').textContent.includes('部分数据待补齐'));
  button('复制广告授权链接').click();await wait(()=>requests.some(r=>r.endpoint==='/ads/authorize'));
  const dialog=d.querySelector('dialog'),sent=JSON.parse(requests.find(r=>r.endpoint==='/ads/authorize').options.body);
  assert.equal(sent.shopId,'');assert.equal(sent.gmvStoreId,'');assert(!dialog.textContent.includes('GMV Max 店铺 ID'));assert(!dialog.querySelector('select'));
  dialog.close();
  w.FinanceWorkspace.enter('ads');await ready();await select('店铺分组','');
  await select('开始日期','2026-10-01');await select('结束日期','2026-10-31');
  assert.equal(requests.filter(r=>r.endpoint==='/ads').at(-1).q.start,'2026-10-01');
  assert(card('店铺广告消耗').textContent.includes('GMV Max 报表消耗（待核对）'));
  assert(card('店铺广告消耗').textContent.includes('99.9'),'GMV source cost is visible without adding it to posted fees');
  const health=card('广告数据同步');assert(health.textContent.includes('31 / 31 天'));assert(health.textContent.includes('7 / 31 天'));
  const basic=[...health.querySelectorAll('tr')].find(r=>r.textContent.includes('普通广告（账户总额）'));assert(basic.textContent.includes('接口返回零消耗'));
  const partial=[...health.querySelectorAll('tr')].find(r=>r.textContent.includes('7 / 31 天'));assert(partial.textContent.includes('已采集部分'));assert(!partial.textContent.includes('接口返回零消耗'));
  assert(button('异常补录'));assert(!button('导入账单'));
  await select('店铺分组','baby');assert(!card('广告数据同步').textContent.includes('7 / 31 天'),'other store coverage respects the selected group');
  assert(!requests.some(r=>r.options.method&&r.endpoint!=='/ads/authorize'),'analysis never changes platform advertising authorization');
  console.log('PASS advertising automation UI: automatic OAuth options, multiple matched stores, scoped coverage, partial vs zero, GMV costs and exceptional import.');
}finally{w.FinanceWorkspace.leave();w.close();}
