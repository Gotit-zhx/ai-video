// Opt-in browser QA against a local backend; never calls publishing or model APIs.
const {chromium}=require('playwright');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
(async()=>{
  if(process.env.HEYI_NAV_BROWSER_TEST!=='1')throw Error('Set HEYI_NAV_BROWSER_TEST=1 for local-only QA');
  const browser=await chromium.launch({headless:true,channel:'chrome'});
  try{
    const context=await browser.newContext({viewport:{width:1440,height:1000}});
    const login=await context.request.post('http://localhost:3000/api/v1/auth/login',{data:{organizationCode:'heyi',phone:'13800000000',password:process.env.HEYI_LOCAL_TEST_PASSWORD}});
    assert.equal(login.status(),200,'Local test login');
    const page=await context.newPage();const errors=[];page.on('pageerror',e=>errors.push(e.message));
    await page.route('http://localhost:3000/**',async route=>{
      const pathname=new URL(route.request().url()).pathname;
      if(pathname==='/'||/^\/[a-zA-Z0-9-]+\.(js|css)$/.test(pathname)){
        const file=path.join(__dirname,'..',pathname==='/'?'index.html':pathname.slice(1));
        if(fs.existsSync(file))return route.fulfill({path:file});
      }
      return route.continue();
    });
    await page.goto('http://localhost:3000/#product-capture');
    await page.locator('#dashboard-view').waitFor({state:'visible'});
    assert.equal(await page.locator('.nav-group').count(),4);
    assert.equal(await page.locator('[data-commerce-panel]:visible').count(),1);
    await page.locator('[data-product-capture-id]').first().click();
    await page.locator('#product-capture-editor').waitFor({state:'visible'});
    await page.locator('#design-current-product').click();
    await page.getByText(/^已固定：/).waitFor({state:'visible'});
    assert.equal(new URL(page.url()).hash,'#commerce-designs');
    await page.getByRole('button',{name:'回看来源商品'}).click();
    await page.locator('#product-capture-editor').waitFor({state:'visible'});
    const routes=['commerce-designs','commerce-templates','commerce-batches','commerce-ads'];
    for(const route of routes){
      await page.locator(`[data-commerce-route="${route}"]`).click();
      await page.locator(`[data-commerce-panel="${route}"]`).waitFor({state:'visible'});
      assert.equal(await page.locator('[data-commerce-panel]:visible').count(),1);
      assert.equal(await page.locator('.nav-item[aria-current="page"]').count(),1);
      assert.equal(new URL(page.url()).hash,'#'+route);
    }
    await page.reload();await page.locator('[data-commerce-panel="commerce-ads"]').waitFor({state:'visible'});
    await page.locator('[data-commerce-route="commerce-designs"]').click();
    await page.screenshot({path:'/tmp/heyi-commerce-navigation-desktop.png',fullPage:true,animations:'disabled'});
    await page.locator('#nav-shops').click();await page.locator('#shop-management-page').waitFor({state:'visible'});
    await page.locator('#nav-publish').click();await page.locator('[data-commerce-panel="product-capture"]').waitFor({state:'visible'});
    assert.equal(await page.locator('[data-commerce-panel]:visible').count(),1);
    await page.setViewportSize({width:390,height:844});
    await page.screenshot({path:'/tmp/heyi-commerce-navigation-mobile.png',fullPage:true,animations:'disabled'});
    assert.deepEqual(errors,[],'Browser runtime errors');
    console.log('PASS: four menu groups, isolated pages, active navigation, reload deep link, shop return; desktop/mobile screenshots saved.');
  }finally{await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
