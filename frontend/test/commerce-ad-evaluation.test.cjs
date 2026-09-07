const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
function setup(){
  const node=()=>({children:[],handlers:{},value:'',append(e){this.children.push(e);},replaceChildren(){this.children=[];},addEventListener(k,f){this.handlers[k]=f;}});
  const form=node(),result=node(),calls=[];let resolve;
  vm.runInNewContext(fs.readFileSync(require('node:path').join(__dirname,'../commerce-ad-evaluation.js'),'utf8'),{
    document:{getElementById:id=>id==='commerce-ad-form'?form:result,createElement:node},
    api:(url,options)=>{calls.push({url,options});return new Promise(r=>resolve=r);}
  });
  return {form,result,calls,respond(){resolve({data:{decision:'OBSERVE',observedRoas:null,remainingBudget:7000,reasons:['<script>unsafe</script>'],suggestedActions:[],limitations:[]}});}};
}
test('ad evaluation calls preview only and uses text rendering',async()=>{
  const s=setup();const p=s.form.handlers.submit({preventDefault(){}});s.respond();await p;
  assert.equal(s.calls[0].url,'/api/v1/commerce-plans/ads/evaluate');
  const body=JSON.parse(s.calls[0].options.body);assert.equal(body.totalBudgetCap,7000);assert.equal(body.observationDays,7);
  assert.ok(s.result.children.some(e=>e.textContent==='<script>unsafe</script>'));
});
test('editing invalidates in-flight ad advice',async()=>{
  const s=setup();const p=s.form.handlers.submit({preventDefault(){}});s.form.handlers.input();s.respond();await p;
  assert.equal(s.result.children.length,0);assert.match(s.result.textContent,/重新评估/);
});
