// Exercise the actual production packager; no Docker daemon is needed.
const fs=require('node:fs'),os=require('node:os'),path=require('node:path'),{execFileSync}=require('node:child_process');
let manifest;
module.exports=()=>{
 if(manifest)return manifest;
 const folder=fs.mkdtempSync(path.join(os.tmpdir(),'heyi-public-assets-'));
 try{
  execFileSync('python3',[path.join(__dirname,'../package.py'),path.join(folder,'public')],{stdio:'pipe'});
  manifest=JSON.parse(fs.readFileSync(path.join(folder,'public/asset-manifest.json'),'utf8'));
  return manifest;
 }finally{fs.rmSync(folder,{recursive:true,force:true});}
};
