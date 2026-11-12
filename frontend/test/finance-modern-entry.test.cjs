const test = require('node:test');
const assert = require('node:assert/strict');
const { createRequire } = require('node:module');
const path = require('node:path');
const fs = require('node:fs');
const vm = require('node:vm');
const requireQa = createRequire(path.join(__dirname, '../../scripts/ci/qa/package.json'));
const { JSDOM } = requireQa('jsdom');

function entry() { return require('../finance-entry.js'); }
function fixture(mode = 'legacy', overrides = {}) {
  const dom = new JSDOM('<main id="finance-page"></main>', {url: `https://example.test/?financeUi=${mode}#finance-overview`});
  const container = dom.window.document.querySelector('main');
  const snapshot = {organizationId:'company-a',userId:'user-a',canView:true,canManage:true,theme:'light',locale:'zh-CN'};
  let legacyLoads = 0, modernLoads = 0, mounts = 0, disposes = 0;
  const host = {getSnapshot:()=>snapshot,subscribe:()=>()=>{},navigate(){},onSessionExpired(){}};
  const legacy = {allowed:()=>true,canManage:()=>true,enter(route){container.textContent=`classic ${route}`;},leave(){},reset(){container.replaceChildren();}};
  const { createFacade } = entry();
  const facade = createFacade({
    container, location:dom.window.location, host,
    loadLegacy:async()=>{legacyLoads++;return legacy;},
    loadModern:async()=>{modernLoads++;return {mountFinanceRoot(node, options){
      mounts++;
      const paint = next=>{node.textContent=`modern ${next.route}`;node.hidden=!next.active;};
      paint(options);
      return {render:paint,dispose(){disposes++;node.replaceChildren();}};
    }};}, ...overrides
  });
  return {dom,container,host,facade,counts:()=>({legacyLoads,modernLoads,mounts,disposes})};
}

test('only the exact explicit modern URL opts in; default and invalid modes stay classic',()=>{
  const { modeFromSearch } = entry();
  for (const search of ['', '?x=modern', '?financeUi=legacy', '?financeUi=Modern', '?financeUi=unknown']) assert.equal(modeFromSearch(search),'legacy');
  assert.equal(modeFromSearch('?lang=zh&financeUi=modern'),'modern');
});

test('new entry carries validated scope and never forwards credentials or changes the original location',()=>{
  const { buildFinanceUrl } = entry();
  const current = new URL('https://example.test/app?lang=ms&token=private&state=private#finance-costs');
  const before=current.href;
  const href=buildFinanceUrl(current,{start:'2026-10-01',end:'2026-10-31',shopId:'shop-a',groupId:'UNGROUPED',platform:'TIKTOK_SHOP',currency:'USD',displayCurrency:'CNY',fxSnapshot:'private',password:'private'},'modern');
  assert.equal(href,'/app?lang=ms&financeUi=modern&start=2026-10-01&end=2026-10-31&shopId=shop-a&groupId=UNGROUPED&platform=TIKTOK_SHOP&currency=USD&displayCurrency=CNY#finance-overview');
  assert.equal(current.href,before);
  const invalid=new URL(buildFinanceUrl(current,{start:'2026-02-30',end:'bad',platform:'UNKNOWN',currency:'usd',displayCurrency:'wrong'},'modern'),'https://example.test');
  assert.equal(invalid.searchParams.has('start'),false);
  assert.equal(invalid.searchParams.has('end'),false);
  assert.equal(invalid.searchParams.has('platform'),false);
  assert.equal(invalid.searchParams.has('currency'),false);
  assert.equal(invalid.searchParams.has('displayCurrency'),false);
});

test('experience link opens an isolated tab and remains unique while its selection updates',()=>{
  const { addExperienceLink }=entry();
  const dom=new JSDOM('<header></header>',{url:'https://example.test/#finance-overview'}),parent=dom.window.document.querySelector('header');
  addExperienceLink(parent,dom.window.location,{shopId:'first'},true);
  addExperienceLink(parent,dom.window.location,{shopId:'second'},true);
  const links=parent.querySelectorAll('[data-finance-modern-entry]');
  assert.equal(links.length,1);assert.equal(links[0].target,'_blank');
  assert(links[0].rel.includes('noopener'));assert(links[0].rel.includes('noreferrer'));
  assert(links[0].textContent.includes('体验新版财务 BI'));
  assert.equal(new URL(links[0].href).searchParams.get('shopId'),'second');
  const hidden=dom.window.document.createElement('header');
  addExperienceLink(hidden,dom.window.location,{},false);assert.equal(hidden.children.length,0);
  dom.window.close();
});

test('classic default renders classic finance without loading the modern runtime',async()=>{
  const f=fixture('legacy');await f.facade.enter('overview');
  assert.equal(f.container.textContent,'classic overview');
  assert.deepEqual(f.counts(),{legacyLoads:1,modernLoads:0,mounts:0,disposes:0});
  f.facade.dispose();f.dom.window.close();
});

test('modern owns a disposable child rather than attaching a shadow root to the classic section',async()=>{
  const f=fixture('modern');await f.facade.enter('overview');await f.facade.enter('ads');
  assert.equal(f.container.shadowRoot,null);
  assert.equal(f.container.querySelectorAll('[data-finance-modern-host]').length,1);
  assert.equal(f.container.textContent,'modern ads');
  assert.deepEqual(f.counts(),{legacyLoads:0,modernLoads:1,mounts:1,disposes:0});
  f.facade.leave();assert.equal(f.container.firstElementChild.hidden,true);
  await f.facade.enter('orders');assert.equal(f.container.firstElementChild.hidden,false);
  f.facade.reset();assert.equal(f.container.children.length,0);assert.equal(f.counts().disposes,1);
  f.facade.dispose();f.dom.window.close();
});

