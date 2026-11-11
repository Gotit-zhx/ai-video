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
fixture.shops.push({id:'c',name:'未关联菲律宾店',platform:'TIKTOK_SHOP',currency:'PHP',timezone:'Asia/Manila',group_id:'baby'});
fixture.accounts[0]={advertiser_id:'100001',name:'跨店账户',shop_id:'',currency:'PHP',timezone:'Asia/Manila',enabled:true,status:'PARTIAL',discovery_status:'SUCCESS',requestedDays:31,
  stores:[{store_id:'700001',store_name:'平台店 A',shop_id:'a',match_status:'MATCHED',report_eligible:true,active:true,gmv_available:false},
    {store_id:'700002',store_name:'平台店 B',shop_id:'b',match_status:'MATCHED',report_eligible:true,active:true},
    {store_id:'700003',store_name:'未连接财务店',shop_id:'',match_status:'UNMATCHED',report_eligible:true,active:true}],
  streams:[{channel:'BASIC',store_id:'',status:'SUCCESS'},{channel:'GMV',store_id:'700001',status:'SUCCESS'},
    {channel:'GMV',store_id:'700002',status:'ERROR',error_message:'读取权限不足，自动重试'}],
  coverage:[{channel:'BASIC',store_id:'',covered_days:31,records:0,amount:0},{channel:'GMV',store_id:'700001',covered_days:31,records:2,amount:0},
    {channel:'GMV',store_id:'700002',covered_days:7,records:0,amount:0}]};
