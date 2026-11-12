const test = require('node:test');
const assert = require('node:assert/strict');
const { createRequire } = require('node:module');
const path = require('node:path');
const requireQa = createRequire(path.join(__dirname, '../../scripts/ci/qa/package.json'));
const { JSDOM } = requireQa('jsdom');
const adapter = () => require('../finance-host.js');

test('host caches snapshots and propagates actual session, theme and language changes', async () => {
  const dom = new JSDOM('<html lang="zh-CN" data-theme="light"></html>', {url:'https://example.test/?financeUi=modern#finance-overview'});
  let user = {organizationId:'a',userId:'u',permissions:['finance:view']};
  let expired=0, changes=0;
  const host = adapter().createHost({window:dom.window,readUser:()=>user,expire:()=>expired++});
  const initial = host.getSnapshot();
  assert.strictEqual(initial,host.getSnapshot());
  assert.equal(initial.canView,true); assert.equal(initial.canManage,false);
  const off = host.subscribe(()=>changes++);
  dom.window.document.documentElement.dataset.theme='dark';
  await new Promise(resolve=>setTimeout(resolve,0));
  assert.equal(host.getSnapshot().theme,'dark');assert.equal(changes,1);
  user = {...user,organizationId:'b',roles:['ADMIN']};host.notify();
  assert.equal(host.getSnapshot().organizationId,'b');assert.equal(host.getSnapshot().canManage,true);
  dom.window.document.documentElement.lang='en';
  await new Promise(resolve=>setTimeout(resolve,0));
  assert.equal(host.getSnapshot().locale,'en');
  host.navigate('ads');assert.equal(dom.window.location.hash,'#finance-ads');
  host.onSessionExpired();assert.equal(expired,1);
  off();host.dispose();dom.window.close();
});

test('modern loader validates every asset before importing and reads isolated CSS', async () => {
  const calls=[],imports=[];
  const manifest={'src/app/build-entry.ts':{isEntry:true,file:'assets/entry-abcd.js'},'style.css':{file:'assets/style-abcd.css'}};
  const fetch=async (url,options)=>{calls.push({url:String(url),options});return {ok:true,json:async()=>manifest,text:async()=>'.finance{color:red}'};};
  const result=await adapter().loadModern({location:new URL('https://example.test/app'),fetch,importModule:async url=>{imports.push(url);return {mountFinanceRoot(){}};}});
  assert.equal(typeof result.mountFinanceRoot,'function');assert.equal(result.cssText,'.finance{color:red}');
  assert.deepEqual(imports,['https://example.test/assets/finance-modern/assets/entry-abcd.js']);
  assert(calls.every(call=>call.options.credentials==='same-origin'&&call.options.cache==='no-store'));
  for(const unsafe of ['https://other.test/evil.js','../evil.js','assets/../evil.js','assets/evil.js?token=secret']) {
    let imported=false;
    await assert.rejects(adapter().loadModern({location:new URL('https://example.test/app'),fetch:async()=>({ok:true,json:async()=>({...manifest,'src/app/build-entry.ts':{isEntry:true,file:unsafe}})}),importModule:async()=>{imported=true;return {};}}));
    assert.equal(imported,false);
  }
});

test('classic loader preserves script order and restores the facade after capturing the renderer', async()=>{
  const dom=new JSDOM('<main id="finance-page"></main><script data-finance-legacy-src="/first.js"></script><script data-finance-legacy-src="/second.js"></script>',{url:'https://example.test/'});
  const facade={enter(){}};dom.window.FinanceWorkspace=facade;
  const legacy={enter(){},leave(){},reset(){}};
  const order=[];
  const renderer=await adapter().loadLegacy({window:dom.window,facade,loadScript:async src=>{order.push(src);if(src==='/second.js')dom.window.FinanceWorkspace=legacy;}});
  assert.deepEqual(order,['/first.js','/second.js']);
  assert.strictEqual(renderer,legacy);assert.strictEqual(dom.window.FinanceWorkspace,facade);
  dom.window.close();
});

test('browser installation is lazy and company reset invalidates the active renderer',async()=>{
  const dom=new JSDOM('<main id="finance-page"></main>',{url:'https://example.test/#finance-overview'});
  dom.window.FinanceEntry=require('../finance-entry.js');
  let loads=0,resets=0;
  const installed=adapter().install({window:dom.window,readUser:()=>({organizationId:'a',userId:'u',permissions:['finance:view']}),expire(){},loadLegacy:async()=>{loads++;return {enter(){},leave(){},reset(){resets++;}};},loadModern:async()=>{throw Error('classic must not load modern');}});
  assert.equal(loads,0);assert.equal(dom.window.FinanceWorkspace.allowed(),true);
  await dom.window.FinanceWorkspace.enter('overview');assert.equal(loads,1);
  dom.window.dispatchEvent(new dom.window.Event('heyi:company-reset'));assert.equal(resets,1);
  installed.dispose();dom.window.close();
});

test('host carries a safe legacy return URL and consumes callback notices once',()=>{
  const dom=new JSDOM('<html lang="zh-CN"></html>',{url:'https://example.test/app?lang=en&financeUi=modern&token=private#finance-ads'});
  dom.window.FinanceEntry=require('../finance-entry.js');
  dom.window.HoyeeFinanceAuthNotice={status:'error',message:'未取得可用广告账户'};
  const host=adapter().createHost({window:dom.window,readUser:()=>null,expire(){}});
  const href=host.getLegacyUrl({shopId:'shop-a',start:'2026-10-01',end:'2026-10-31'});
  assert.equal(href,'/app?lang=en&financeUi=legacy&start=2026-10-01&end=2026-10-31&shopId=shop-a#finance-overview');
  assert.deepEqual(host.consumeAuthorizationNotice(),{status:'error',message:'未取得可用广告账户'});
  assert.equal(host.consumeAuthorizationNotice(),null);
  assert.equal(dom.window.HoyeeFinanceAuthNotice,undefined);
  host.dispose();dom.window.close();
});
