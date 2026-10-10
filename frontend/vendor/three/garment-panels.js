/** Image-defined cap/dolman sewing panels. No inserted tube sleeves. */
const clamp=(x,a,b)=>Math.min(b,Math.max(a,x));
const lerp=(a,b,t)=>a+(b-a)*t;
const pause=()=>globalThis.scheduler?.yield?globalThis.scheduler.yield():new Promise(resolve=>{
 if(typeof MessageChannel!=='function'){setTimeout(resolve,0);return;}
 const channel=new MessageChannel();channel.port1.onmessage=()=>{channel.port1.close();channel.port2.close();resolve();};channel.port2.postMessage(0);
});
function bbox(mask,w,h){let x0=w,x1=-1,y0=h,y1=-1;for(let y=0;y<h;y++)for(let x=0;x<w;x++)if(mask[y*w+x]){x0=Math.min(x0,x);x1=Math.max(x1,x);y0=Math.min(y0,y);y1=y;}return{x0,x1,y0,y1};}
function contour(mask,w,h){
 const next=new Map(),key=(x,y)=>`${x},${y}`,add=(x,y,a,b)=>next.set(key(x,y),[a,b]);
 for(let y=0;y<h;y++)for(let x=0;x<w;x++)if(mask[y*w+x]){
  if(y===0||!mask[(y-1)*w+x])add(x,y,x+1,y);
  if(x===w-1||!mask[y*w+x+1])add(x+1,y,x+1,y+1);
  if(y===h-1||!mask[(y+1)*w+x])add(x+1,y+1,x,y+1);
  if(x===0||!mask[y*w+x-1])add(x,y+1,x,y);
 }
 let best=[];const seen=new Set();for(const start of next.keys()){if(seen.has(start))continue;let p=start,points=[];
  while(!seen.has(p)&&next.has(p)){seen.add(p);points.push(p.split(',').map(Number));const q=next.get(p);p=key(...q);}
  if(p===start&&points.length>best.length)best=points;
 }if(best.length<8)throw new Error('外层衣片轮廓不完整');return best;
}
function distance(p,a,b){const dx=b[0]-a[0],dy=b[1]-a[1],t=clamp(((p[0]-a[0])*dx+(p[1]-a[1])*dy)/Math.max(1e-12,dx*dx+dy*dy),0,1);return Math.hypot(p[0]-a[0]-dx*t,p[1]-a[1]-dy*t);}
function rdp(points,e){if(points.length<=2)return points;let d=0,j=0;for(let i=1;i<points.length-1;i++){const x=distance(points[i],points[0],points.at(-1));if(x>d){d=x;j=i;}}return d>e?[...rdp(points.slice(0,j+1),e).slice(0,-1),...rdp(points.slice(j),e)]:[points[0],points.at(-1)];}
function simplify(points,denoise=false){
 const extent=Math.max(...points.map(p=>p[1]))-Math.min(...points.map(p=>p[1]));
 if(denoise){const sigma=Math.max(1,extent/250),radius=Math.ceil(sigma*3);points=points.map((_,i)=>{const p=[0,0];let sum=0;for(let d=-radius;d<=radius;d++){const w=Math.exp(-d*d/(2*sigma*sigma)),q=points[(i+d+points.length)%points.length];sum+=w;p[0]+=q[0]*w;p[1]+=q[1]*w;}return p.map(x=>x/sum);});}
 let far=1;for(let i=2;i<points.length;i++)if(Math.hypot(...points[i].map((x,k)=>x-points[0][k]))>Math.hypot(...points[far].map((x,k)=>x-points[0][k])))far=i;
 let e=denoise?Math.max(.8,extent*.003):.4,result;do{result=[...rdp(points.slice(0,far+1),e).slice(0,-1),...rdp([...points.slice(far),points[0]],e).slice(0,-1)];e*=1.16;}while(result.length>38);return result;
}
function signedArea(points){return points.reduce((s,p,i)=>{const q=points[(i+1)%points.length];return s+p[0]*q[1]-q[0]*p[1];},0)/2;}
const cross2=(a,b,c)=>(b[0]-a[0])*(c[1]-a[1])-(b[1]-a[1])*(c[0]-a[0]);
function ears(points){const remaining=points.map((_,i)=>i),faces=[];let guard=0;
 while(remaining.length>3){let found=false;for(let k=0;k<remaining.length;k++){const a=remaining[(k+remaining.length-1)%remaining.length],b=remaining[k],c=remaining[(k+1)%remaining.length];if(cross2(points[a],points[b],points[c])<1e-10)continue;
   if(remaining.some(i=>i!==a&&i!==b&&i!==c&&cross2(points[a],points[b],points[i])>=-1e-10&&cross2(points[b],points[c],points[i])>=-1e-10&&cross2(points[c],points[a],points[i])>=-1e-10))continue;
   faces.push([a,b,c]);remaining.splice(k,1);found=true;break;
  }if(!found||guard++>points.length*2)throw new Error('衣片轮廓无法稳定三角化');
 }faces.push([...remaining]);return faces;
}
function polygonRow(points,y){const xs=[];for(let i=0;i<points.length;i++){const a=points[i],b=points[(i+1)%points.length];if((a[1]<=y&&b[1]>y)||(b[1]<=y&&a[1]>y))xs.push(lerp(a[0],b[0],(y-a[1])/(b[1]-a[1])));}return xs.length?[Math.min(...xs),Math.max(...xs)]:[points[0][0]-.01,points[0][0]+.01];}
function gaussian(values,sigma=3){return values.map((_,i)=>{let sum=0,total=0;for(let d=-Math.ceil(sigma*3);d<=sigma*3;d++){const a=Math.exp(-d*d/(2*sigma*sigma));sum+=values[clamp(i+d,0,values.length-1)]*a;total+=a;}return sum/total;});}
function interpolate(values,t){const x=clamp(t,0,values.length-1),i=Math.floor(x);return lerp(values[i],values[Math.min(i+1,values.length-1)],x-i);}
function sideDepth(references,height,{supportedTorso=false}={}){
 const profiles=references.filter(r=>r.side==='LEFT'||r.side==='RIGHT').map(r=>{const b=bbox(r.mask,r.width,r.height),s=(b.y1-b.y0)/height,cx=(b.x0+b.x1)/2,rows=[];
  for(let y=b.y1;y>=b.y0;y--){let a=r.width,z=-1;for(let x=0;x<r.width;x++)if(r.mask[y*r.width+x]){a=Math.min(a,x);z=x;}if(z>=a){const sign=r.side==='LEFT'?-1:1,v=[sign*(a-cx)/s,sign*(z-cx)/s].sort((a,b)=>a-b);rows.push([(b.y1-y)/s,...v]);}}
  return Array.from({length:129},(_,i)=>{const y=i/128*height;let j=0;while(j<rows.length-2&&rows[j+1][0]<y)j++;const t=clamp((y-rows[j][0])/Math.max(1e-8,rows[j+1][0]-rows[j][0]),0,1);return[lerp(rows[j][1],rows[j+1][1],t),lerp(rows[j][2],rows[j+1][2],t)];});
 });
 if(!profiles.length)return{center:Array(129).fill(0),front:Array(129).fill(height*.18),back:Array(129).fill(height*.17)};
 const aggregate=axis=>Array.from({length:129},(_,i)=>profiles.reduce((s,p)=>s+p[i][axis],0)/profiles.length);
 let lower=supportedTorso?aggregate(0):gaussian(aggregate(0),5),upper=supportedTorso?aggregate(1):gaussian(aggregate(1),5);
 // A slanted photographed hem ends at one projected corner. Those final
 // scanlines are not a physical cross-section: keep the open cloth tube's
 // supported depth through the last 10% instead of shrinking it to a point.
 const continueHem=values=>values.map((v,i)=>{let t=clamp((i/128-.04)/.08,0,1);t=t*t*(3-2*t);return lerp(interpolate(values,128*.10),v,t);});
 lower=continueHem(lower);upper=continueHem(upper);
 if(supportedTorso){
  // Sleeves obscure upper-torso side silhouettes. Use the unobscured lower
  // body as a broad support estimate, never transfer each silhouette row to
  // the chest/back. Keep hem folds and the neck out of this estimate too.
  const median=xs=>[...xs].sort((a,b)=>a-b)[Math.floor(xs.length/2)];
  const span=[...Array(26)].map((_,i)=>Math.round((.18+i*.008)*128));
  const center=median(span.map(i=>(lower[i]+upper[i])/2));
  const radius=clamp(median(span.map(i=>(upper[i]-lower[i])/2)),height*.12,height*.24);
  const meanT=span.reduce((s,i)=>s+i/128,0)/span.length;
  const taper=clamp(span.reduce((s,i)=>s+(i/128-meanT)*((upper[i]-lower[i])/2-radius),0)/span.reduce((s,i)=>s+(i/128-meanT)**2,0),-height*.30,height*.045);
  const radii=Array.from({length:129},(_,i)=>{const t=clamp((i/128-.35)/.65,0,1),shoulderLimit=radius*(1-.50*t*t*(3-2*t));return clamp(Math.min(radius+taper*(i/128-meanT),shoulderLimit),height*.075,height*.26);});
  return{center:Array(129).fill(center),front:radii,back:[...radii],evidence:{method:'unoccluded-lower-torso-support',sampleHeightFractions:[.18,.38],upperSilhouetteIgnored:true,radius,taper,shoulderTaperPrior:true}};
 }
 return{center:lower.map((x,i)=>(x+upper[i])/2),front:upper.map((x,i)=>clamp((x-lower[i])/2,height*.09,height*.28)),back:upper.map((x,i)=>clamp((x-lower[i])/2,height*.09,height*.28))};
}
function edgeKey(a,b){return a<b?`${a}:${b}`:`${b}:${a}`;}
function insideCircle(a,b,c,d){
 const ax=a[0]-d[0],ay=a[1]-d[1],bx=b[0]-d[0],by=b[1]-d[1],cx=c[0]-d[0],cy=c[1]-d[1];
 return (ax*ax+ay*ay)*(bx*cy-by*cx)-(bx*bx+by*by)*(ax*cy-ay*cx)+(cx*cx+cy*cy)*(ax*by-ay*bx);
}
// Ear clipping is only an initial constrained triangulation. Its long radial
// triangles are unsuitable for a depth field: midpoint subdivision alone
// preserves a visible fan even with thousands of vertices. Flip unconstrained
// edges and relax only interior planar vertices before assigning any depth.
async function regularizePanels(points,faces,boundary,check,{rounds=20,avoidBoundaryChords=false}={}){
 const fixed=new Set([...boundary.keys()].flatMap(k=>k.split(':').map(Number)));
 const flip=()=>{let total=0;for(let pass=0;pass<12;pass++){
  const edges=new Map();for(let i=0;i<faces.length;i++){const f=faces[i];for(let k=0;k<3;k++){const a=f[k],b=f[(k+1)%3],key=edgeKey(a,b);if(boundary.has(key))continue;
   const other=edges.get(key);if(other)other.push([i,a,b,f[(k+2)%3]]);else edges.set(key,[[i,a,b,f[(k+2)%3]]]);}}
  const touched=new Set();let changed=0;for(const pair of edges.values()){if(pair.length!==2)continue;const[[i,a,b,c],[j,,,d]]=pair;if(touched.has(i)||touched.has(j))continue;
   if(avoidBoundaryChords&&fixed.has(c)&&fixed.has(d)&&!boundary.has(edgeKey(c,d)))continue;
   if(cross2(points[c],points[d],points[b])<=1e-12||cross2(points[d],points[c],points[a])<=1e-12)continue;
   if(insideCircle(points[a],points[b],points[c],points[d])<1e-12)continue;
   faces[i]=[c,d,b];faces[j]=[d,c,a];touched.add(i);touched.add(j);changed++;
  }total+=changed;if(!changed)break;
 }return total;};
 for(let round=0;round<rounds;round++){
  flip();const adj=points.map(()=>new Set());for(const f of faces)for(let k=0;k<3;k++){adj[f[k]].add(f[(k+1)%3]);adj[f[(k+1)%3]].add(f[k]);}
  const targets=points.map((p,i)=>{if(fixed.has(i))return p;const sum=[0,0];for(const j of adj[i]){sum[0]+=points[j][0];sum[1]+=points[j][1];}return sum.map((v,k)=>lerp(p[k],v/adj[i].size,.65));});
  let t=1;while(t>1/64){const trial=points.map((p,i)=>p.map((v,k)=>lerp(v,targets[i][k],t)));if(faces.every(f=>cross2(...f.map(i=>trial[i]))>1e-13)){points=trial;break;}t/=2;}
  if(round%4===0){check();await pause();}
 }flip();return{points,faces};
}
function loopsOf(faces){const edges=new Map();for(const f of faces)for(let i=0;i<3;i++){const a=f[i],b=f[(i+1)%3],k=edgeKey(a,b);edges.set(k,(edges.get(k)||0)+1);}const adj=new Map();for(const[k,n]of edges)if(n===1){const[a,b]=k.split(':').map(Number);if(!adj.has(a))adj.set(a,[]);if(!adj.has(b))adj.set(b,[]);adj.get(a).push(b);adj.get(b).push(a);}
 const seen=new Set(),loops=[];for(const start of adj.keys()){if(seen.has(start))continue;let p=start,previous=-1,ids=[];while(!seen.has(p)){seen.add(p);ids.push(p);const neighbors=adj.get(p);if(neighbors.length!==2)throw new Error('衣片开口出现分叉');const n=neighbors.find(x=>x!==previous);previous=p;p=n;}if(p!==start)throw new Error('衣片开口未闭合');loops.push(ids);}return loops;
}

