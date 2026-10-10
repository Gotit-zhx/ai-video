/** Surface-local, connected triangle decals. Coordinates stay in model space. */
const dot=(a,b)=>a[0]*b[0]+a[1]*b[1]+a[2]*b[2];
const sub=(a,b)=>a.map((v,i)=>v-b[i]);
const add=(a,b)=>a.map((v,i)=>v+b[i]);
const mul=(a,s)=>a.map(v=>v*s);
const cross=(a,b)=>[a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]];
const unit=a=>{const n=Math.hypot(...a);if(n<1e-9)throw new Error('印花表面方向无效');return mul(a,1/n);};
const finiteVector=v=>Array.isArray(v)&&v.length===3&&v.every(Number.isFinite);
const edge=(a,b)=>a<b?`${a}:${b}`:`${b}:${a}`;

export function surfaceFrame(print){
 if(!print||!finiteVector(print.position)||!finiteVector(print.normal)||!finiteVector(print.tangent)||
  !Number.isFinite(print.width)||!Number.isFinite(print.height)||print.width<=0||print.height<=0||!Number.isFinite(print.rotation??0))throw new Error('印花表面位置或尺寸无效');
 const normal=unit(print.normal),tangent=unit(sub(print.tangent,mul(normal,dot(print.tangent,normal)))),bitangent=unit(cross(normal,tangent));
 const c=Math.cos(print.rotation||0),s=Math.sin(print.rotation||0);
 return{normal,tangent:add(mul(tangent,c),mul(bitangent,s)),bitangent:add(mul(bitangent,c),mul(tangent,-s))};
}

export function prepareSurfaceMesh(vertices,faces,parts=[]){
 const normals=[],vertexNormals=vertices.map(()=>[0,0,0]),neighbors=faces.map(()=>[]),edges=new Map(),faceParts=faces.map(()=> 'body');
 for(const part of parts)for(let i=part.start;i<part.start+part.count;i++)if(i>=0&&i<faces.length)faceParts[i]=part.name||'body';
 for(let i=0;i<faces.length;i++){
  const f=faces[i],n=cross(sub(vertices[f[1]],vertices[f[0]]),sub(vertices[f[2]],vertices[f[0]]));
  const length=Math.hypot(...n);normals.push(length>1e-12?mul(n,1/length):[0,0,0]);
  for(const j of f)for(let axis=0;axis<3;axis++)vertexNormals[j][axis]+=n[axis];
  for(let j=0;j<3;j++){const key=edge(f[j],f[(j+1)%3]);if(!edges.has(key))edges.set(key,[]);edges.get(key).push(i);}
 }
 for(const incident of edges.values())if(incident.length===2){neighbors[incident[0]].push(incident[1]);neighbors[incident[1]].push(incident[0]);}
 for(let i=0;i<vertexNormals.length;i++){const length=Math.hypot(...vertexNormals[i]);if(length>1e-12)vertexNormals[i]=mul(vertexNormals[i],1/length);}
 return{vertices,faces,normals,vertexNormals,neighbors,faceParts};
}

function clip(poly,axis,limit,sign){
 const out=[];if(!poly.length)return out;
 for(let i=0;i<poly.length;i++){
  const a=poly[i],b=poly[(i+1)%poly.length],da=sign*a[axis]-limit,db=sign*b[axis]-limit;
  if(da<=1e-10)out.push(a);
  if((da<0&&db>0)||(da>0&&db<0)){const t=da/(da-db);out.push(a.map((v,k)=>v+(b[k]-v)*t));}
 }return out;
}

function pointTriangleDistance2(p,a,b,c){
 const ab=sub(b,a),ac=sub(c,a),ap=sub(p,a),d1=dot(ab,ap),d2=dot(ac,ap);
 if(d1<=0&&d2<=0)return dot(ap,ap);
 const bp=sub(p,b),d3=dot(ab,bp),d4=dot(ac,bp);if(d3>=0&&d4<=d3)return dot(bp,bp);
 const vc=d1*d4-d3*d2;if(vc<=0&&d1>=0&&d3<=0){const q=sub(p,add(a,mul(ab,d1/(d1-d3))));return dot(q,q);}
 const cp=sub(p,c),d5=dot(ab,cp),d6=dot(ac,cp);if(d6>=0&&d5<=d6)return dot(cp,cp);
 const vb=d5*d2-d1*d6;if(vb<=0&&d2>=0&&d6<=0){const q=sub(p,add(a,mul(ac,d2/(d2-d6))));return dot(q,q);}
 const va=d3*d6-d5*d4;if(va<=0&&d4-d3>=0&&d5-d6>=0){const q=sub(p,add(b,mul(sub(c,b),(d4-d3)/(d4-d3+d5-d6))));return dot(q,q);}
 const den=1/(va+vb+vc),q=sub(p,add(a,add(mul(ab,vb*den),mul(ac,vc*den))));return dot(q,q);
}