fixture.accounts.push({advertiser_id:'100002',name:'返回数未知账户',shop_id:'',currency:'PHP',timezone:'Asia/Manila',enabled:false,status:'SUCCESS',discovery_status:'SUCCESS',requestedDays:31,
  stores:[{store_id:'700001',store_name:'平台店 A',shop_id:'a',match_status:'MATCHED',report_eligible:true,active:true,gmv_available:false},
    {store_id:'700002',store_name:'平台店 B',shop_id:'b',match_status:'MATCHED',report_eligible:true,active:true}],
  streams:[{channel:'BASIC',store_id:'',status:'SUCCESS'}],coverage:[{channel:'BASIC',store_id:'',covered_days:31,amount:0}]});
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
const failures=[];
const check=(name,run)=>{try{run();console.log('PASS '+name);}catch(error){failures.push(error);console.error('FAIL '+name+': '+error.message);}};
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
  assert(card('店铺广告费用（已入账）').textContent.includes('GMV Max 报表消耗（待核对）'));
  assert(card('店铺广告费用（已入账）').textContent.includes('99.9'),'GMV source cost is visible without adding it to posted fees');
  const health=card('广告数据同步');assert(health.textContent.includes('31 / 31 天'));assert(health.textContent.includes('7 / 31 天'));
  const coverageTable=[...health.querySelectorAll('table')].find(t=>t.textContent.includes('日期覆盖'));
  check('coverage exposes returned-row provenance',()=>{assert(coverageTable.querySelector('thead').textContent.includes('返回记录数'),'coverage exposes the returned-row count separately from covered dates');assert(health.textContent.includes('来源于接口返回的报表行'),'row-count provenance is explained');});
  const coverageRows=[...coverageTable.querySelectorAll('tbody tr')];
  const basic=coverageRows.find(r=>r.cells[0].textContent==='跨店账户'&&r.cells[1].textContent.includes('普通广告'));
  check('complete empty reports stay distinct from zero spend',()=>{assert(basic.textContent.includes('接口未返回记录'),'a complete empty response is not asserted to be zero spend');
  assert.equal(basic.cells[5].textContent,'0');assert.equal(basic.cells[6].textContent,'—','empty reports do not display fabricated monetary zero');
  assert(basic.cells[1].textContent.includes('不含部分 Shop 创建的 GMV Max'),'BASIC coverage is not presented as all-media spend');});
  const netZero=coverageRows.find(r=>r.cells[0].textContent==='跨店账户'&&r.cells[1].textContent.includes('演示菲律宾店'));
  check('returned rows can have a net-zero total',()=>{assert(netZero.textContent.includes('报表合计为0'),'returned rows with a net-zero sum remain distinct from an empty response');
  assert.equal(netZero.cells[5].textContent,'2');assert.equal(netZero.cells[6].textContent,'0 PHP');});
  const unknown=coverageRows.find(r=>r.cells[0].textContent==='返回数未知账户');
  check('unknown row counts never imply an empty response',()=>{assert(unknown.textContent.includes('返回记录数未核实'),'omitted row counts must not be coerced to empty');
  assert(!unknown.textContent.includes('接口未返回记录'));assert.equal(unknown.cells[5].textContent,'—');assert.equal(unknown.cells[6].textContent,'—');});
  const partial=coverageRows.find(r=>r.textContent.includes('7 / 31 天'));check('partial dates never claim complete coverage',()=>{assert(partial.textContent.includes('已采集部分'));assert(!partial.textContent.includes('已采集范围内报表'));assert(!partial.textContent.includes('接口返回零消耗'));});
  check('store discovery counts unique matched stores within the filter',()=>{const scopeNote=root.querySelector('[data-finance-ad-store-scope]');assert(scopeNote,'current-filter store coverage is explained separately from financial facts');
  assert(scopeNote.textContent.includes('3 家 TikTok 财务店铺'));assert(scopeNote.textContent.includes('已关联 2 家'));assert(scopeNote.textContent.includes('1 家未在当前发现关系范围'),'duplicate account-store relationships count once');});
  const storeRows=[...card('店铺广告费用（已入账）').querySelectorAll('tbody tr')];
  const returned=storeRows.find(r=>r.cells[0].textContent==='演示菲律宾店');assert(returned.cells[5].textContent.includes('99.9'),'historical spend remains visible when gmv_available is false');
  check('GMV missing facts distinguish a matched store from an absent relationship',()=>{const pending=storeRows.find(r=>r.cells[0].textContent==='演示泰国店');assert(pending.cells[5].textContent.includes('已关联广告店铺'),'a linked store with no GMV facts is distinguishable');assert(pending.cells[5].textContent.includes('待返回或补齐'),'one empty account does not assert all associated accounts returned no records');
  const absent=storeRows.find(r=>r.cells[0].textContent==='未关联菲律宾店');assert(absent,'TikTok stores outside discovered relationships remain visible for coverage review');assert(absent.cells[5].textContent.includes('未关联广告店铺'));assert(absent.cells[5].textContent.includes('未在当前发现关系范围'),'complete discovery describes observed relationships, not missing permissions');assert(!absent.cells[5].textContent.includes('无权限'));});
  assert.deepEqual([...root.querySelectorAll('[data-ad-metric]')].map(n=>(n.cells[1].textContent+n.cells[2].textContent).replace(/\s/g,'')),['17.5CNY','7CNY','10.5CNY','5CNY'],'compact ledger checks preserve financial totals and keep GMV separate');
  check('paused accounts retain coverage and historical state',()=>{
    const accountRow=[...health.querySelector('table').querySelectorAll('tbody tr')].find(r=>r.cells[0].textContent==='返回数未知账户');
    assert(accountRow.cells[3].textContent.includes('已暂停'),'enabled=false overrides the old success status in the account display');
    assert(unknown.textContent.includes('31 / 31 天'),'pausing does not hide historical coverage');
  });
  fixture.accounts[1].coverage[0].amount=5;button('刷新').click();await ready();
  check('unknown row counts preserve known historical nonzero amounts',()=>{
    const row=[...card('广告数据同步').querySelectorAll('tbody tr')].find(r=>r.cells[0].textContent==='返回数未知账户'&&r.textContent.includes('31 / 31 天'));
    assert(row.textContent.includes('返回记录数未核实'));assert.equal(row.cells[6].textContent,'5 PHP');
  });
  const discovering={advertiser_id:'100003',name:'发现状态验证账户',shop_id:'',currency:'THB',timezone:'Asia/Bangkok',enabled:true,status:'READY',stores:[],streams:[],coverage:[]};
  fixture.accounts.push(discovering);
  for(const [discoveryStatus,message] of [['PENDING','店铺关系发现待完成'],['ERROR','店铺关系发现失败待重试'],[undefined,'店铺关系发现待完成'],['EMPTY','未在当前发现关系范围']]){
    if(discoveryStatus===undefined)delete discovering.discovery_status;else discovering.discovery_status=discoveryStatus;
    button('刷新').click();await ready();
    check('discovery '+(discoveryStatus||'missing')+' does not infer permissions from missing relationships',()=>{
      const scope=root.querySelector('[data-finance-ad-store-scope]').textContent;
      const rows=[...card('店铺广告费用（已入账）').querySelectorAll('tbody tr')];
      const absent=rows.find(row=>row.cells[0].textContent==='未关联菲律宾店').cells[5].textContent;
      assert(scope.includes(message),'scope explanation reflects discovery state');assert(absent.includes(message),'missing GMV explanation reflects discovery state');
      assert(!scope.includes('授权账户范围未覆盖'));assert(!absent.includes('授权账户范围未覆盖'));
      if(discoveryStatus!=='EMPTY'){assert(!scope.includes('1 家未在当前发现关系范围'));assert(!absent.includes('未关联广告店铺'));}
      assert(rows.find(row=>row.cells[0].textContent==='演示菲律宾店').cells[5].textContent.includes('99.9'),'discovery failures never hide linked historical spend');
    });
  }
  discovering.discovery_status='ERROR';await select('原币','PHP');
  check('discovery failures outside the selected account currency do not taint this scope',()=>{
    const scope=root.querySelector('[data-finance-ad-store-scope]').textContent;
    assert(scope.includes('1 家未在当前发现关系范围'));assert(!scope.includes('失败待重试'));
  });
  await select('原币','');fixture.accounts.pop();
  assert(button('异常补录'));assert(!button('导入账单'));
  await select('店铺分组','baby');assert(!card('广告数据同步').textContent.includes('7 / 31 天'),'other store coverage respects the selected group');
  check('store discovery narrows with the selected group',()=>{const narrowed=root.querySelector('[data-finance-ad-store-scope]');assert(narrowed,'filtered store-discovery scope is visible');assert(narrowed.textContent.includes('2 家 TikTok 财务店铺'));assert(narrowed.textContent.includes('已关联 1 家'));assert(narrowed.textContent.includes('1 家未在当前发现关系范围'));});
  await select('平台','SHOPEE');assert(!root.querySelector('[data-finance-ad-store-scope]')?.textContent.includes('未关联'),'TikTok discovery counts do not leak into a Shopee-only filter');
  assert(!requests.some(r=>r.options.method&&r.endpoint!=='/ads/authorize'),'analysis never changes platform advertising authorization');
  if(failures.length)throw new AggregateError(failures,'Advertising coverage UI checks failed');
  console.log('PASS advertising automation UI: automatic OAuth options, multiple matched stores, scoped coverage, empty vs net-zero vs unknown row counts, scoped store discovery, historical GMV costs and exceptional import.');
}finally{w.FinanceWorkspace.leave();w.close();}
