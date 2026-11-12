const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');

function location(config,name){
  const start=config.indexOf(`location ${name} {`);
  assert.ok(start>=0,`location ${name} exists`);
  const open=config.indexOf('{',start);let depth=1,end=open+1;
  while(depth&&end<config.length){if(config[end]==='{')depth++;if(config[end]==='}')depth--;end++;}
  assert.equal(depth,0,'location closes');return config.slice(open+1,end-1);
}

for(const file of ['nginx.local.conf','nginx.conf']){
  const config=fs.readFileSync(path.join(__dirname,'..',file),'utf8');
  test(`${file}: SPA entry revalidates without losing server security headers`,()=>{
    const entry=location(config,'= /index.html');
    assert.match(entry,/add_header Cache-Control "no-cache, no-store, must-revalidate" always;/);
    const headers=config.split(/\n\s*location\b/)[0].split('\n').map(line=>line.trim()).filter(line=>line.startsWith('add_header '));
    assert.ok(headers.some(line=>line.includes('X-Content-Type-Options')));
    for(const header of headers)assert.ok(entry.includes(header),`HTML retains ${header.split(/\s+/)[1]}`);
    assert.match(location(config,'/'),/try_files \$uri \$uri\/ \/index\.html;/);
    assert.match(config,/index index\.html;/);
  });
  test(`${file}: HTML policy does not override API response caching`,()=>{
    const api=location(config,'/api/');
    assert.match(api,/proxy_cache off;/);
    assert.doesNotMatch(api,/add_header\s+Cache-Control/i);
    const beforeLocations=config.split(/\n\s*location\b/)[0];
    assert.doesNotMatch(beforeLocations,/add_header\s+Cache-Control/i);
  });
  test(`${file}: finance build sources never reach the static source root`,()=>{
    assert.match(location(config,'^~ /modern/'),/return 404;/);
    assert.match(location(config,'= /modern-build.mjs'),/return 404;/);
  });
}
