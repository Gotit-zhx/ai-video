/* Compile optional finance sources privately, then publish immutable assets. */
import {createHash, randomBytes} from 'node:crypto';
import {readFile, mkdir, rename, rm, writeFile, open} from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath, pathToFileURL} from 'node:url';

const frontend = path.dirname(fileURLToPath(import.meta.url));

export async function publishModernAssets(source, target) {
  const raw = await readFile(path.join(source, 'manifest.json'), 'utf8');
  const manifest = JSON.parse(raw);
  const assets = [...new Set(Object.values(manifest).map(item => item.file))];
  if (!assets.length || !Object.values(manifest).some(item => item.isEntry === true)) throw new Error('Missing finance entry asset');
  const checked = [];
  // Complete validation precedes any public write. Incomplete builds keep the old entry.
  for (const relative of assets) {
    if (typeof relative !== 'string' || !/^assets\/[A-Za-z0-9_-]+\.(?:js|css)$/.test(relative)) throw new Error('Invalid finance asset path');
    const data = await readFile(path.join(source, relative));
    checked.push({relative,data});
    try {
      const existing = await readFile(path.join(target, relative));
      if (!existing.equals(data)) throw new Error('An immutable finance asset would be overwritten');
    } catch (error) { if (error.code !== 'ENOENT') throw error; }
  }
  await mkdir(path.join(target, 'assets'), {recursive:true});
  const suffix = '.tmp-' + process.pid + '-' + randomBytes(6).toString('hex');
  async function replace(relative, data) {
    const destination = path.join(target,relative),temporary = destination + suffix;
    try { await writeFile(temporary,data); await rename(temporary,destination); }
    finally { await rm(temporary,{force:true}); }
  }
  for (const {relative,data} of checked) await replace(relative,data);
  // Existing hashes are deliberately retained for open tabs and recovery.
  await replace('manifest.json',raw);
  return {assets:checked.length,sha256:createHash('sha256').update(raw).digest('hex')};
}

export async function buildModern({watch = false} = {}) {
  const root = path.join(frontend,'modern');
  const state = path.resolve(frontend,'../.dev');
  await mkdir(state,{recursive:true});
  const lockPath = path.join(state,'finance-modern-build.lock');
  let lock;
  try { lock = await open(lockPath,'wx',0o600); }
  catch (error) {
    if (error.code === 'EEXIST') throw new Error('Another finance compiler owns this worktree; stop it before rebuilding');
    throw error;
  }
  try {
    await lock.writeFile(String(process.pid));
    const {build} = await import(pathToFileURL(path.join(root,'node_modules/vite/dist/node/index.js')).href);
    const source = path.join(state,'finance-modern-build');
    const compiler = await build({root,build:{outDir:source,emptyOutDir:true,...(watch ? {watch:{}} : {})},plugins:[{
      name:'finance-atomic-assets',
      async writeBundle() { await publishModernAssets(source,path.join(frontend,'assets/finance-modern')); }
    }]});
    if (watch) await new Promise((resolve,reject) => {
      let closing = false;
      const stop = async () => {
        if (closing) return; closing = true;
        try { await compiler.close(); resolve(); } catch (error) { reject(error); }
      };
      process.once('SIGINT',stop);process.once('SIGTERM',stop);
    });
  } finally { await lock.close(); await rm(lockPath,{force:true}); }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const args = process.argv.slice(2);
  if (args.some(value=>value !== '--watch')) throw new Error('Only --watch is supported');
  await buildModern({watch:args.includes('--watch')});
}
