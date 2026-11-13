import fs from 'node:fs/promises';
import assert from 'node:assert/strict';
import {advertisingWorkspaceResponse,createAdvertisingFixture} from './fixtures/finance-advertising.mjs';
const {JSDOM}=await import(process.env.JSDOM_MODULE||'jsdom');
const dom=new JSDOM('<main id="finance-page"></main>',{url:'https://example.test/#finance-overview',runScripts:'outside-only',pretendToBeVisual:true});
const w=dom.window,d=w.document,root=d.querySelector('main'),fixture=createAdvertisingFixture(),reads=[],exports=[],charts=[];
let partial=false,failAccounts=false,heldShop='',releaseHeld;
w.currentUser={organizationId:'qa',userId:'reader',permissions:['finance:view']};
w.matchMedia=()=>({matches:false});w.ResizeObserver=class{observe(){}disconnect(){}};
w.echarts={init:()=>({setOption(option){charts.push(option);},resize(){},dispose(){}})};
w.HTMLDialogElement.prototype.showModal=function(){this.open=true;};w.HTMLDialogElement.prototype.close=function(){this.open=false;this.dispatchEvent(new w.Event('close'));};
w.URL.createObjectURL=()=> 'blob:ad-qa';w.URL.revokeObjectURL=()=>{};w.HTMLAnchorElement.prototype.click=function(){};
w.fetch=async path=>{exports.push(new URL(path,'https://example.test'));return {ok:true,blob:async()=>new w.Blob(['qa'])};};
w.api=async(path,options={})=>{
  assert(!options.method||options.method==='GET','read-only analysis must not write business data');
  const u=new URL(path,'https://example.test'),q=Object.fromEntries(u.searchParams),endpoint=u.pathname.replace('/api/v1/finance','');reads.push({endpoint,q});
  if(endpoint==='/ads'){if(failAccounts)throw Error('mock unavailable');return {data:fixture.accounts};}
  if(endpoint==='/overview'){
    const result=fixture.overview(q,partial);
    if(heldShop&&q.shopId===heldShop)await new Promise(resolve=>{releaseHeld=resolve;});
    return {data:result};
  }
  if(endpoint==='/workspace/query'){
    if(heldShop&&q.shopId===heldShop)await new Promise(resolve=>{releaseHeld=resolve;});
    return {data:advertisingWorkspaceResponse(fixture,q,{partial,adAccounts:failAccounts?[]:fixture.accounts,adAccountError:failAccounts})};
  }
  if(endpoint==='/records')return {data:fixture.records(q,partial)};
  if(endpoint.startsWith('/fields/'))return {data:fixture.records({kind:endpoint.split('/').at(-1)}).fieldSettings};
  throw Error('Unexpected API '+endpoint);
};
for(const name of ['incremental-dom.js','incremental-charts.js','finance-workspace.js'])w.eval(await fs.readFile(new URL('../../frontend/'+name,import.meta.url),'utf8'));
const wait=async predicate=>{for(let i=0;i<200;i++){if(predicate())return;await new Promise(r=>setTimeout(r,5));}throw Error('UI timeout '+root.textContent.slice(-400));};
const ready=()=>wait(()=>root.getAttribute('aria-busy')==='false');
const button=(text,host=d)=>[...host.querySelectorAll('button')].find(n=>n.textContent.trim()===text);
const card=title=>[...root.querySelectorAll('[data-update-key]')].find(n=>n.dataset.updateKey===title);
const metric=key=>root.querySelector(`[data-ad-metric="${key}"] strong`).textContent;
const select=async(label,value)=>{const n=d.querySelector(`[aria-label="${label}"]`);assert(n,label);n.value=value;n.dispatchEvent(new w.Event('change'));await ready();};
const click=async(text,host=d)=>{const n=button(text,host);assert(n,text);n.click();await ready();};
try{
  w.FinanceWorkspace.enter('overview');await ready();
  await select('开始日期','2026-10-01');await select('结束日期','2026-10-31');
  assert.equal(root.querySelector('[data-metric="AD"] strong').textContent,'17.5');
  assert.equal(root.querySelector('[data-metric="PROFIT"] strong').textContent,'369.5','displayed total advertising does not replace net-profit deductions');
  const grouped=card('分组经营明细'),stores=card('店铺经营明细');
  const tableCell=(section,rowLabel,column)=>{
    const headers=[...section.querySelectorAll('thead th')].map(n=>n.textContent);
    const index=headers.indexOf(column);assert(index>=0,'missing column '+column);
    const row=[...section.querySelectorAll('tbody tr')].find(n=>n.cells[0]?.textContent.includes(rowLabel));
    assert(row,'missing row '+rowLabel);return row.cells[index].textContent;
  };
  assert.equal(tableCell(stores,'演示菲律宾店','GMV Max 报表消耗（待核对）'),'99.9');
  assert.equal(tableCell(grouped,'母婴组','GMV Max 报表消耗（待核对）'),'99.9');
  assert.equal(tableCell(stores,'演示泰国店','GMV Max 报表消耗（待核对）'),'暂无记录');
  assert(root.querySelector('[data-metric="AD"]').textContent.includes('GMV Max 报表：99.9'));
  assert([...grouped.querySelectorAll('tbody tr')].some(n=>n.textContent.includes('服装组')&&n.textContent.includes('4')));
  assert([...stores.querySelectorAll('tbody tr')].some(n=>n.textContent.includes('演示泰国店')&&n.textContent.includes('4')),'shops with advertising only must remain visible');
  assert(stores.textContent.includes('未分配店铺'));
  assert(root.textContent.includes('Shopee 当前已接入结算账单中的广告费'));
  await select('原币','PHP');
  assert(!root.textContent.includes('Shopee 当前已接入结算账单中的广告费'),'a currency outside Shopee must not carry its source-availability notice');
  await select('原币','');
  fixture.groups.push({id:'gmv-only',name:'仅专项组'});
  fixture.shops.push({id:'c',name:'仅专项店',platform:'TIKTOK_SHOP',currency:'PHP',group_id:'gmv-only'});
  fixture.facts.push({id:'gmv-only',kind:'AD_GMV',shop_id:'c',group_id:'gmv-only',platform:'TIKTOK_SHOP',currency:'PHP',amount:100,record_date:'2026-10-10',source:'API'});
  await click('刷新');
  assert.equal(tableCell(card('店铺经营明细'),'仅专项店','GMV Max 报表消耗（待核对）'),'10');
  assert.equal(tableCell(card('店铺经营明细'),'仅专项店','广告费用（已入账）'),'暂无记录');
  assert.equal(tableCell(card('分组经营明细'),'仅专项组','GMV Max 报表消耗（待核对）'),'10');
  assert.equal(root.querySelector('[data-metric="AD"] strong').textContent,'17.5','GMV is visible without being added to posted advertising');
  assert.equal(root.querySelector('[data-metric="PROFIT"] strong').textContent,'369.5');
  fixture.facts.pop();fixture.shops.pop();fixture.groups.pop();await click('刷新');
  w.FinanceWorkspace.enter('ads');await ready();
  assert(root.querySelector('.fi-calendar-note').textContent.includes('独立广告按广告账户报表日期'));
  assert.equal(metric('total'),'17.5');assert.equal(metric('settlement'),'7');assert.equal(metric('independent'),'10.5');assert.equal(metric('unassigned'),'5');
  assert(card('投放概览').textContent.includes('10%'));assert(card('GMV Max 专项分析').textContent.includes('99.9'));
  assert(!button('导入账单'));assert(!button('复制广告授权链接'));
  await click('普通独立广告消耗明细');const before=metric('total');await click('下一页');
  assert.equal(reads.filter(x=>x.endpoint==='/workspace/query').at(-1).q.page,'2');assert.equal(metric('total'),before);
  await click('平台账单广告费明细');
  let query=reads.filter(x=>x.endpoint==='/workspace/query').at(-1).q;assert.equal(query.kind,'FEE');assert.equal(query.page,'1');
  const search=d.querySelector('.fi-number-search textarea');search.value='ledger-a';search.closest('form').dispatchEvent(new w.Event('submit',{cancelable:true}));await ready();
  assert.equal(reads.filter(x=>x.endpoint==='/workspace/query').at(-1).q.numbers,'ledger-a');assert.equal(metric('total'),'17.5');
  await click('导出 Excel',card('平台账单广告费明细'));const dialog=d.querySelector('dialog');
  button('下载报表',dialog).click();await wait(()=>exports.length===1&&!dialog.isConnected);
  query=Object.fromEntries(exports[0].searchParams);assert.equal(query.view,'FEE');assert.equal(query.advertisingOnly,'true');assert.equal(query.numbers,'ledger-a');assert(!('page' in query));
  assert.equal(query.start,'2026-10-01');assert.equal(query.end,'2026-10-31');assert.equal(query.fxSnapshot,'test-fixed');
  await click('GMV Max 分析明细');query=reads.filter(x=>x.endpoint==='/workspace/query').at(-1).q;assert.equal(query.kind,'AD_GMV');assert.equal(metric('total'),'17.5');
  await select('店铺分组','clothes');assert.equal(metric('total'),'4');assert.equal(metric('independent'),'—');
  assert(root.querySelector('[data-ad-metric="independent"]').textContent.includes('暂无记录'));
  assert(!card('广告数据同步').textContent.includes('演示广告账户'));
  await select('店铺分组','');await select('店铺','b');assert.equal(metric('total'),'4');
  await click('原币',d.querySelector('.fi-pills'));assert.equal(metric('total'),'20');
  await click('人民币 ¥',d.querySelector('.fi-pills'));await select('店铺','');assert.equal(metric('total'),'17.5');
  await select('结束日期','2026-10-09');assert.equal(metric('total'),'5.5');
  await select('结束日期','2026-10-31');
  partial=true;await click('刷新');assert.equal(metric('total'),'17.4');assert(root.querySelector('[data-ad-metric="total"]').textContent.includes('已知部分'));
  assert(card('投放概览').textContent.includes('已知部分'));assert(!card('投放概览').textContent.includes('NaN'));partial=false;
  failAccounts=true;await click('刷新');assert.equal(metric('total'),'17.5');assert(root.textContent.includes('广告账户状态暂时读取失败'));failAccounts=false;
  heldShop='a';const shop=d.querySelector('[aria-label="店铺"]');shop.value='a';shop.dispatchEvent(new w.Event('change'));await wait(()=>!!releaseHeld);
  shop.value='b';shop.dispatchEvent(new w.Event('change'));await ready();assert.equal(metric('total'),'4');releaseHeld();await new Promise(r=>setTimeout(r,20));assert.equal(metric('total'),'4','late responses cannot restore another shop');heldShop='';
  await select('店铺','');await select('开始日期','2026-09-01');await select('结束日期','2026-09-30');
  assert.equal(metric('total'),'—');assert(root.textContent.includes('当前筛选暂无已同步或导入的广告费用'));assert(!root.textContent.includes('Infinity'));
  fixture.facts.push({id:'zero',kind:'AD',shop_id:'b',group_id:'clothes',platform:'SHOPEE',currency:'THB',amount:0,record_date:'2026-09-15',category:'独立广告消耗',source:'IMPORT'});
  fixture.accounts.length=0;await click('刷新');assert.equal(metric('total'),'0','an actual zero record remains zero');
  assert(root.textContent.includes('当前公司尚未连接广告账户'));assert(!root.textContent.includes('当前筛选暂无已同步或导入的广告费用'));
  fixture.facts.push(
    {id:'commission-a',kind:'FEE',shop_id:'a',group_id:'baby',platform:'TIKTOK_SHOP',currency:'PHP',amount:30,record_date:'2026-10-10',category:'达人广告佣金',source:'API'},
    {id:'commission-refund',kind:'FEE',shop_id:'a',group_id:'baby',platform:'TIKTOK_SHOP',currency:'PHP',amount:-5,record_date:'2026-10-10',category:'达人广告佣金',source:'API'},
    {id:'commission-b',kind:'FEE',shop_id:'b',group_id:'clothes',platform:'SHOPEE',currency:'THB',amount:20,record_date:'2026-10-11',category:'合作伙伴店铺广告佣金',source:'API'});
  await select('结束日期','2026-10-31');await select('开始日期','2026-10-01');
  assert.equal(metric('total'),'24');assert.equal(metric('settlement'),'13.5');assert.equal(metric('independent'),'10.5');
  assert(root.querySelector('[data-ad-metric="settlement"]').textContent.includes('其中广告佣金：6.5'));
  assert(card('广告费用构成').textContent.includes('达人广告佣金'));assert(card('广告费用构成').textContent.includes('合作伙伴店铺广告佣金'));
  assert(card('店铺广告费用（已入账）').textContent.includes('其中广告佣金'));assert(card('分组广告费用（已入账）').textContent.includes('其中广告佣金'));
  w.FinanceWorkspace.enter('overview');await ready();
  assert.equal(root.querySelector('[data-metric="AD"] strong').textContent,'24');
  assert(root.querySelector('[data-metric="AD"]').textContent.includes('其中广告佣金：6.5'));
  assert.equal(root.querySelector('[data-metric="PROFIT"] strong').textContent,'369.5');
  assert(card('店铺经营明细').textContent.includes('其中广告佣金'));
  const allFacts=[...fixture.facts];
  fixture.facts.splice(0,fixture.facts.length,...allFacts.filter(row=>row.kind!=='AD'));
  await click('刷新');
  root.querySelector('[data-metric="AD"]').click();assert.equal(w.location.hash,'#finance-ads');
  w.FinanceWorkspace.enter('ads');await ready();
  assert.equal(reads.filter(x=>x.endpoint==='/workspace/query').at(-1).q.kind,'FEE','a ledger-funded ad card must open its populated source instead of an empty ordinary report');
  fixture.facts.splice(0,fixture.facts.length,...allFacts.filter(row=>row.kind==='AD_GMV'));
  w.FinanceWorkspace.enter('overview');await ready();
  root.querySelector('[data-metric="AD"]').click();w.FinanceWorkspace.enter('ads');await ready();
  assert.equal(reads.filter(x=>x.endpoint==='/workspace/query').at(-1).q.kind,'AD_GMV','a GMV-only card must open its separate report without changing booked totals');
  fixture.facts.splice(0,fixture.facts.length,...allFacts);
  w.FinanceWorkspace.enter('overview');await ready();
  await select('开始日期','2026-08-01');await select('结束日期','2026-08-31');
  assert(card('店铺经营明细').textContent.includes('暂无记录'));assert(card('分组经营明细').textContent.includes('暂无记录'));
  if(process.env.FINANCE_ADS_PREVIEW_DIR){
    const dir=process.env.FINANCE_ADS_PREVIEW_DIR;await fs.mkdir(dir,{recursive:true});
    await fs.writeFile(dir+'/fixture.js','window.advertisingFixture=('+createAdvertisingFixture.toString()+')();');
  }
  console.log('PASS advertising UI: all costs, categories/shops, currencies, date ranges, GMV isolation, paging, exact search, export scope, partial/zero data, viewer access, account errors and stale responses.');
}finally{w.FinanceWorkspace.reset();w.close();}
