import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp, mkdir, readFile, readdir, readlink, rm, symlink, writeFile} from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';

test('asset publication keeps old hashes usable and never changes manifest on an incomplete build', async()=>{
  const {publishModernAssets}=await import('../modern-build.mjs');
  const directory=await mkdtemp(path.join(os.tmpdir(),'finance-modern-publish-'));
  try {
    const source=path.join(directory,'build'),target=path.join(directory,'public');
    await mkdir(path.join(source,'assets'),{recursive:true});
    const manifest={'src/app/build-entry.ts':{isEntry:true,file:'assets/entry-a.js'},'style.css':{file:'assets/style-a.css'}};
    await writeFile(path.join(source,'manifest.json'),JSON.stringify(manifest));
    await writeFile(path.join(source,'assets/entry-a.js'),'export const version=1');
    await writeFile(path.join(source,'assets/style-a.css'),'body{}');
    await publishModernAssets(source,target);
    const first=await readFile(path.join(target,'manifest.json'),'utf8');
    const firstTree=await snapshot(target);
    await writeFile(path.join(source,'manifest.json'),JSON.stringify({...manifest,'src/app/build-entry.ts':{isEntry:true,file:'assets/entry-b.js'}}));
    await assert.rejects(publishModernAssets(source,target));
    assert.equal(await readFile(path.join(target,'manifest.json'),'utf8'),first);
    assert.deepEqual(await snapshot(target),firstTree);
    await writeFile(path.join(source,'assets/entry-b.js'),'export const version=2');
    await publishModernAssets(source,target);
    assert.equal(await readFile(path.join(target,'assets/entry-a.js'),'utf8'),'export const version=1');
    assert.equal(await readFile(path.join(target,'assets/entry-b.js'),'utf8'),'export const version=2');
    assert.notEqual(await readFile(path.join(target,'manifest.json'),'utf8'),first);
    const secondTree=await snapshot(target);
    await writeFile(path.join(source,'assets/entry-b.js'),'overwritten hash');
    await assert.rejects(publishModernAssets(source,target),/immutable/i);
    assert.deepEqual(await snapshot(target),secondTree);
  } finally { await rm(directory,{recursive:true,force:true}); }
});

test('publication rejects outside paths and does not publish source files',async()=>{
  const {publishModernAssets}=await import('../modern-build.mjs');
  const directory=await mkdtemp(path.join(os.tmpdir(),'finance-modern-publish-'));
  try {
    const source=path.join(directory,'build');await mkdir(source);
    await writeFile(path.join(source,'manifest.json'),JSON.stringify({'entry':{isEntry:true,file:'../secret.js'}}));
    await assert.rejects(publishModernAssets(source,path.join(directory,'public')),/asset/i);
    await assert.rejects(readdir(path.join(directory,'public')),{code:'ENOENT'});
  } finally {await rm(directory,{recursive:true,force:true});}
});

const entryKey = 'src/app/build-entry.ts';
function validManifest() {
  return {
    [entryKey]: {isEntry:true,file:'assets/entry-new.js'},
    'style.css': {file:'assets/style-new.css'}
  };
}

async function snapshot(directory) {
  const result = {};
  for (const item of (await readdir(directory,{withFileTypes:true})).sort((a,b)=>a.name.localeCompare(b.name))) {
    const file=path.join(directory,item.name);
    result[item.name] = item.isSymbolicLink() ? {symlink:await readlink(file)} : item.isDirectory() ? await snapshot(file) : (await readFile(file)).toString('base64');
  }
  return result;
}

async function withPublishedBuild(run) {
  const {publishModernAssets} = await import('../modern-build.mjs');
  const directory = await mkdtemp(path.join(os.tmpdir(),'finance-modern-contract-'));
  try {
    const source = path.join(directory,'build'), target = path.join(directory,'public');
    await mkdir(path.join(source,'assets'),{recursive:true});
    await writeFile(path.join(source,'assets/entry-old.js'),'export const version=1');
    await writeFile(path.join(source,'assets/style-old.css'),'body{color:black}');
    await writeFile(path.join(source,'manifest.json'),JSON.stringify({
      [entryKey]:{isEntry:true,file:'assets/entry-old.js'}, 'style.css':{file:'assets/style-old.css'}
    }));
    await publishModernAssets(source,target);
    await writeFile(path.join(source,'assets/entry-new.js'),'export const version=2');
    await writeFile(path.join(source,'assets/style-new.css'),'body{color:blue}');
    await run({source,target,directory,publishModernAssets});
  } finally { await rm(directory,{recursive:true,force:true}); }
}

