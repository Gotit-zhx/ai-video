// Local-only UI fixture. No model or marketplace endpoint is contacted.
import http from 'node:http';
import fs from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const image = '/assets/showcase/ai-commerce-jacket-triptych.png';
const templateId = '12345678-1234-1234-1234-123456789012';
const analysis = {complete:true,productDescription:'画面中央的服装，人物和背景不属于产品',
  productRegions:[{x:.12,y:.18,width:.65,height:.68}],layout:'商品居中占画面约65%，上方标题，右下方细节与副标题',
  palette:'白色背景、深灰文字、绿色强调色',typography:'粗体无衬线标题，细体小字，左对齐',
  background:'浅灰摄影背景',lighting:'左上方柔和侧光',decorations:'细线分隔',
  prompt:'沿用参考海报构图、光影与文字层级，将全部产品替换为{{product}}，标题为{{title}}。'};
const templates = [{id:templateId,name:'简约服饰 · 示例图集',status:'READY',imageCount:3,
  images:Array.from({length:3},(_,index)=>({id:`image-${index}`,index,imageUrl:image,status:'READY',width:1536,height:1024,analysis:{...analysis},error:null}))}];
const product = {id:'preview-product',organizationId:'preview-org',title:'Cotton Graphic T-Shirt',status:'READY',version:1,
  sourcePlatform:'SHOPEE',sourceUrl:'https://shopee.ph/product/1/2',sourceProductId:'2',currency:'PHP',minPrice:199,maxPrice:199,
  categoryName:'Women Clothes > T-shirts',description:'Cotton T-shirt',thumbnailUrl:image,createdAt:'2026-10-19T01:00:00Z',
  images:[{id:'preview-image',originalUrl:image,processedUrl:image,roleName:'MAIN',sortOrder:0,status:'PROCESSED'}],
  skus:[{id:'sku-1',skuKey:'sku-1',externalSkuId:'shirt-M',price:'199',currency:'PHP',stock:100,attributes:{Size:'M'}}],
  normalizedData:{},completenessWarnings:[]};
