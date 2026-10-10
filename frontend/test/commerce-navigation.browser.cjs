// Opt-in browser QA against a local backend; never calls publishing or model APIs.
const {chromium}=require('playwright');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
(async()=>{
  if(process.env.HEYI_NAV_BROWSER_TEST!=='1')throw Error('Set HEYI_NAV_BROWSER_TEST=1 for local-only QA');
  const browser=await chromium.launch({headless:true,channel:'chrome'});
  try{
    const context=await browser.newContext({viewport:{width:1600,height:1200}});
    const login=await context.request.post('http://localhost:3000/api/v1/auth/login',{data:{organizationCode:'heyi',phone:'13800000000',password:process.env.HEYI_LOCAL_TEST_PASSWORD}});
    assert.equal(login.status(),200,'Local test login');
    const sources=await (await context.request.get('http://localhost:3000/api/v1/product-captures?page=0&size=1')).json();
    const source=await (await context.request.get('http://localhost:3000/api/v1/product-captures/'+sources.data.content[0].id)).json();
    const fixture={id:'browser-readonly-fixture',name:'浏览器只读批次夹具',status:'DRAFT',items:[{id:'item',captureId:source.data.id,captureVersion:source.data.version,shopName:'测试店铺'}]};
    const page=await context.newPage();const errors=[];page.on('pageerror',e=>errors.push(e.message));
    await page.route('http://localhost:3000/**',async route=>{
      const pathname=new URL(route.request().url()).pathname;
      if(pathname==='/api/v1/commerce-batches'||pathname==='/api/v1/commerce-batches/browser-readonly-fixture')return route.fulfill({json:{data:pathname.endsWith('fixture')?fixture:[fixture]}});
      if(process.env.HEYI_TEST_DEPLOYED!=='1'&&(pathname==='/'||/^\/[a-zA-Z0-9-]+\.(js|css)$/.test(pathname))){
        const file=path.join(__dirname,'..',pathname==='/'?'index.html':pathname.slice(1));
        if(fs.existsSync(file))return route.fulfill({path:file});
      }
      return route.continue();
    });
    await page.goto('http://localhost:3000/#product-capture');
    await page.locator('#dashboard-view').waitFor({state:'visible'});
    assert.equal(await page.locator('.nav-group').count(),4);
    assert.equal(await page.locator('[data-commerce-panel]:visible').count(),0);
    assert.equal(await page.locator('#primary-navigation [data-commerce-route="commerce-designs"]').isVisible(),false);
    await page.locator('#workbench-batch-refresh').click();
    await page.locator('#workbench-batch-select option[value="browser-readonly-fixture"]').waitFor({state:'attached'});
    await page.locator('#workbench-batch-select').selectOption('browser-readonly-fixture');
    await page.locator('#product-capture-editor').waitFor({state:'visible'});
    await page.locator('[data-studio-step="design"]').click();
    await page.getByText(/^已固定：/).waitFor({state:'visible'});
    assert.equal(new URL(page.url()).hash,'#commerce-designs');
    await page.locator('[data-studio-step="design"].is-current').waitFor();
    assert.equal(await page.locator('.studio-source-pane').isVisible(),true);
    const geometry=await page.evaluate(()=>({source:document.querySelector('.studio-source-pane').getBoundingClientRect().width,recipe:document.querySelector('.studio-recipe').getBoundingClientRect().width,gap:getComputedStyle(document.querySelector('#commerce-design-workspace')).columnGap,columns:getComputedStyle(document.querySelector('.studio-design-results>div')).gridTemplateColumns.split(' ').length}));
    assert.equal(geometry.source,200,'Reference source column is 200px');
    assert.equal(geometry.recipe,302,'Reference recipe panel is 302px');
    assert.equal(geometry.gap,'22px','Reference inter-panel gap');
    assert.equal(geometry.columns,3,'Reference has three variation columns');
    assert.equal(await page.locator('.studio-steps button:disabled').count(),1);
    await page.screenshot({path:'/tmp/heyi-studio-selected-source.png',fullPage:true,animations:'disabled'});
    await page.getByRole('button',{name:'回看来源商品'}).click();
    await page.locator('#product-capture-editor').waitFor({state:'visible'});
    const routes=['commerce-designs','commerce-templates','commerce-batches','commerce-ads'];
    for(const route of routes){
      await page.locator(`[data-commerce-route="${route}"]`).evaluate(node=>node.click());
      await page.locator(`[data-commerce-panel="${route}"]`).waitFor({state:'visible'});
      assert.equal(await page.locator('[data-commerce-panel]:visible').count(),1);
      assert.equal(await page.locator('.nav-item[aria-current="page"]').count(),1);
      assert.equal(new URL(page.url()).hash,'#'+route);
    }
    await page.locator('[data-studio-route="commerce-designs"]').evaluate(node=>node.click());
    await page.screenshot({path:'/tmp/heyi-commerce-navigation-desktop.png',fullPage:true,animations:'disabled'});
    await page.locator('#nav-shops').click();await page.locator('#shop-management-page').waitFor({state:'visible'});
    await page.locator('#nav-publish').click();await page.locator('[data-commerce-panel="product-capture"]').waitFor({state:'visible'});
    assert.equal(await page.locator('[data-commerce-panel]:visible').count(),1);
    await page.locator('[data-commerce-route="commerce-assets"]').click();
    await page.locator('#material-slots-refresh').click();
    await page.waitForFunction(()=>!document.querySelector('#material-slots-refresh').disabled);
    assert.equal(await page.locator('.commerce-batch-hub').isVisible(),false);
    assert.match(await page.locator('#material-slots-status').innerText(),/第 .* 页|暂无图集/);
    const slots=await (await context.request.get('http://localhost:3000/api/v1/commerce-material-slots')).json();
    assert.equal(await page.locator('#material-slots-list>article').count(),slots.data.slots.length);
    if(slots.data.slots.length){await page.locator('#material-slots-list button').first().click();await page.locator('.material-slots-images').waitFor();assert.equal(await page.locator('.material-slots-images>article').count(),slots.data.slots[0].images.length,'Full set is preserved');}
    await page.screenshot({path:'/tmp/heyi-material-slots-desktop.png',fullPage:true,animations:'disabled'});
    await page.locator('#nav-publish').click();await page.locator('#workbench-template').click();
    await page.locator('#commerce-print-editor').waitFor({state:'visible'});
    await page.getByRole('button',{name:'背面',exact:true}).click();assert.equal(await page.locator('.print-area').count(),0,'No invented back print');
    await page.getByRole('button',{name:'正面',exact:true}).click();assert.equal(await page.locator('.print-area').count(),1);
    await page.locator('#commerce-print-editor').getByRole('button',{name:'读取模板',exact:true}).click();
    await page.waitForFunction(()=>/已读取/.test(document.querySelector('#commerce-print-editor>p').textContent));
    await page.screenshot({path:'/tmp/heyi-print-editor-desktop.png',fullPage:true,animations:'disabled'});
    await page.setViewportSize({width:390,height:844});
    if(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth)) console.log(await page.evaluate(()=>[...document.querySelectorAll('body *')].filter(e=>e.getBoundingClientRect().right>innerWidth&&e.getBoundingClientRect().width>0).slice(0,15).map(e=>({tag:e.tagName,id:e.id,cls:e.className,width:e.getBoundingClientRect().width}))));
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>window.innerWidth),false,'Mobile has no page-wide horizontal overflow');
    await page.evaluate(()=>document.documentElement.dataset.theme='dark');
    await page.screenshot({path:'/tmp/heyi-commerce-navigation-mobile.png',fullPage:true,animations:'disabled'});
    assert.deepEqual(errors,[],'Browser runtime errors');
    console.log('PASS: batch-first workspace, hidden legacy submenu entries, pinned source handoff, exact studio geometry and mobile layout; batch data is a read-only fixture, not a live publishing test.');
  }finally{await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