/**
 * Clip actual source triangles in an oriented local box, then retain only the
 * connected patch containing the nearest anchor. A nearby opposite cloth
 * layer cannot acquire the same decal merely because it lies inside the box.
 */
export function buildSurfaceDecal(mesh,print,{offset=.0008,depth=Math.min(print.width,print.height)*.45}={}){
 const frame=surfaceFrame(print),axes=[frame.tangent,frame.bitangent,frame.normal],limits=[print.width/2,print.height/2,Math.max(depth,.002)];
 const local=mesh.vertices.map(v=>{const p=sub(v,print.position);return axes.map(a=>dot(p,a));});
 const candidates=new Map();let anchor=-1,nearest=Infinity;
 for(let i=0;i<mesh.faces.length;i++){
  if(print.meshPart&&mesh.faceParts[i]!==print.meshPart)continue;
  // Both shell sides can be selected intentionally, but the stored normal
  // determines this patch's facing direction. Winding is corrected below.
  const alignment=Math.abs(dot(mesh.normals[i],frame.normal));if(alignment<.10)continue;
  const f=mesh.faces[i],coords=f.map(j=>[...local[j],...mesh.vertexNormals[j]]);
  if(limits.some((limit,axis)=>Math.min(...coords.map(v=>v[axis]))>limit||Math.max(...coords.map(v=>v[axis]))< -limit))continue;
  let poly=coords;for(let axis=0;axis<3&&poly.length;axis++)for(const sign of[-1,1])poly=clip(poly,axis,limits[axis],sign);
  if(poly.length<3)continue;
  candidates.set(i,poly);
  const distance=pointTriangleDistance2(print.position,...f.map(j=>mesh.vertices[j]));
  if(distance<nearest){nearest=distance;anchor=i;}
 }
 if(anchor<0||nearest>Math.max(.01,Math.min(print.width,print.height)*.15)**2)return{positions:[],uv:[],normals:[],sourceFaces:[],coverage:0};
 const frontSign=dot(mesh.normals[anchor],frame.normal)>=0?1:-1;
 const selected=[],seen=new Set([anchor]),queue=[anchor];
 while(queue.length){const i=queue.pop();if(dot(mesh.normals[i],frame.normal)*frontSign<.10)continue;selected.push(i);
  for(const j of mesh.neighbors[i])if(!seen.has(j)&&candidates.has(j)&&mesh.faceParts[j]===mesh.faceParts[anchor]){seen.add(j);queue.push(j);}
 }
 const positions=[],uv=[],normals=[],sourceFaces=[];let area=0;
 const toWorld=p=>add(print.position,add(mul(axes[0],p[0]),add(mul(axes[1],p[1]),mul(axes[2],p[2]))));
 for(const i of selected){const poly=candidates.get(i),normal=mul(mesh.normals[i],frontSign);
  for(let j=1;j<poly.length-1;j++){
   const tri=frontSign>0?[poly[0],poly[j],poly[j+1]]:[poly[0],poly[j+1],poly[j]];
   const signed=(tri[1][0]-tri[0][0])*(tri[2][1]-tri[0][1])-(tri[1][1]-tri[0][1])*(tri[2][0]-tri[0][0]);
   if(Math.abs(signed)<1e-14)continue;area+=Math.abs(signed)/2;
   for(const p of tri){const interpolated=p.slice(3,6),length=Math.hypot(...interpolated),shading=length>1e-9?mul(interpolated,frontSign/length):normal;
    positions.push(...add(toWorld(p),mul(shading,offset)));uv.push(p[0]/print.width+.5,p[1]/print.height+.5);normals.push(...shading);}
   sourceFaces.push(i);
  }
 }
 return{positions,uv,normals,sourceFaces,coverage:Math.min(1,area/(print.width*print.height))};
}
