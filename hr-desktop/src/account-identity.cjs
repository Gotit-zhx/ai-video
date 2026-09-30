const fs=require('node:fs/promises');
const path=require('node:path');
const crypto=require('node:crypto');
const validKey=value=>typeof value==='string'&&/^[a-f0-9]{64}$/.test(value);
const identityKey=(owner,id)=>{
  if(!validKey(owner)||typeof id!=='string'||!/^[1-9]\d{0,19}$/.test(id))throw Error('招聘账号身份尚未就绪');
  return crypto.createHash('sha256').update(`${owner}:boss-recruiter:${id}`).digest('hex');
};
async function binding(root){
  try{return JSON.parse(await fs.readFile(path.join(root,'account-identity.json'),'utf8'));}
  catch(error){if(error.code==='ENOENT')return null;throw error;}
}
async function resolveAccount(directory,owner,id,sessionKey){
  const identity=identityKey(owner,id),base=path.join(directory,'accounts',owner);
  await fs.mkdir(base,{recursive:true,mode:0o700});
  const found=[];
  for(const item of await fs.readdir(base,{withFileTypes:true})){
    if(!item.isDirectory()||!validKey(item.name))continue;
    const root=path.join(base,item.name),saved=await binding(root);
    if(saved?.identity===identity)found.push(root);
  }
  if(found.length>1)throw Error('招聘账号存在多份历史记录，需要核对后恢复');
  if(found.length)return {root:found[0],identity,bound:true};
  // Preserve an existing exact-session directory during the one-time migration.
  const legacy=path.join(base,sessionKey);
  try{
    const stat=await fs.lstat(legacy);
    if(stat.isDirectory()&&!stat.isSymbolicLink()&&!await binding(legacy))return {root:legacy,identity,bound:false};
  }catch(error){if(error.code!=='ENOENT')throw error;}
  return {root:path.join(base,identity),identity,bound:false};
}
async function bindAccount(root,identity){
  const old=await binding(root);
  if(old&&old.identity!==identity)throw Error('招聘账号历史记录归属不一致');
  const target=path.join(root,'account-identity.json'),temporary=target+'.tmp';
  await fs.writeFile(temporary,JSON.stringify({version:1,identity}),{mode:0o600});
  await fs.rename(temporary,target);
}
// Read the already loaded official profile. The main process compares this
// session marker with its cookie snapshot; no request or name-based matching.
function sampleAccountIdentity(){
  const user=document.querySelector('#wrap')?.__vue__?.user$;
  const id=typeof user?.userId==='number'&&Number.isSafeInteger(user.userId)
    ?String(user.userId):typeof user?.userId==='string'?user.userId:'';
  return /^[1-9]\d{0,19}$/.test(id)&&typeof user.wt2==='string'
    ?{id,session:user.wt2}:null;
}
module.exports={resolveAccount,bindAccount,identityKey,sampleAccountIdentity,validKey};
