const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
const html=fs.readFileSync(path.join(__dirname,'../index.html'),'utf8');
const app=fs.readFileSync(path.join(__dirname,'../app.js'),'utf8');
test('all original destinations belong to one of four parent menus',()=>{
  const nav=html.slice(html.indexOf('<nav id="primary-navigation"'),html.indexOf('</nav>',html.indexOf('<nav id="primary-navigation"')));
  const groups=[...nav.matchAll(/<details class="nav-group"[\s\S]*?<\/details>/g)].map(m=>m[0]);
  assert.equal(groups.length,4);
  for(const id of ['nav-ai-video-monitor','nav-video-scripts','nav-ai-video-tasks','nav-general-video','nav-shops','nav-publish','nav-automations','nav-automation-admin','nav-models','nav-system','nav-assistant-test'])assert.equal(groups.filter(g=>g.includes(`id="${id}"`)).length,1,id);
});
test('commerce deep links select exactly one content panel and reject unknown routes',()=>{
  const routes=['product-capture','commerce-designs','commerce-templates','commerce-batches','commerce-ads'];
  const panels=routes.map(route=>({dataset:{commercePanel:route},hidden:false}));
  const links=routes.slice(1).map(route=>({dataset:{commerceRoute:route},classList:{toggle(){}},setAttribute(){},removeAttribute(){}}));
  const context={window:{location:{hash:'',pathname:'/',search:''},history:{replaceState(){}}},document:{title:'test',querySelectorAll:s=>s==='[data-commerce-panel]'?panels:links,querySelector:()=>({classList:{toggle(){}},textContent:''})}};
  vm.createContext(context);
  vm.runInContext(app.slice(app.indexOf('const commerceSections ='),app.indexOf('document.querySelectorAll(".nav-group > summary")')),context);
  for(const route of routes){context.window.location.hash='#'+route;vm.runInContext('selectCommerceSection()',context);assert.deepEqual(panels.filter(p=>!p.hidden).map(p=>p.dataset.commercePanel),[route]);}
  context.window.location.hash='#untrusted-route';assert.equal(vm.runInContext('commerceRouteFromHash()',context),null);
});
