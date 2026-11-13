/* Compile optional finance sources privately, then publish immutable assets. */
import {createHash, randomBytes} from 'node:crypto';
import {readFile, mkdir, rename, rm, writeFile, open, lstat} from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath, pathToFileURL} from 'node:url';

const frontend = path.dirname(fileURLToPath(import.meta.url));

// Match the host's fixed JavaScript entry and single, standalone stylesheet.
// Manifest keys are identifiers; only validated output paths touch the filesystem.
function manifestAssets(manifest) {
  const record = value => value !== null && typeof value === 'object' && !Array.isArray(value);
  if (!record(manifest)) throw new Error('Invalid finance asset manifest');
  const chunks = Object.values(manifest), assets = new Set();
  function asset(file) {
    if (typeof file !== 'string' || !/^assets\/[A-Za-z0-9_-]+\.(?:js|css|svg|png|jpe?g|gif|webp|avif|ico|woff2?|ttf|otf|eot|wasm)$/.test(file)) {
      throw new Error('Invalid finance asset path');
    }
    assets.add(file);
  }
  for (const chunk of chunks) {
    if (!record(chunk)) throw new Error('Invalid finance asset record');
    asset(chunk.file);
    for (const field of ['src','name']) {
      if (Object.hasOwn(chunk,field) && (typeof chunk[field] !== 'string' || !chunk[field])) throw new Error('Invalid finance asset '+field);
    }
    for (const field of ['isEntry','isDynamicEntry']) {
      if (Object.hasOwn(chunk,field) && typeof chunk[field] !== 'boolean') throw new Error('Invalid finance asset '+field);
      if (chunk[field] === true && !chunk.file.endsWith('.js')) throw new Error('Invalid finance JavaScript entry asset');
    }
    for (const field of ['imports','dynamicImports','css','assets']) {
      if (Object.hasOwn(chunk,field) && (!Array.isArray(chunk[field]) || chunk[field].some(value => typeof value !== 'string' || !value))) {
        throw new Error('Invalid finance asset '+field);
      }
    }
  }
  const entry = manifest['src/app/build-entry.ts'];
  if (!Object.hasOwn(manifest,'src/app/build-entry.ts') || entry.isEntry !== true || !entry.file.endsWith('.js')) throw new Error('Missing finance entry asset');
  const styles = chunks.filter(chunk => chunk.file.endsWith('.css'));
  if (styles.length !== 1) throw new Error('Invalid finance stylesheet asset');
  // Check every record, including transitive and currently unreachable chunks.
  // Cycles are valid; checking each edge once avoids recursive traversal.
  for (const chunk of chunks) {
    for (const field of ['imports','dynamicImports']) {
      if (Object.hasOwn(chunk,field) && !chunk.file.endsWith('.js')) throw new Error('Invalid finance dependency owner');
      for (const key of chunk[field] || []) {
        if (!Object.hasOwn(manifest,key) || !manifest[key].file.endsWith('.js')) throw new Error('Unresolved finance asset '+field);
      }
    }
    for (const file of chunk.css || []) {
      asset(file);
      if (file !== styles[0].file) throw new Error('Invalid finance stylesheet reference');
    }
    for (const file of chunk.assets || []) {
      asset(file);
      if (file.endsWith('.css')) throw new Error('Invalid finance asset reference: use css');
    }
  }
  return [...assets];
}

async function checkPath(file, directory = false, optional = false) {
  try {
    const info = await lstat(file);
    if (directory ? !info.isDirectory() : !info.isFile()) throw new Error('Invalid finance asset filesystem path');
  } catch (error) { if (!optional || error.code !== 'ENOENT') throw error; }
}

export async function publishModernAssets(source, target) {
  await checkPath(source,true);
  await checkPath(path.join(source,'manifest.json'));
  const raw = await readFile(path.join(source, 'manifest.json'), 'utf8');
  const assets = manifestAssets(JSON.parse(raw));
  await checkPath(path.join(source,'assets'),true);
  await checkPath(target,true,true);
  await checkPath(path.join(target,'assets'),true,true);
  await checkPath(path.join(target,'manifest.json'),false,true);
  const checked = [];
  // Complete validation precedes any public write. Incomplete builds keep the old entry.
  for (const relative of assets) {
    await checkPath(path.join(source,relative));
    const data = await readFile(path.join(source, relative));
    checked.push({relative,data});
    await checkPath(path.join(target,relative),false,true);
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