const invalidManifests = [
  ['wrong entry key', m => { m.wrong=m[entryKey];delete m[entryKey]; }],
  ['missing stylesheet', m => { delete m['style.css']; }],
  ['multiple stylesheet records', m => { m.extra={file:'assets/style-new.css'}; }],
  ['entry is not JavaScript', m => { m[entryKey].file='assets/style-new.css'; }],
  ['entry flag absent', m => { delete m[entryKey].isEntry; }],
  ['dangling import', m => { m[entryKey].imports=['_missing.js']; }],
  ['dangling dynamic import', m => { m[entryKey].dynamicImports=['_missing.js']; }],
  ['inherited import key', m => { m[entryKey].imports=['toString']; }],
  ['import of CSS record', m => { m[entryKey].imports=['style.css']; }],
  ['transitive dangling import', m => { m[entryKey].imports=['_child.js'];m['_child.js']={file:'assets/entry-new.js',imports:['_missing.js']}; }],
  ['missing referenced asset', m => { m[entryKey].assets=['assets/missing.svg']; }],
  ['stylesheet reference outside single host stylesheet', m => { m[entryKey].css=['assets/other.css']; }],
  ['asset path traversal', m => { m[entryKey].assets=['assets/../secret.js']; }],
  ['absolute asset path', m => { m[entryKey].assets=['/tmp/secret.js']; }],
  ['URL asset path', m => { m[entryKey].assets=['https://example.com/secret.js']; }],
  ['encoded asset path', m => { m[entryKey].assets=['assets/%2e%2e/secret.js']; }],
  ['source asset path', m => { m[entryKey].assets=['assets/secret.ts']; }],
  ['backslash asset path', m => { m[entryKey].assets=['assets\\secret.js']; }],
  ['null manifest', () => null],
  ['array manifest', m => Object.values(m)],
  ['null record', m => { m.extra=null; }],
  ['array record', m => { m.extra=[]; }],
  ['string record', m => { m.extra='invalid'; }],
  ['missing file', m => { delete m[entryKey].file; }],
  ['nonboolean entry flag', m => { m['style.css'].isEntry='true'; }],
  ['nonboolean dynamic entry flag', m => { m[entryKey].isDynamicEntry=1; }],
  ['nonstring metadata', m => { m[entryKey].src={}; }],
  ...['imports','dynamicImports','css','assets'].flatMap(field => [
    [field+' must be an array', m => { m[entryKey][field]='assets/entry-new.js'; }],
    [field+' must contain strings', m => { m[entryKey][field]=[null]; }]
  ])
];

for (const [name,mutate] of invalidManifests) {
  test(`rejects ${name} before changing public manifest or assets`, async()=>{
    await withPublishedBuild(async({source,target,publishModernAssets})=>{
      let manifest=validManifest();
      const replacement=mutate(manifest);
      if (replacement !== undefined) manifest=replacement;
      await writeFile(path.join(source,'manifest.json'),JSON.stringify(manifest));
      const before=await snapshot(target);
      const error=await publishModernAssets(source,target).then(()=>null,error=>error);
      assert.deepEqual(await snapshot(target),before,'rejected builds must preserve the complete public tree');
      assert.ok(error,'invalid manifest must reject');
    });
  });
}

test('resolves all static/dynamic dependencies and publishes referenced assets without separate records',async()=>{
  await withPublishedBuild(async({source,target,publishModernAssets})=>{
    const manifest=validManifest();
    manifest[entryKey].imports=['_shared.js'];
    manifest[entryKey].dynamicImports=['src/lazy.ts'];
    manifest[entryKey].css=['assets/style-new.css'];
    manifest['_shared.js']={file:'assets/shared-a.js',imports:[entryKey]};
    manifest['src/lazy.ts']={file:'assets/lazy-a.js',isDynamicEntry:true,imports:['_shared.js'],assets:['assets/icon-a.svg']};
    await writeFile(path.join(source,'assets/shared-a.js'),'export const shared=true');
    await writeFile(path.join(source,'assets/lazy-a.js'),'export const lazy=true');
    await writeFile(path.join(source,'assets/icon-a.svg'),'<svg/>');
    await writeFile(path.join(source,'manifest.json'),JSON.stringify(manifest));
    const result=await publishModernAssets(source,target);
    assert.equal(result.assets,5);
    assert.equal(await readFile(path.join(target,'assets/icon-a.svg'),'utf8'),'<svg/>');
    assert.equal(await readFile(path.join(target,'assets/entry-old.js'),'utf8'),'export const version=1');
    assert.deepEqual(JSON.parse(await readFile(path.join(target,'manifest.json'),'utf8')),manifest);
    const before=await snapshot(target);
    await writeFile(path.join(source,'assets/icon-a.svg'),'<svg>changed</svg>');
    await assert.rejects(publishModernAssets(source,target),/immutable/i);
    assert.deepEqual(await snapshot(target),before);
  });
});

for (const location of ['source file','source assets directory','target assets directory']) {
  test(`rejects a symlink at ${location} before public writes`,async()=>{
    await withPublishedBuild(async({source,target,directory,publishModernAssets})=>{
      await writeFile(path.join(source,'manifest.json'),JSON.stringify(validManifest()));
      const outside=path.join(directory,'outside');
      await mkdir(outside);
      if (location === 'source file') {
        await writeFile(path.join(outside,'external.js'),'export const privateValue=true');
        await rm(path.join(source,'assets/entry-new.js'));
        await symlink(path.join(outside,'external.js'),path.join(source,'assets/entry-new.js'));
      } else {
        const assets=path.join(location.startsWith('source') ? source : target,'assets');
        for (const file of await readdir(assets)) await writeFile(path.join(outside,file),await readFile(path.join(assets,file)));
        await rm(assets,{recursive:true});
        await symlink(outside,assets);
      }
      const before=await snapshot(target), outsideBefore=await snapshot(outside);
      const error=await publishModernAssets(source,target).then(()=>null,error=>error);
      assert.deepEqual(await snapshot(target),before);
      assert.deepEqual(await snapshot(outside),outsideBefore);
      assert.ok(error,'symlink must reject');
    });
  });
}