const requests = [], workflows = new Map();
const progressMode = process.env.PREVIEW_PROGRESS === '1', started = new Map();
function updateProgress() {
  for (const row of templates) {
    if (!started.has(row.id)) continue;
    const elapsed = Math.floor((Date.now() - started.get(row.id)) / 1000);
    row.images.forEach((slot, index) => {
      const time = elapsed - Math.floor(index / 2) * 12;
      slot.status = time < 0 ? 'QUEUED' : time < 12 ? 'ANALYZING' : index === row.images.length - 1 ? 'FAILED' : 'READY';
      slot.analysis = slot.status === 'READY' ? {...analysis} : time >= 0 ? {progress: {
        stage: time < 4 ? 'MODEL_WAITING' : 'MODEL_STREAMING', elapsedSeconds:Math.min(time,12), outputCharacters:Math.max(0,Math.min(time-4,8))*75
      }} : {};
      slot.error = slot.status === 'FAILED' ? '模拟模型连接中断，可重试此图' : null;
    });
    row.status = row.images.some(i=>i.status==='FAILED') ? 'FAILED' : row.images.every(i=>i.status==='READY') ? 'READY' : 'ANALYZING';
  }
}
const json = (res, data, status = 200) => {res.writeHead(status,{'Content-Type':'application/json'});res.end(JSON.stringify({data}));};
const mime = {'.html':'text/html','.css':'text/css','.js':'text/javascript','.mjs':'text/javascript','.png':'image/png','.svg':'image/svg+xml','.json':'application/json'};
const server = http.createServer(async (req,res) => {
  try {
    const url = new URL(req.url,'http://localhost'), p = url.pathname;
    if (p === '/__preview/requests') return json(res, requests);
    if (p === '/api/v1/auth/me') return json(res,{id:'preview-user',organizationId:'preview-org',organizationCode:'preview',
      organizationName:'本地测试 · 模拟数据',displayName:'预览账号',permissions:['product:view','product:edit','product:publish','product:collect','asset:watermark'],roles:[]});
    if (p === '/api/v1/shops') return json(res,[]);
    if (p === '/api/v1/shops/integration-status') return json(res,{livePublishingEnabled:false});
    if (p === '/api/v1/product-listing-templates') return json(res,
      ['SHOPEE','TIKTOK_SHOP'].map(platform=>({platform,name:'Local fixture',enabled:false,description:'',
        weightGrams:250,packageLengthCm:20,packageWidthCm:15,packageHeightCm:2,version:0})));
    if (p === '/api/v1/product-captures') return json(res,{content:[product],totalElements:1,totalPages:1,number:0,size:24,
      statusCounts:{CAPTURED:0,READY:1,APPROVED:0,PUBLISHED:0,FAILED:0}});
    if (p === '/api/v1/product-captures/preview-product') return json(res,product);
    if (p === '/api/v1/product-main-image-templates' && req.method === 'GET') {updateProgress();return json(res,templates);}
    if (p === '/api/v1/product-main-image-templates' && req.method === 'POST') {
      const form = await new Request('http://localhost/upload',{method:'POST',headers:req.headers,body:req,duplex:'half'}).formData();
      const files = form.getAll('files'), id=crypto.randomUUID();
      const row={id,name:form.get('name'),status:'READY',imageCount:files.length,images:files.map((file,index)=>({
        id:crypto.randomUUID(),index,imageUrl:image,status:'READY',width:1024,height:1024,analysis:{...analysis},error:null}))};
      if (progressMode) {
        await new Promise(resolve=>setTimeout(resolve,8000));
        started.set(row.id,Date.now());
      }
      templates.unshift(row); updateProgress(); requests.push({method:'POST',path:p,files:files.map(f=>f.name)}); return json(res,row);
    }
    if (p.startsWith('/api/v1/product-main-image-templates/') && req.method === 'PATCH') {
      let raw='';for await(const chunk of req)raw+=chunk;
      const parts=p.split('/'), row=templates.find(row=>row.id===parts[4]), slot=row.images.find(i=>i.id===parts[6]);
      slot.analysis.prompt=JSON.parse(raw).prompt;return json(res,row);
    }
    if (p.startsWith('/api/v1/product-main-image-templates/') && req.method === 'DELETE') {
      const index=templates.findIndex(row=>row.id===p.split('/')[4]);if(index>=0)templates.splice(index,1);return json(res,{archived:true});
    }
    if (p === '/api/v1/product-captures/preview-product/publish-workflows' && req.method === 'POST') {
      let raw='';for await(const chunk of req)raw+=chunk;const body=JSON.parse(raw);requests.push({method:'POST',path:p,body});
      const id=crypto.randomUUID(), template=templates.find(row=>row.id===body.mainImageStyleTemplateId);
      const workflow={id,captureId:product.id,shopId:'dry-run-shop',destinationPlatform:'SHOPEE',executionMode:'DRY_RUN',
        status:'PENDING',currentStep:template?'STYLE_IMAGES':'PUBLISH',mainImageStyle:template?{templateName:template.name,totalImages:template.imageCount,processedImages:0,imageUrls:[]}:null};
      workflows.set(id,{workflow,polls:0,template});return json(res,workflow);
    }
    if (p.startsWith('/api/v1/product-captures/preview-product/publish-workflows/')) {
      const entry=workflows.get(p.split('/').at(-1));
      if(!entry)return json(res,null,404);
      entry.polls++;
      if(entry.template){entry.workflow.mainImageStyle.processedImages=Math.min(entry.template.imageCount,entry.polls);
        entry.workflow.mainImageStyle.imageUrls=Array(entry.workflow.mainImageStyle.processedImages).fill(image);}
      if(entry.polls>3){entry.workflow.status='SUCCEEDED';entry.workflow.currentStep='COMPLETED';entry.workflow.publication={dryRun:true,status:'DRY_RUN_SUCCEEDED'};}
      return json(res,entry.workflow);
    }
    if (p.startsWith('/api/')) {
      if (req.method !== 'GET') return json(res,{message:'Preview: unsupported mutation'},400);
      return json(res,[]);
    }
    const file=path.resolve(root,p==='/'?'index.html':p.slice(1));
    if (!file.startsWith(root+path.sep)) {res.writeHead(403);return res.end();}
    const data=await fs.readFile(file);
    res.writeHead(200,{'Content-Type':mime[path.extname(file)]||'application/octet-stream','Cache-Control':'no-store'});res.end(data);
  } catch (error) {res.writeHead(500,{'Content-Type':'application/json'});res.end(JSON.stringify({message:error.message}));}
});
const port = Number(process.env.PORT || 8093);
server.listen(port,'127.0.0.1',()=>console.log(`Local fixture preview: http://127.0.0.1:${port}/#product-main-image-styles`));
