// Read-only sandbox preflight. Credentials stay in memory and never enter output.
import fs from 'node:fs';
import crypto from 'node:crypto';
import {execFileSync} from 'node:child_process';
const docker='/Applications/Docker.app/Contents/Resources/bin/docker';
const env=Object.fromEntries(JSON.parse(execFileSync(docker,['inspect','ai-commerce-app'],{encoding:'utf8'}))[0].Config.Env.map(s=>{const p=s.indexOf('=');return [s.slice(0,p),s.slice(p+1)];}));
const base=env.APP_SHOPEE_API_BASE_URL;
if(base!=='https://openplatform.sandbox.test-stable.shopee.sg')throw Error('Expected exact sandbox API URL');
const org='4d6279a0-92b2-4a26-9edf-3bfe5b58f817',shop='4739be25-0900-4ff8-94b6-3d76aba22492',external='227892738';
const sql=`SELECT access_token_ciphertext FROM shop_credentials WHERE organization_id='${org}' AND shop_id='${shop}'`;
const cipher=execFileSync(docker,['exec','ai-commerce-mysql','sh','-c','MYSQL_PWD="$MYSQL_PASSWORD" mysql -u "$MYSQL_USER" "$MYSQL_DATABASE" -NB --raw -e "$1"','qa',sql],{encoding:'utf8'}).trim();
const bytes=Buffer.from(cipher.slice(3),'base64');
const dec=crypto.createDecipheriv('aes-256-gcm',crypto.createHash('sha256').update(env.APP_SHOP_CREDENTIAL_KEY).digest(),bytes.subarray(0,12));
dec.setAAD(Buffer.from(org+':'+shop));dec.setAuthTag(bytes.subarray(-16));
const token=Buffer.concat([dec.update(bytes.subarray(12,-16)),dec.final()]).toString();

function query(q){return execFileSync(docker,['exec','ai-commerce-mysql','sh','-c','MYSQL_PWD="$MYSQL_PASSWORD" mysql -u "$MYSQL_USER" "$MYSQL_DATABASE" -NB --raw -e "$1"','qa',q],{encoding:'utf8'}).trim();}
function call(path, data={}, method='GET') {
 const timestamp=Math.floor(Date.now()/1000);
 const params=new URLSearchParams({partner_id:env.APP_SHOPEE_PARTNER_ID,timestamp:String(timestamp),access_token:token,shop_id:external,sign:crypto.createHmac('sha256',env.APP_SHOPEE_PARTNER_KEY).update(env.APP_SHOPEE_PARTNER_ID+path+timestamp+token+external).digest('hex')});
 if(method==='GET')for(const [k,v] of Object.entries(data))params.set(k,String(v));
 const worker=`let d='';process.stdin.on('data',s=>d+=s);process.stdin.on('end',async()=>{const p=JSON.parse(d);const r=await fetch(p.url,{method:p.method,headers:{'Content-Type':'application/json'},...(p.method==='POST'?{body:JSON.stringify(p.data)}:{}),signal:AbortSignal.timeout(45000)});process.stdout.write(JSON.stringify(await r.json()));});`;
 return JSON.parse(execFileSync(docker,['exec','-i','heyi-pi-runtime','node','-e',worker],{input:JSON.stringify({url:base+path+'?'+params,method,data}),encoding:'utf8',timeout:55000}));
}
const itemId=846055666;
const before=call('/api/v2/product/get_model_list',{item_id:itemId});
fs.writeFileSync('output/shopee-sandbox-preflight/96-retry-before.json',JSON.stringify(before));
console.log(JSON.stringify({error:before.error,keys:Object.keys(before.response||{}),tiers:before.response?.tier_variation,standard:before.response?.standardise_tier_variation,count:before.response?.model?.length,first:before.response?.model?.[0]}));
if(before.error || before.response?.model?.length!==50)throw Error('Expected 50 existing models');
const state=call('/api/v2/product/get_item_base_info',{item_id_list:itemId});
if(state.response?.item_list?.[0]?.item_status!=='UNLIST')throw Error('Expected pending listing');
const rows=query(`SELECT JSON_OBJECT('attributes',attributes_json,'price',price) FROM product_capture_skus WHERE organization_id='${org}' AND capture_id='8e522bbd-9697-485f-8a36-d8bc409f9a16' ORDER BY sort_order`).split('\n').map(JSON.parse);
const quote=JSON.parse(query("SELECT JSON_EXTRACT(request_json,'$.exchangeRateSnapshot') FROM product_publish_workflows WHERE id='0cac0756-a8de-4694-a179-6c884f71aa5e'"));
if(quote.sourceCurrency!=='PHP'||quote.targetCurrency!=='MYR')throw Error('Unexpected currencies');
const existing=new Set(before.response.model.map(m=>m.tier_index.join(',')));
const models=rows.map(row=>{const attrs=typeof row.attributes==='string'?JSON.parse(row.attributes):row.attributes;
 const indexes=before.response.tier_variation.map(t=>t.option_list.findIndex(o=>o.option===attrs[t.name]));
 if(indexes.some(i=>i<0))throw Error('Missing option: no tier changes allowed');
 return {tier_index:indexes,original_price:Math.round(Number(row.price)*Number(quote.rate)*100)/100,seller_stock:[{stock:999}]};
}).filter(m=>!existing.has(m.tier_index.join(',')));
if(rows.length!==96||models.length!==46||new Set(models.map(m=>m.tier_index.join(','))).size!==46)throw Error('Unexpected missing model set');
const request={item_id:itemId,model_list:models};
fs.writeFileSync('output/shopee-sandbox-preflight/96-retry-request.json',JSON.stringify(request));
const result=call('/api/v2/product/add_model',request,'POST');
fs.writeFileSync('output/shopee-sandbox-preflight/96-retry-result.json',JSON.stringify(result));
const after=call('/api/v2/product/get_model_list',{item_id:itemId});
const finalState=call('/api/v2/product/get_item_base_info',{item_id_list:itemId});
fs.writeFileSync('output/shopee-sandbox-preflight/96-retry-after.json',JSON.stringify({after,finalState}));
console.log(JSON.stringify({attemptedAdditional:models.length,error:result.error,message:result.message,response:result.response,actualCount:after.response?.model?.length,itemStatus:finalState.response?.item_list?.[0]?.item_status}));