test('late modern bootstrap after leaving cannot remount an inactive financial view',async()=>{
  let release;
  const loaded=new Promise(resolve=>{release=resolve;});
  const f=fixture('modern',{loadModern:()=>loaded});
  const pending=f.facade.enter('overview');f.facade.leave();
  release({mountFinanceRoot(){throw Error('stale mount must not happen');}});
  await pending;assert.equal(f.container.children.length,0);
  f.facade.dispose();f.dom.window.close();
});

test('failed modern loading shows a safe classic fallback and never executes both renderers',async()=>{
  const f=fixture('modern',{loadModern:async()=>{throw Error('token=private internal failure');}});
  await f.facade.enter('overview');
  assert(f.container.querySelector('[role="alert"]'));
  assert.equal(f.counts().legacyLoads,0);assert(!f.container.textContent.includes('private'));
  const link=f.container.querySelector('a');assert(link);
  assert.equal(new URL(link.href).searchParams.get('financeUi'),'legacy');
  f.facade.dispose();f.dom.window.close();
});

test('a reader without financial access cannot trigger either renderer',async()=>{
  const f=fixture('modern');f.host.getSnapshot().canView=false;
  assert.equal(f.facade.allowed(),false);await f.facade.enter('overview');
  assert.deepEqual(f.counts(),{legacyLoads:0,modernLoads:0,mounts:0,disposes:0});
  f.facade.dispose();f.dom.window.close();
});

test('modern Qianyi after-sales remains reachable through the shared portal facade',async()=>{
  const f=fixture('modern');
  await f.facade.enter('qianyi');
  assert.equal(f.container.textContent,'modern qianyi');
  assert.equal(f.counts().legacyLoads,0);
  f.facade.dispose();f.dom.window.close();
});

function sanitizedBootstrap(search,hash='#finance-overview') {
  const source=fs.readFileSync(path.join(__dirname,'../index.html'),'utf8').match(/<script>([\s\S]*?)<\/script>/)[1];
  let replaced;
  const history={replaceState(_state,_title,url){replaced=url;}};
  const window={location:{search,pathname:'/app',hash},history};
  vm.runInNewContext(source,{window,history,URLSearchParams,Date,Set,localStorage:{getItem:()=>null},document:{title:'',documentElement:{dataset:{},style:{}}}});
  return {url:replaced,notice:window.HoyeeFinanceAuthNotice};
}

test('early URL cleanup preserves explicit finance mode and valid scope while removing secrets',()=>{
  assert.equal(sanitizedBootstrap('?lang=en&financeUi=modern&start=2026-10-01&end=2026-10-31&shopId=shop-a&token=secret').url,
    '/app?lang=en&financeUi=modern&start=2026-10-01&end=2026-10-31&shopId=shop-a#finance-overview');
  assert.equal(sanitizedBootstrap('?financeUi=legacy&auth_code=secret&state=secret').url,'/app?financeUi=legacy#finance-overview');
  assert.equal(sanitizedBootstrap('?financeUi=wrong&shopId=shop-a&password=secret').url,'/app#finance-overview');
  assert.equal(sanitizedBootstrap('?financeUi=modern&start=2026-02-30&groupId=UNGROUPED&access_token=secret').url,
    '/app?financeUi=modern&groupId=UNGROUPED#finance-overview');
});

test('OAuth result is handed off in memory and credentials never survive in the URL or notice',()=>{
  const result=sanitizedBootstrap('?financeAuth=success&financeMessage=Authorization%20completed&auth_code=secret&state=secret','#finance-shops');
  assert.equal(result.url,'/app#finance-shops');
  assert.equal(result.notice.status,'success');assert.equal(result.notice.message,'Authorization completed');
  const unsafe=sanitizedBootstrap('?financeAuth=error&financeMessage=access_token%3Dsecret','#finance-shops');
  assert(!JSON.stringify(unsafe.notice).includes('secret'));
  assert.equal(sanitizedBootstrap('?view=finance&token=secret','').url,'/app#finance-shops');
});

test('OAuth notices reject JSON and camel-case credential fields as well as query-shaped secrets',()=>{
  for (const message of ['{"access_token":"private-canary"}','appSecret: private-canary','refreshToken private-canary','Cookie private-canary']) {
    const result=sanitizedBootstrap('?financeAuth=error&financeMessage='+encodeURIComponent(message),'#finance-shops');
    assert.equal(result.url,'/app#finance-shops');
    assert(!JSON.stringify(result.notice).includes('private-canary'));
  }
});

test('OAuth notices replace structured and camel-case state values with the generic fallback',()=>{
  for (const message of [
    '{"state":"private-canary"}',
    '{ "state" : "private-canary" }',
    'oauthState: private-canary',
    'oauth_state = private-canary',
    '{"oauthState":"private-canary"}'
  ]) {
    const result=sanitizedBootstrap('?financeAuth=error&financeMessage='+encodeURIComponent(message),'#finance-shops');
    assert.equal(result.url,'/app#finance-shops');
    assert.equal(result.notice.status,'error');
    assert.equal(result.notice.message,'财务授权未完成，请查看账户连接状态');
    assert(!JSON.stringify(result.notice).includes('private-canary'));
  }
});
