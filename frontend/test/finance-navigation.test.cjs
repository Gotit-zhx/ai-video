const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
const source=fs.readFileSync(path.join(__dirname,'../app.js'),'utf8');
const access=source.slice(source.indexOf('function hasAnyPermission('),source.indexOf('async function restoreSession('));
function render(user,module){
  const links=Array.from({length:9},()=>({hidden:true}));const group={hidden:true,querySelectorAll:()=>links};
  const element={hidden:false,disabled:false,classList:{toggle(){}}};
  const document={querySelector:()=>element,querySelectorAll:selector=>selector==='[data-finance-route]'?links:selector==='.nav-group'?[group]:[]};
  const context={currentUser:user,document,window:module?{FinanceWorkspace:module}:{}};vm.createContext(context);vm.runInContext(access+'\napplyFeatureAccess();',context);
  return {links,group};
}
for(const [name,user] of [['viewer',{permissions:['finance:view']}],['manager',{permissions:['finance:manage']}],['admin',{roles:['ADMIN']}],['platform admin',{platformAdmin:true}]]){
  test(name+' sees finance navigation before the lazy workspace module exists',()=>{const {links,group}=render(user);assert.equal(group.hidden,false);assert(links.every(n=>!n.hidden));});
}
test('module readiness cannot hide existing finance permissions or grant missing permissions',()=>{
  assert.equal(render({permissions:['finance:view']},{allowed:()=>false}).group.hidden,false);
  assert.equal(render({permissions:['product:view']},{allowed:()=>true}).group.hidden,true);
  assert.equal(render(null,{allowed:()=>true}).group.hidden,true);
});
test('permission changes take effect without relying on a workspace reload',()=>{
  assert.equal(render({permissions:['finance:view']}).group.hidden,false);
  assert.equal(render({permissions:[]}).group.hidden,true);
});
