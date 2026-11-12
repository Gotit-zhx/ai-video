import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp, mkdir, readFile, rm, writeFile} from 'node:fs/promises';
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
    await writeFile(path.join(source,'manifest.json'),JSON.stringify({...manifest,'src/app/build-entry.ts':{isEntry:true,file:'assets/entry-b.js'}}));
    await assert.rejects(publishModernAssets(source,target));
    assert.equal(await readFile(path.join(target,'manifest.json'),'utf8'),first);
    await writeFile(path.join(source,'assets/entry-b.js'),'export const version=2');
    await publishModernAssets(source,target);
    assert.equal(await readFile(path.join(target,'assets/entry-a.js'),'utf8'),'export const version=1');
    assert.equal(await readFile(path.join(target,'assets/entry-b.js'),'utf8'),'export const version=2');
    assert.notEqual(await readFile(path.join(target,'manifest.json'),'utf8'),first);
    await writeFile(path.join(source,'assets/entry-b.js'),'overwritten hash');
    await assert.rejects(publishModernAssets(source,target),/immutable/i);
  } finally { await rm(directory,{recursive:true,force:true}); }
});

test('publication rejects outside paths and does not publish source files',async()=>{
  const {publishModernAssets}=await import('../modern-build.mjs');
  const directory=await mkdtemp(path.join(os.tmpdir(),'finance-modern-publish-'));
  try {
    const source=path.join(directory,'build');await mkdir(source);
    await writeFile(path.join(source,'manifest.json'),JSON.stringify({'entry':{isEntry:true,file:'../secret.js'}}));
    await assert.rejects(publishModernAssets(source,path.join(directory,'public')),/asset/i);
  } finally {await rm(directory,{recursive:true,force:true});}
});