export async function buildCapPanels(references,{onProgress,signal,armholes,construction='offshoulder'}={}){
 const check=()=>{if(signal?.aborted)throw new DOMException('已取消生成','AbortError');};check();
 const front=references.find(r=>r.side==='FRONT');if(!front)throw new Error('连肩衣片需要正面参考');
 const box=bbox(front.mask,front.width,front.height),height=1.35,scale=(box.y1-box.y0)/height,cx=(box.x0+box.x1)/2;
 let outline=simplify(contour(front.mask,front.width,front.height),construction!=='offshoulder').map(([x,y])=>[(x-.5-cx)/scale,(box.y1-y+.5)/scale]);
 if(signedArea(outline)<0)outline.reverse();
 const minX=Math.min(...outline.map(p=>p[0])),maxX=Math.max(...outline.map(p=>p[0])),maxY=Math.max(...outline.map(p=>p[1])),minY=Math.min(...outline.map(p=>p[1]));
 // Normalize only relative scale; preserve the input silhouette aspect.
 outline=outline.map(p=>[p[0]*height/(maxY-minY),(p[1]-minY)*height/(maxY-minY)]);
 const x0=Math.min(...outline.map(p=>p[0])),x1=Math.max(...outline.map(p=>p[0])),width=x1-x0;
 const crew=construction==='round-neck'||construction==='symmetric-top',confirmedRound=construction==='round-neck',middle=(x0+x1)/2;
 // A round-neck shirt is not a slanted cap-sleeve pattern. Identify its two
 // collar endpoints and independent cuff edges from the front boundary.
 const upper=(sign)=>outline.filter(p=>(p[0]-middle)*sign>width*.06&&(p[0]-middle)*sign<width*.32).reduce((a,p)=>p[1]>a[1]?p:a,[middle+sign*width*.16,0]);
 const crewLeft=upper(-1),crewRight=upper(1);
 const high=outline.reduce((best,p)=>p[1]>best[1]?p:best),highOnRight=high[0]>(x0+x1)/2;
 const lowCandidates=outline.filter(p=>{const fraction=(p[0]-x0)/width;return fraction>(highOnRight?.10:.66)&&fraction<(highOnRight?.34:.90)&&p[1]>height*.73;});
 const low=lowCandidates.reduce((best,p)=>p[1]>best[1]?p:best,lowCandidates[0]||[x0+width*(highOnRight?.22:.78),height*.84]);
 const cuffTop=height*(armholes?.topFraction??.80),cuffBottom=height*(armholes?.bottomFraction??.52);
 // Insert exact opening endpoints so classifications cannot stop inside an edge.
 const split=[];for(let i=0;i<outline.length;i++){const a=outline[i],b=outline[(i+1)%outline.length],cuts=[0];
  for(const y of(crew?[]:[cuffTop,cuffBottom])){const t=(y-a[1])/(b[1]-a[1]);if(t>1e-7&&t<1-1e-7)cuts.push(t);}
  cuts.sort((a,b)=>a-b);for(const t of cuts)split.push([lerp(a[0],b[0],t),lerp(a[1],b[1],t)]);
 }outline=split;
 const closest=point=>outline.reduce((best,p,i)=>Math.hypot(p[0]-point[0],p[1]-point[1])<Math.hypot(outline[best][0]-point[0],outline[best][1]-point[1])?i:best,0);
 const lowId=closest(crew?crewLeft:low),highId=closest(crew?crewRight:high),arc=(a,b)=>{const ids=[];for(let i=a;i!==b;i=(i+1)%outline.length)ids.push(i);return ids;};
 const neckArcs=[arc(lowId,highId),arc(highId,lowId)];
 neckArcs.sort((a,b)=>Math.min(...b.map(i=>outline[i][1]))-Math.min(...a.map(i=>outline[i][1])));
 const neckEdges=new Set(neckArcs[0]);
 const hemCandidates=crew?outline.flatMap((a,i)=>a[1]<height*.28?[i]:[]):outline.flatMap((a,i)=>{const j=(i+1)%outline.length,b=outline[j];return Math.max(a[1],b[1])<height*.12&&Math.abs(a[0]-b[0])>Math.abs(a[1]-b[1])*2?[i,j]:[];});
 if(hemCandidates.length<2)throw new Error('无法可靠确定下摆开口，请补充完整正面图');
 const hemLeft=hemCandidates.reduce((best,i)=>outline[i][0]<outline[best][0]?i:best),hemRight=hemCandidates.reduce((best,i)=>outline[i][0]>outline[best][0]?i:best);
 const hemArcs=[arc(hemLeft,hemRight),arc(hemRight,hemLeft)];
 hemArcs.sort((a,b)=>Math.max(...a.map(i=>outline[i][1]))-Math.max(...b.map(i=>outline[i][1])));
 const hemEdges=new Set(hemArcs[0]);
 const cuffs=new Map(),cuffRanges={};
 if(crew){
  const rows=[.22,.28,.34].map(t=>polygonRow(outline,t*height));
  const bodyHalf=rows.reduce((s,r)=>s+(r[1]-r[0])/2,0)/rows.length;
  for(const sign of[-1,1]){
   const name=sign>0?'left_cuff':'right_cuff';
   const tip=outline.reduce((best,p,i)=>(p[0]-middle)*sign>(outline[best][0]-middle)*sign?i:best,0);
   const paths=[-1,1].map(direction=>{const edges=[],ids=[tip];let current=tip;
    for(let step=0;step<outline.length/2;step++){const next=(current+direction+outline.length)%outline.length,p=outline[next];
     if(p[1]>outline[tip][1]+height*.012)break;
     edges.push(direction>0?current:next);ids.push(next);current=next;
     if(Math.abs(p[0]-middle)<=bodyHalf*1.035||p[1]<outline[tip][1]-height*.22)break;
    }return{edges,ids};
   }).filter(p=>p.edges.length&&p.ids.some(i=>outline[i][1]<outline[tip][1]-height*.025));
   paths.sort((a,b)=>b.ids.length-a.ids.length);const selected=paths[0];
   const extension=selected?Math.abs(outline[tip][0]-middle)-Math.min(...selected.ids.map(i=>Math.abs(outline[i][0]-middle))):0;
   if(!selected||extension<width*.045)throw new Error('未识别到两侧独立短袖袖口；无袖或袖子贴身遮挡的图片暂不支持，请使用袖子自然展开的正面图');
   selected.edges.forEach(i=>cuffs.set(i,name));
   cuffRanges[name]={bottom:Math.min(...selected.ids.map(i=>outline[i][1])),top:Math.max(...selected.ids.map(i=>outline[i][1]))};
  }
 }
 const labels=outline.map((a,i)=>{const b=outline[(i+1)%outline.length],x=(a[0]+b[0])/2,y=(a[1]+b[1])/2;
  if(neckEdges.has(i))return'neck';
  if(hemEdges.has(i))return'hem';
  if(crew)return cuffs.get(i)||'seam';
  if(y>=cuffBottom-1e-7&&y<=cuffTop+1e-7)return x<(x0+x1)/2?'right_cuff':'left_cuff';
  return'seam';
 });
 const hemOutlineIds=new Set([...hemEdges].flatMap(i=>[i,(i+1)%outline.length]));
 const hemOutline=[...hemOutlineIds].map(i=>outline[i]),hemOutlineMin=Math.min(...hemOutline.map(p=>p[0])),hemOutlineMax=Math.max(...hemOutline.map(p=>p[0]));
 const hemCore=Array.from({length:17},(_,j)=>{const x=lerp(hemOutlineMin,hemOutlineMax,.15+j*.70/16),ys=[];
  for(const i of hemEdges){const a=outline[i],b=outline[(i+1)%outline.length];if(Math.abs(a[0]-b[0])>1e-8&&x>=Math.min(a[0],b[0])&&x<=Math.max(a[0],b[0]))ys.push(lerp(a[1],b[1],(x-a[0])/(b[0]-a[0])));}
  return ys.length?Math.min(...ys):Infinity;
 });
 const hemCoreRange=Math.max(...hemCore)-Math.min(...hemCore);
 const hemAdjustment=Math.max(...hemOutline.map(p=>p[1]));
 const levelHem=crew&&hemAdjustment<height*.08&&hemCoreRange<height*.02;
 let points=outline.map(p=>[...p]),faces=ears(points),boundary=new Map();for(let i=0;i<outline.length;i++)boundary.set(edgeKey(i,(i+1)%outline.length),labels[i]);
 // Keep the final model plus inner layer below the template mesh limits.
 const rounds=faces.length*256*2<24000?4:3;
 for(let round=0;round<rounds;round++){
  const mids=new Map(),nextFaces=[],nextBoundary=new Map();
  const mid=(a,b)=>{const key=edgeKey(a,b);if(mids.has(key))return mids.get(key);const id=points.length;points.push(points[a].map((v,k)=>(v+points[b][k])/2));mids.set(key,id);const label=boundary.get(key);if(label){nextBoundary.set(edgeKey(a,id),label);nextBoundary.set(edgeKey(id,b),label);}return id;};
  for(const[a,b,c]of faces){const ab=mid(a,b),bc=mid(b,c),ca=mid(c,a);nextFaces.push([a,ab,ca],[ab,b,bc],[ca,bc,c],[ab,bc,ca]);}
  faces=nextFaces;boundary=nextBoundary;check();await pause();
 }
 ({points,faces}=await regularizePanels(points,faces,boundary,check));
 const vertexLabels=new Map();for(const[k,label]of boundary){for(const i of k.split(':').map(Number)){if(!vertexLabels.has(i))vertexLabels.set(i,new Set());vertexLabels.get(i).add(label);}}
 // A triangulation diagonal between two sewn boundary vertices must not be
 // welded as a surface edge on both sheets (that would give four incident
 // faces). Insert a separate interior midpoint on each such diagonal.
 const diagonals=new Map();for(const f of faces)for(let k=0;k<3;k++){const a=f[k],b=f[(k+1)%3],key=edgeKey(a,b);if(!boundary.has(key)&&vertexLabels.get(a)?.has('seam')&&vertexLabels.get(b)?.has('seam'))diagonals.set(key,[a,b]);}
 for(const[a,b]of diagonals.values()){const m=points.length;points.push(points[a].map((v,k)=>(v+points[b][k])/2));const next=[];
  for(const f of faces){let k=-1;for(let j=0;j<3;j++)if((f[j]===a&&f[(j+1)%3]===b)||(f[j]===b&&f[(j+1)%3]===a))k=j;
   if(k<0)next.push(f);else{const x=f[k],y=f[(k+1)%3],z=f[(k+2)%3];next.push([x,m,z],[m,y,z]);}}
  faces=next;
 }
 // The sewing safeguard above inserts new interior vertices. They must also
 // be relaxed before thickness solving, without recreating seam-to-seam
 // interior chords. Otherwise one tiny midpoint triangle produces a fishscale.
 if(crew)({points,faces}=await regularizePanels(points,faces,boundary,check,{rounds:18,avoidBoundaryChords:true}));
 const neighbors=points.map(()=>new Map()),mass=new Float64Array(points.length);
 for(const f of faces){const area=cross2(...f.map(i=>points[i]))/2;for(let i=0;i<3;i++){
   const a=f[i],b=f[(i+1)%3],c=f[(i+2)%3],u=points[a].map((v,k)=>v-points[c][k]),v=points[b].map((x,k)=>x-points[c][k]);
   const cot=(u[0]*v[0]+u[1]*v[1])/Math.max(area*2,1e-14)/2;
   neighbors[a].set(b,(neighbors[a].get(b)||0)+cot);neighbors[b].set(a,(neighbors[b].get(a)||0)+cot);mass[a]+=area/3;
 }}
 for(const links of neighbors)for(const[j,w]of links)links.set(j,Math.max(w,1e-7));
 const depth=sideDepth(references,height,{supportedTorso:crew}),centerAt=y=>interpolate(depth.center,y/height*128),ampAt=y=>interpolate(depth.front,y/height*128);
 const curves=new Map();for(const name of['neck','hem','left_cuff','right_cuff']){const ids=[...vertexLabels].filter(([i,ls])=>ls.has(name)).map(([i])=>i);curves.set(name,ids);}
 const hemPoints=curves.get('hem').map(i=>points[i]),hemMinX=Math.min(...hemPoints.map(p=>p[0])),hemMaxX=Math.max(...hemPoints.map(p=>p[0]));
 const hemMaxY=Math.max(...hemPoints.map(p=>p[1]));
 const supportRows=Array.from({length:129},(_,i)=>polygonRow(outline,clamp(i/128*height,hemMaxY+height*.005,height-.001)));
 const supportLeft=gaussian(supportRows.map(r=>r[0]),3.5),supportRight=gaussian(supportRows.map(r=>r[1]),3.5);
 const rowShape=(x,y)=>{const[a,b]=crew?[interpolate(supportLeft,y/height*128),interpolate(supportRight,y/height*128)]:polygonRow(outline,clamp(y,hemMaxY+height*.005,height-.001));const u=clamp((x-a)/Math.max(.02,b-a),0,1);return Math.sqrt(Math.max(0,1-(2*u-1)**2));};
 const boundaryValue=(i,sign)=>{const[x,y]=points[i],ls=vertexLabels.get(i),center=centerAt(y);if(ls.has('seam'))return center;
  if(ls.has('hem')){const u=clamp((x-hemMinX)/(hemMaxX-hemMinX),0,1);return center+sign*ampAt(y)*Math.sqrt(Math.max(0,1-(2*u-1)**2));}
  if(ls.has('neck')){const a=crew?crewLeft:low,b=crew?crewRight:high,span=b[0]-a[0],u=clamp((x-a[0])/(Math.abs(span)>.01?span:(highOnRight?.01:-.01)),0,1);return center+sign*height*(crew?.10:.11)*Math.sin(Math.PI*u);}
  const cuff=crew?cuffRanges[ls.has('left_cuff')?'left_cuff':'right_cuff']:null;
  const u=clamp((y-(cuff?.bottom??cuffBottom))/((cuff?.top??cuffTop)-(cuff?.bottom??cuffBottom)),0,1),s=crew?Math.sqrt(Math.max(0,1-(2*u-1)**2)):Math.sin(Math.PI*u);return center-(crew?0:height*.04*s)+sign*height*(crew?.10:(armholes?.halfDepthFraction??.075))*s;
 };
 const baseAmplitude=[...depth.front];let fields=[],solveReport=[];
 // A screened membrane is slightly narrower than its support target near
 // sewn edges. Calibrate only the broad lower-body support, using actual
 // triangle sections; cuff and neckline boundary sizes remain unchanged.
 for(let pass=0;pass<(crew?1:3);pass++){
  const targets=points.map(([x,y])=>{const shape=rowShape(x,y);return[centerAt(y)-ampAt(y)*shape,centerAt(y)+ampAt(y)*shape];});fields=[];
  if(crew){
   // Solve squared thickness, rather than Z. Its square root has a vertical
   // tangent at a sewn edge, so the two sheets meet as a rounded cloth shell
   // instead of a knife ridge. Positive cotangent weights preserve positivity.
   for(const sign of[1,-1]){
    const target=targets.map((p,i)=>(p[sign===1?1:0]-centerAt(points[i][1]))**2);
    const q=points.map((p,i)=>vertexLabels.has(i)?(boundaryValue(i,sign)-centerAt(p[1]))**2:target[i]);
    let residual=Infinity,iterations=0;
    for(;iterations<3000;iterations++){
     residual=0;for(let i=0;i<points.length;i++){if(vertexLabels.has(i))continue;let sum=0,weight=0;for(const[j,w]of neighbors[i]){sum+=q[j]*w;weight+=w;}
      const screen=mass[i]*120,next=(sum+screen*target[i])/(weight+screen),updated=Math.max(0,q[i]+1.35*(next-q[i]));residual=Math.max(residual,Math.abs(updated-q[i]));q[i]=updated;
     }
     if(iterations%80===0){check();onProgress?.({stage:'panels',progress:.35+.4*(fields.length+Math.min(iterations/800,1))/2,message:'平滑胸背支撑、袖口截面与缝边切线'});await pause();}
     if(residual<height*height*1e-8)break;
    }
    fields.push(q.map((v,i)=>vertexLabels.has(i)?boundaryValue(i,sign):centerAt(points[i][1])+sign*Math.sqrt(Math.max(0,v))));
    solveReport.push({iterations:iterations+1,residual,converged:residual<height*height*1e-8});
   }
   break;
  }
  for(const sign of[1,-1]){let z=points.map((p,i)=>vertexLabels.has(i)?boundaryValue(i,sign):targets[i][sign===1?1:0]);
   for(let iteration=0;iteration<240;iteration++){const next=[...z];for(let i=0;i<points.length;i++){if(vertexLabels.has(i))continue;let sum=0,weight=0;for(const[j,w]of neighbors[i]){sum+=z[j]*w;weight+=w;}const screen=mass[i]*220;next[i]=(sum+screen*targets[i][sign===1?1:0])/(weight+screen);}z=next;
    if(iteration%60===0){check();onProgress?.({stage:'panels',progress:.35+.40*(pass+(fields.length+iteration/240)/2)/3,message:'按衣片结构建立胸背支撑与独立袖口'});await pause();}
   }fields.push(z);
  }
  if(crew||pass===2)break;
  const gains=Array.from({length:129},(_,i)=>{const fraction=clamp(i/128,.025,.975),y=fraction*height;let lo=Infinity,hi=-Infinity;
   for(const f of faces)for(let j=0;j<3;j++){const a=f[j],b=f[(j+1)%3];if((points[a][1]<=y&&points[b][1]>y)||(points[b][1]<=y&&points[a][1]>y)){
    const t=(y-points[a][1])/(points[b][1]-points[a][1]);for(const z of fields){const value=lerp(z[a],z[b],t);lo=Math.min(lo,value);hi=Math.max(hi,value);}
   }}
   const ratio=clamp(baseAmplitude[i]/Math.max(.01,(hi-lo)/2),.95,1.22),fade=clamp((.62-fraction)/.16,0,1);
   return lerp(1,ratio,fade);
  });
  const smooth=gaussian(gains,3);depth.front=depth.front.map((v,i)=>clamp(v*smooth[i],baseAmplitude[i]*.95,baseAmplitude[i]*1.28));
 }
 const vertices=[],outputFaces=[],frontIds=[],backIds=[];
 for(let i=0;i<points.length;i++){frontIds[i]=vertices.length;const[x,y]=points[i];
  const neckU=crew?clamp((x-crewLeft[0])/(crewRight[0]-crewLeft[0]),0,1):0;
  const collar=confirmedRound?Math.sin(Math.PI*neckU)*height*.105*clamp((y/height-.76)/.24,0,1)**2:0;
  vertices.push([x,y-collar,fields[0][i]]);}
 for(let i=0;i<points.length;i++){if(vertexLabels.get(i)?.has('seam'))backIds[i]=frontIds[i];else{backIds[i]=vertices.length;vertices.push([...points[i],fields[1][i]]);}}
 for(const f of faces){outputFaces.push(f.map(i=>frontIds[i]));outputFaces.push([...f].reverse().map(i=>backIds[i]));}
 const panelFaceCount=outputFaces.length;
 if(levelHem){
  const hemLoop=loopsOf(outputFaces).find(ids=>ids.reduce((s,i)=>s+vertices[i][1],0)/ids.length<height*.28);
  const lowered=new Map(hemLoop.map(i=>{if(vertices[i][1]<1e-8)return[i,i];const id=vertices.length;vertices.push([vertices[i][0],0,vertices[i][2]]);return[i,id];}));
  const directed=new Set(outputFaces.flatMap(f=>f.map((a,k)=>`${a}:${f[(k+1)%3]}`)));
  for(let i=0;i<hemLoop.length;i++){let a=hemLoop[i],b=hemLoop[(i+1)%hemLoop.length];if(!directed.has(`${a}:${b}`))[a,b]=[b,a];const na=lowered.get(a),nb=lowered.get(b);
   for(const f of[[b,a,na],[b,na,nb]])if(new Set(f).size===3)outputFaces.push(f);
  }
 }
 const loops=loopsOf(outputFaces);if(loops.length!==4)throw new Error(`衣片应有4个实际开口，当前为${loops.length}`);
 const openings={};for(const ids of loops){const y=ids.reduce((s,i)=>s+vertices[i][1],0)/ids.length,x=ids.reduce((s,i)=>s+vertices[i][0],0)/ids.length;
  const name=y<height*(crew?.28:.1)?'hem':y>height*.8?'neck':x>0?'left_cuff':'right_cuff';if(openings[name])throw new Error('衣片开口位置不明确');openings[name]=ids;
 }
 const semantic_regions={front:frontIds,back:backIds,torso:vertices.map((_,i)=>i),seams:[...vertexLabels].filter(([i,ls])=>ls.has('seam')).map(([i])=>frontIds[i])};
 let structureChecks;
 if(crew){
  const sheetOrientation=outputFaces.slice(0,panelFaceCount).every((face,i)=>{const[a,b,c]=face.map(j=>vertices[j]);return(i%2===0?1:-1)*cross2(a,b,c)>1e-12;});
  const areas=Object.values(openings).map(ids=>{const normal=[0,0,0];for(let i=0;i<ids.length;i++){const a=vertices[ids[i]],b=vertices[ids[(i+1)%ids.length]];
   for(let k=0;k<3;k++)normal[k]+=(a[(k+1)%3]-b[(k+1)%3])*(a[(k+2)%3]+b[(k+2)%3]);
  }return Math.hypot(...normal)/2;});
  const cuffDepths=['left_cuff','right_cuff'].map(name=>{const zs=openings[name].map(i=>vertices[i][2]);return Math.max(...zs)-Math.min(...zs);});
  structureChecks={sheetOrientation,minOpeningArea:Math.min(...areas),minCuffDepth:Math.min(...cuffDepths),solverConverged:solveReport.every(r=>r.converged)};
  if(!sheetOrientation||structureChecks.minOpeningArea<height*height*.005||structureChecks.minCuffDepth<height*.08||!structureChecks.solverConverged)
   throw new Error('衣片曲面或袖口形态未通过检查，已停止采用该模型；请核对完整、自然展开的参考图');
 }
 return{vertices:vertices.map(v=>v.map(x=>Math.round(x*1e7)/1e7)),faces:outputFaces,openings,semantic_regions,params:{body_length:height,torso_width:width*.68,depth:height*.38,sleeve_length:.12,neck_width:crew?crewRight[0]-crewLeft[0]:Math.abs(high[0]-low[0])},geometryVersion:crew?`image-defined-${confirmedRound?'round-neck':'symmetric-top'}-panels/1.0`:'image-defined-cap-panels/1.0',
  panelEvidence:{pattern:'front-reference-contour',construction,backSurface:'shared-pattern-with-inferred-depth',armholeTop:cuffTop,armholeBottom:cuffBottom,cuffRanges:crew?cuffRanges:undefined,levelHem:levelHem?{method:'near-level-reference-common-opening',maximumAdjustment:hemAdjustment,construction:'boundary-extension-with-original-panels-preserved'}:undefined,depthSupport:depth.evidence,fieldMethod:crew?'positive-squared-thickness-screened-membrane':'screened-membrane',solve:crew?solveReport:undefined,structureChecks,subdivisionRounds:rounds,outlineVertices:outline.length,sideDepthMaxGain:Math.max(...depth.front.map((v,i)=>v/baseAmplitude[i]))}};
}
