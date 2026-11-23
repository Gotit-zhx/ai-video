const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const html=fs.readFileSync(path.join(__dirname,'../index.html'),'utf8');
const app=fs.readFileSync(path.join(__dirname,'../app.js'),'utf8');
test('shop authorization panel has one independent page and no duplicate DOM ids',()=>{
  const start=html.indexOf('id="shop-management-page"');
  const panel=html.indexOf('id="marketplace-shop-panel"');
  const products=html.indexOf('id="product-publish-page"');
  assert.ok(start<panel && panel<products);
  for(const id of ['nav-shops','nav-shops-v2','shop-management-page','marketplace-shop-panel','marketplace-shop-list'])
    assert.equal(html.split(`id="${id}"`).length-1,1);
  assert.match(html, /<a[^>]*href="#shops"[^>]*id="nav-shops"/);
  assert.match(html, /data-nav-group="commerce-v2"(?:(?!<\/details>)[\s\S])*href="#shops-v2" id="nav-shops-v2"/);
});
test('shop navigation and OAuth return retain permission checks',()=>{
  assert.match(app, /querySelectorAll\("#nav-shops, #nav-shops-v2"\)\.forEach\(link => \{\s*link\.hidden = !hasAnyPermission\("product:view", "product:publish"\)/);
  assert.match(app, /returnPath: marketplaceShopRoute\(\) === "shops-v2" \? "\/\?view=shops-v2" : "\/\?view=shops"/);
  assert.match(app, /const isShops = Boolean\(marketplaceShopRoute\(page\)\)/);
  assert.match(app, /shop-management-page"\)\.hidden = !isShops/);
  assert.match(app, /if \(shopPage && currentUser && hasAnyPermission/);
});
test('OAuth URL cleanup keeps only the intended shop route and safe notice fields',()=>{
  const vm=require('node:vm');
  const bootstrap=html.match(/<script>([\s\S]*?)<\/script>/)[1];
  for (const [query,hash,status,connected] of [
    ['?view=shops-v2&shopAuth=success&platform=SHOPEE&connected=2&code=do-not-keep', '#shops-v2', 'success', 2],
    ['?shopAuth=error&platform=TIKTOK_SHOP&message=do-not-keep&state=do-not-keep', '#shops', 'error', null]
  ]) {
    let cleaned;
    const window={location:{search:query,hash:'',pathname:'/'},history:{replaceState:(_state,_title,url)=>{cleaned=url;}}};
    const ctx={URLSearchParams, window, document:{title:'test'}, Date};
    const cleanup=bootstrap.slice(0,bootstrap.indexOf('// Resolve the preferred theme'));
    vm.runInNewContext(cleanup, ctx);
    assert.equal(cleaned,'/'+hash);
    assert.equal(window.HoyeeShopAuthNotice.status,status);
    assert.equal(window.HoyeeShopAuthNotice.connected,connected);
    assert.doesNotMatch(JSON.stringify(window.HoyeeShopAuthNotice),/do-not-keep/);
  }
});

test('company reset is propagated to auxiliary workspaces',()=>{
  assert.match(app,/dispatchEvent\(new Event\("heyi:company-reset"\)\)/);
  for(const file of ['commerce-planner.js','commerce-batches.js']){
    const code=fs.readFileSync(path.join(__dirname,'../'+file),'utf8');
    assert.ok(code.includes('"heyi:company-reset"'));
    assert.ok(code.includes('form.reset()'));
  }
});

test('TikTok authorization sends selected market and prefers the saved shop market on reconnect',async()=>{
  const vm=require('node:vm');
  const source=app.slice(app.indexOf('async function startMarketplaceAuthorization('),app.indexOf('\nfunction consumeMarketplaceAuthorizationReturn('));
  for(const [platform,saved,selected,expected,route] of [
    ['TIKTOK_SHOP',undefined,'US','US'],
    ['TIKTOK_SHOP','MY','US','MY'],
    ['TIKTOK_SHOP',undefined,'ROW','ROW'],
    ['SHOPEE',undefined,'US',undefined],
    ['SHOPEE',undefined,'US',undefined,'shops-v2'],
    ['TIKTOK_SHOP','PH','US','PH','shops-v2']
  ]) {
    let submitted,redirect;
    const context={hasAnyPermission:()=>true,rememberProductCaptureRoute:()=>{},marketplaceShopRoute:()=>route || "shops",
      document:{querySelector:()=>({value:selected})},
      api:async(url,options)=>{submitted={url,body:JSON.parse(options.body)};return {data:{authorizationUrl:'https://services.tiktokshop.com/open/authorize'}};},
      safeProductUrl:url=>url,window:{location:{assign:url=>redirect=url}}};
    vm.createContext(context);vm.runInContext(source,context);
    const button={textContent:'授权',disabled:false,dataset:{authorizationMarket:saved}};
    await context.startMarketplaceAuthorization(platform,button);
    assert.equal(submitted.body.market,expected);
    assert.equal(submitted.body.returnPath, route === 'shops-v2' ? '/?view=shops-v2' : '/?view=shops');
    assert.equal(submitted.url,`/api/v1/shop-authorizations/${platform}/start`);
    assert.equal(redirect,'https://services.tiktokshop.com/open/authorize');
  }
});

test('copy authorization links never navigate and clipboard denial exposes a selectable link',async()=>{
 const vm=require('node:vm');const source=app.slice(app.indexOf('async function startMarketplaceAuthorization('),app.indexOf('\nfunction consumeMarketplaceAuthorizationReturn('));
 for(const copied of [true,false])for(const platform of ['SHOPEE','TIKTOK_SHOP']){
  const url='https://example.com/authorize?state=one',note={textContent:'',append(input){this.input=input;}};let clipboard,requests=0;
  const context={hasAnyPermission:()=>true,rememberProductCaptureRoute(){},marketplaceShopRoute:()=>null,safeProductUrl:x=>x,copyTextToClipboard:async x=>{clipboard=x;return copied;},
   api:async()=>{requests++;return {data:{authorizationUrl:url,expiresAt:'2026-10-13T02:14:38Z'}};},document:{querySelector:selector=>selector==='#marketplace-shop-note'?note:{value:'ROW'},createElement:()=>({style:{},setAttribute(){},focus(){},select(){this.selected=true;}})},window:{location:{assign(){throw new Error('must not navigate');}}}};
  vm.createContext(context);vm.runInContext(source,context);const button={textContent:'复制授权链接',disabled:false,dataset:{authorizationCopy:'true'}};
  const pending=context.startMarketplaceAuthorization(platform,button);await context.startMarketplaceAuthorization(platform,button);await pending;
  assert.equal(requests,1);assert.equal(clipboard,url);assert.equal(button.disabled,false);assert.equal(button.textContent,'复制授权链接');assert.match(note.textContent,/请到对应的网络环境店铺进行授权/);
  assert.ok(note.textContent.includes(new Date('2026-10-13T02:14:38Z').toLocaleString('zh-CN',{month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',hour12:false})));
  assert.match(note.textContent,/过期后请重新复制授权链接/);
  if(!copied){assert.equal(note.input.value,url);assert.equal(note.input.selected,true);}
 }
});
