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
const paths=['/api/v2/shop/get_shop_info','/api/v2/product/get_category','/api/v2/logistics/get_channel_list', '/api/v2/product/get_attribute_tree'];
const requests=paths.map(path=>{const timestamp=Math.floor(Date.now()/1000);const params=new URLSearchParams({partner_id:env.APP_SHOPEE_PARTNER_ID,timestamp:String(timestamp),access_token:token,shop_id:external,sign:crypto.createHmac('sha256',env.APP_SHOPEE_PARTNER_KEY).update(env.APP_SHOPEE_PARTNER_ID+path+timestamp+token+external).digest('hex')});if(path.endsWith('get_category')||path.endsWith('get_attribute_tree'))params.set('language','en');if(path.endsWith('get_attribute_tree'))params.set('category_id_list','106554');return {path,url:base+path+'?'+params};});
const worker=`let data='';process.stdin.on('data',s=>data+=s);process.stdin.on('end',async()=>{const requests=JSON.parse(data);const results=await Promise.all(requests.map(async({path,url})=>{try{const r=await fetch(url,{signal:AbortSignal.timeout(45000)});return {path,status:r.status,body:await r.json()};}catch{return {path,status:0,error:'Read failed'};}}));process.stdout.write(JSON.stringify(results));});`;
const result=execFileSync(docker,['exec','-i','heyi-pi-runtime','node','-e',worker],{input:JSON.stringify(requests),encoding:'utf8',timeout:55000,maxBuffer:8*1024*1024});
const safe=result.replaceAll(token,'[REDACTED]').replaceAll(env.APP_SHOPEE_PARTNER_KEY,'[REDACTED]');
fs.mkdirSync('output/shopee-sandbox-preflight',{recursive:true});fs.writeFileSync('output/shopee-sandbox-preflight/readiness.json',safe);
for(const r of JSON.parse(safe)){console.log(JSON.stringify({path:r.path,status:r.status,error:r.body?.error,message:r.body?.message,responseKeys:Object.keys(r.body?.response||{}),...(r.path.endsWith('get_shop_info')?{shop:r.body?.response||r.body}:{})}));}
