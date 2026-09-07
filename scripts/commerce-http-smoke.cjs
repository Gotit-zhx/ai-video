// Run only against a disposable QA application; creates versioned template records.
const assert=require('node:assert/strict');
const base=process.env.HEYI_QA_URL;
if(!base||process.env.HEYI_QA_CONFIRM_DISPOSABLE!=='yes')throw new Error('Explicit disposable QA URL and confirmation required');
const endpoint=new URL(base);
if(!['127.0.0.1','localhost','heyi-commerce-http-app'].includes(endpoint.hostname))throw new Error('Only local/isolated QA hosts allowed');
let cookie='';
async function call(path,body,expected=200){
  const response=await fetch(base+path,{method:body===undefined?'GET':'POST',headers:{'Content-Type':'application/json',...(cookie?{Cookie:cookie}:{})},...(body===undefined?{}:{body:JSON.stringify(body)})});
  assert.equal(response.status,expected,`${path} status ${response.status}`);
  const setCookie=response.headers.getSetCookie();if(setCookie.length)cookie=setCookie.map(v=>v.split(';')[0]).join('; ');
  if(expected!==200)return;
  const result=await response.json();assert.equal(result.success,true,path);return result.data;
}
(async()=>{
  await call('/api/v1/commerce-ad-templates',undefined,401);
  await call('/api/v1/auth/login',{organizationCode:'heyi',phone:process.env.HEYI_QA_PHONE,password:process.env.HEYI_QA_PASSWORD});
  assert.ok(cookie,'Login must establish a session');await call('/api/v1/auth/me');
  const policy={currency:'PHP',observationDays:7,minimumImpressions:1000,minimumOrders:5,totalBudgetCap:7000,optimizeThreshold:5,campaignTarget:6.5,retestTarget:9};
  const first=await call('/api/v1/commerce-ad-templates',{templateId:null,expectedVersion:0,name:'QA测款规则',policy});
  assert.equal(first.version,1);assert.deepEqual(first.policy,policy);
  const second=await call('/api/v1/commerce-ad-templates',{templateId:first.templateId,expectedVersion:1,name:'QA测款规则v2',policy:{...policy,totalBudgetCap:6000}});
  assert.equal(second.version,2);assert.notEqual(first.snapshotId,second.snapshotId);
  await call('/api/v1/commerce-ad-templates',{templateId:first.templateId,expectedVersion:1,name:'stale',policy},409);
  const versions=await call(`/api/v1/commerce-ad-templates/${first.templateId}/versions`);
  assert.equal(versions.length,2);assert.equal(versions.find(v=>v.version===1).policy.totalBudgetCap,7000);
  const configuration={mode:'POD_DESIGN',platform:'TIKTOK_SHOP',market:'PH',sourceProducts:30,designsPerProduct:10,imagesPerDesign:1,shopCount:6,dailyListingsPerShop:50,rightsConfirmed:true,supplyConfirmed:true,productionFilesConfirmed:true,dailyBudgetPerShop:1000,observationDays:7,targetRoi:6.5};
  const workflow=await call('/api/v1/commerce-templates',{templateId:null,expectedVersion:0,name:'QA铺货模板',configuration});
  assert.equal(workflow.configuration.rightsConfirmed,false);
  assert.ok(!(await call('/api/v1/commerce-templates')).some(t=>t.templateId===first.templateId));
  assert.ok(!(await call('/api/v1/commerce-ad-templates')).some(t=>t.templateId===workflow.templateId));
  await call('/api/v1/commerce-templates',{templateId:first.templateId,expectedVersion:2,name:'wrong kind',configuration},400);
  const decision=await call('/api/v1/commerce-plans/ads/evaluate',{...policy,elapsedDays:7,impressions:5000,attributedOrders:10,spend:1000,netAttributedGmv:5000,attributionSettled:true});
  assert.equal(decision.decision,'OPTIMIZE_CANDIDATE');assert.equal(decision.mode,'PREVIEW_ONLY');
  await call('/api/v1/auth/logout',{});cookie='';await call('/api/v1/commerce-batches',undefined,401);
  console.log('PASS: session login, immutable versions, stale rejection, template type isolation, ad evaluation, logout');
})().catch(error=>{console.error(error.message);process.exitCode=1;});
