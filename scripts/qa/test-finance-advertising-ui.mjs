import fs from 'node:fs/promises';
import assert from 'node:assert/strict';
import {createAdvertisingFixture} from './fixtures/finance-advertising.mjs';
const {JSDOM}=await import(process.env.JSDOM_MODULE||'jsdom');
const dom=new JSDOM('<main id="finance-page"></main>',{url:'https://example.test/#finance-overview',runScripts:'outside-only',pretendToBeVisual:true});
const w=dom.window,d=w.document,root=d.querySelector('main'),fixture=createAdvertisingFixture();
let partial=false;
w.currentUser={organizationId:'qa',userId:'reader',permissions:['finance:view']};
w.matchMedia=()=>({matches:false});w.ResizeObserver=class{observe(){}disconnect(){}};
w.echarts={init:()=>({setOption(){},resize(){},dispose(){}})};
w.api=async path=>{
  const u=new URL(path,'https://example.test');
  assert.equal(u.pathname,'/api/v1/finance/overview');
  return {data:fixture.overview(Object.fromEntries(u.searchParams),partial)};
};
for(const name of ['incremental-dom.js','incremental-charts.js','finance-workspace.js'])w.eval(await fs.readFile(new URL('../../frontend/'+name,import.meta.url),'utf8'));
const wait=async predicate=>{for(let i=0;i<200;i++){if(predicate())return;await new Promise(r=>setTimeout(r,5));}throw Error('UI timeout '+root.textContent.slice(-400));};
const ready=()=>wait(()=>root.getAttribute('aria-busy')==='false');
const button=(text,host=d)=>[...host.querySelectorAll('button')].find(n=>n.textContent.trim()===text);
const card=title=>[...root.querySelectorAll('[data-update-key]')].find(n=>n.dataset.updateKey===title);
const metric=key=>root.querySelector(`[data-metric="${key}"] strong`).textContent;
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
  await select('店铺分组','clothes');
  assert.equal(metric('AD'),'4');
  await select('店铺分组','');await select('店铺','b');assert.equal(metric('AD'),'4');
  await click('原币',d.querySelector('.fi-pills'));assert.equal(metric('AD'),'20');
  await click('人民币 ¥',d.querySelector('.fi-pills'));await select('店铺','');assert.equal(metric('AD'),'17.5');
  await select('结束日期','2026-10-09');assert.equal(metric('AD'),'5.5');
  await select('结束日期','2026-10-31');
  partial=true;await click('刷新');
  assert.equal(metric('AD'),'17.4');
  assert(root.querySelector('[data-metric="AD"]').textContent.includes('已知部分'));
  partial=false;
  fixture.facts.push(
    {id:'commission-a',kind:'FEE',shop_id:'a',group_id:'baby',platform:'TIKTOK_SHOP',currency:'PHP',amount:30,record_date:'2026-10-10',category:'达人广告佣金',source:'API'},
    {id:'commission-refund',kind:'FEE',shop_id:'a',group_id:'baby',platform:'TIKTOK_SHOP',currency:'PHP',amount:-5,record_date:'2026-10-10',category:'达人广告佣金',source:'API'},
    {id:'commission-b',kind:'FEE',shop_id:'b',group_id:'clothes',platform:'SHOPEE',currency:'THB',amount:20,record_date:'2026-10-11',category:'合作伙伴店铺广告佣金',source:'API'});
  await click('刷新');
  assert.equal(metric('AD'),'24');
  assert(root.querySelector('[data-metric="AD"]').textContent.includes('其中广告佣金：6.5'));
  assert.equal(metric('PROFIT'),'369.5');
  assert(card('店铺经营明细').textContent.includes('其中广告佣金'));
  root.querySelector('[data-metric="AD"]').click();
  assert.equal(w.location.hash,'#finance-ads');
  await select('开始日期','2026-08-01');await select('结束日期','2026-08-31');
  assert(card('店铺经营明细').textContent.includes('暂无记录'));assert(card('分组经营明细').textContent.includes('暂无记录'));
  if(process.env.FINANCE_ADS_PREVIEW_DIR){
    const dir=process.env.FINANCE_ADS_PREVIEW_DIR;await fs.mkdir(dir,{recursive:true});
    await fs.writeFile(dir+'/fixture.js','window.advertisingFixture=('+createAdvertisingFixture.toString()+')();');
  }
  console.log('PASS advertising overview: posted costs, commissions, profit, date and shop scope, GMV isolation and partial amounts.');
}finally{w.FinanceWorkspace.reset();w.close();}
