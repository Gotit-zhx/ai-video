const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const html=fs.readFileSync(path.join(__dirname,'../index.html'),'utf8');
const app=fs.readFileSync(path.join(__dirname,'../app.js'),'utf8');
test('shop authorization panel has one independent page and no duplicate DOM ids',()=>{
  const start=html.indexOf('id="shop-management-page"');
  const panel=html.indexOf('id="marketplace-shop-panel"');
  const products=html.indexOf('id="product-publish-page"');
  assert.ok(start<panel && panel<products);
  for(const id of ['nav-shops','shop-management-page','marketplace-shop-panel','marketplace-shop-list'])
    assert.equal(html.split(`id="${id}"`).length-1,1);
  assert.match(html, /<a[^>]*href="#shops"[^>]*id="nav-shops"/);
});
test('shop navigation and OAuth return retain permission checks',()=>{
  assert.match(app,/nav-shops"\)\.hidden = !hasAnyPermission\("product:view", "product:publish"\)/);
  assert.match(app,/returnPath: "\/\?view=shops"/);
  assert.match(app,/const isShops = page === "shops"/);
  assert.match(app,/shop-management-page"\)\.hidden = !isShops/);
  assert.match(app,/window\.location\.hash === "#shops" && currentUser && hasAnyPermission/);
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
  for(const [platform,saved,selected,expected] of [
    ['TIKTOK_SHOP',undefined,'US','US'],
    ['TIKTOK_SHOP','MY','US','MY'],
    ['TIKTOK_SHOP',undefined,'ROW','ROW'],
    ['SHOPEE',undefined,'US',undefined]
  ]) {
    let submitted,redirect;
    const context={hasAnyPermission:()=>true,rememberProductCaptureRoute:()=>{},
      document:{querySelector:()=>({value:selected})},
      api:async(url,options)=>{submitted={url,body:JSON.parse(options.body)};return {data:{authorizationUrl:'https://services.tiktokshop.com/open/authorize'}};},
      safeProductUrl:url=>url,window:{location:{assign:url=>redirect=url}}};
    vm.createContext(context);vm.runInContext(source,context);
    const button={textContent:'授权',disabled:false,dataset:{authorizationMarket:saved}};
    await context.startMarketplaceAuthorization(platform,button);
    assert.equal(submitted.body.market,expected);
    assert.equal(submitted.body.returnPath,'/?view=shops');
    assert.equal(submitted.url,`/api/v1/shop-authorizations/${platform}/start`);
    assert.equal(redirect,'https://services.tiktokshop.com/open/authorize');
  }
});
