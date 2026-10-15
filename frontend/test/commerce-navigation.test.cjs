const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
const html=fs.readFileSync(path.join(__dirname,'../index.html'),'utf8');
const app=fs.readFileSync(path.join(__dirname,'../app.js'),'utf8');
test('all original destinations belong to one of six parent menus',()=>{
  const nav=html.slice(html.indexOf('<nav id="primary-navigation"'),html.indexOf('</nav>',html.indexOf('<nav id="primary-navigation"')));
  const groups=[...nav.matchAll(/<details class="nav-group"[\s\S]*?<\/details>/g)].map(m=>m[0]);
  assert.equal(groups.length,6);
  for(const id of ['nav-ai-video-monitor','nav-video-scripts','nav-ai-video-tasks','nav-general-video','nav-shops','nav-publish','nav-automations','nav-automation-admin','nav-models','nav-system','nav-assistant-test'])assert.equal(groups.filter(g=>g.includes(`id="${id}"`)).length,1,id);
});
test('commerce deep links select exactly one content panel and reject unknown routes',()=>{
  const routes=['product-capture','commerce-designs','commerce-templates','commerce-batches','commerce-ads','commerce-assets'];
  const panels=routes.map(route=>({dataset:{commercePanel:route},hidden:false}));
  const links=routes.slice(1).map(route=>({dataset:{commerceRoute:route},classList:{toggle(){}},setAttribute(){},removeAttribute(){}}));
  const entered=[];const element=()=>({classList:{toggle(){}},dataset:{},setAttribute(){},removeAttribute(){},textContent:'',querySelector:()=>null,append(){},addEventListener(){}});
  const context={CustomEvent:class{constructor(type,options){this.type=type;this.detail=options.detail;}},URLSearchParams,window:{dispatchEvent:event=>entered.push(event),location:{hash:'',pathname:'/',search:''},history:{replaceState(){}}},document:{title:'test',createElement:element,querySelectorAll:s=>s==='[data-commerce-panel]'?panels:links,querySelector:element}};
  vm.createContext(context);
  vm.runInContext(app.slice(app.indexOf('const commerceSections ='),app.indexOf('document.querySelectorAll(".nav-group > summary")')),context);
  vm.runInContext(app.slice(app.indexOf('function syncProductCaptureView()'),app.indexOf('function syncProductCapturePagination(')),context);
  for(const route of routes){context.window.location.hash='#'+route;vm.runInContext('selectCommerceSection()',context);assert.deepEqual(panels.filter(p=>!p.hidden).map(p=>p.dataset.commercePanel),[route]);}
  context.window.location.hash='#product-capture?id=test-product';vm.runInContext('selectCommerceSection()',context);assert.equal(vm.runInContext('syncProductCaptureView()',context),'test-product');assert.deepEqual(panels.filter(p=>!p.hidden).map(p=>p.dataset.commercePanel),['product-capture']);
  assert.ok(entered.some(e=>e.type==='heyi:commerce-section-entered'&&e.detail.route==='commerce-assets'));
  context.window.location.hash='#untrusted-route';assert.equal(vm.runInContext('commerceRouteFromHash()',context),null);
  for(const [route,module] of [['product-capture','CAPTURE'],['commerce-designs','FISSION'],['commerce-templates','TEMPLATES'],['commerce-ads','ADS'],['commerce-assets','ASSETS']])
    assert.equal(vm.runInContext(`commerceDataModule("${route}")`,context),module);
});
