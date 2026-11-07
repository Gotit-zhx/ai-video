const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const root=path.join(__dirname,'..'), html=fs.readFileSync(path.join(root,'index.html'),'utf8');
for(const recipe of ['Dockerfile.local'])test(`${recipe} packages every root script and stylesheet referenced by index`,()=>{
  const copied=new Set(fs.readFileSync(path.join(root,recipe),'utf8').split('\n').filter(l=>l.startsWith('COPY ')).flatMap(l=>l.trim().split(/\s+/).slice(1,-1)));
  const resources=Array.from(html.matchAll(/(?:src|href)="\/([^/"?]+\.(?:js|css))(?:\?[^" ]*)?"/g),m=>m[1]);
  assert.ok(resources.includes('commerce-ad-evaluation.js'));
  for(const file of resources){assert.ok(fs.existsSync(path.join(root,file)),`${file} exists`);assert.ok(copied.has(file),`${recipe} missing ${file}`);}
});

test('production packages the validated public asset tree',()=>{
  const recipe=fs.readFileSync(path.join(root,'Dockerfile'),'utf8');
  assert.match(recipe,/python3 \/source\/frontend\/package.py \/public/);
  assert.match(recipe,/COPY --from=assets \/public\//);
});
