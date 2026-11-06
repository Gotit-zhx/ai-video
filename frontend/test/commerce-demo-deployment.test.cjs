const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const read = name => fs.readFileSync(path.join(__dirname, '..', name), 'utf8');
test('both frontend images include the animation document, script, styles and images', () => {
  for (const dockerfile of ['Dockerfile', 'Dockerfile.local']) {
    const source = read(dockerfile);
    if(dockerfile==='Dockerfile'){const assets=require('./public-assets.cjs')();for(const name of ['commerce-motion-demo.html','commerce-motion-demo.js','commerce-motion-demo.css'])assert.ok(assets[name]);assert.ok(Object.keys(assets).some(p=>p.startsWith('assets/')));continue;}
    for (const file of ['commerce-motion-demo.html', 'commerce-motion-demo.js', 'commerce-motion-demo.css']) {
      assert.ok(source.split('\n').some(line => line.startsWith('COPY ') && line.includes(file)), `${dockerfile}: ${file}`);
    }
    assert.match(source, /COPY assets \/usr\/share\/nginx\/html\/assets/);
  }
});
test('only the static demo is embeddable and missing demo files cannot fall back to the login page', () => {
  for (const config of ['nginx.conf', 'nginx.local.conf']) {
    const source = read(config);
    const block = source.match(/location = \/commerce-motion-demo\.html \{([^}]+)\}/)?.[1];
    assert.ok(block);
    assert.match(block, /X-Frame-Options "SAMEORIGIN"/);
    assert.match(block, /frame-ancestors 'self'/);
    assert.match(block, /connect-src 'none'/);
    assert.match(block, /try_files \$uri =404/);
    assert.match(source.replace(block, ''), /X-Frame-Options "DENY"/);
  }
});
