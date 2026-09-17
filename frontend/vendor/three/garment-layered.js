/** Adds actual underlayer cloth and a finite-width shoulder strap to a fitted outer shell.
 * Source observations describe image-space placement; hidden underlayer construction
 * remains a declared hypothesis. No photograph texture substitutes for these meshes.
 */
const clamp=(v,a,b)=>Math.max(a,Math.min(b,v));
const mix=(a,b,t)=>a+(b-a)*t;
const cross=(a,b)=>[a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]];
const unit=v=>{const n=Math.hypot(...v);if(n<1e-12)throw new Error('肩带方向无效');return v.map(x=>x/n);};
const color=(value,fallback)=>/^#[0-9a-f]{6}$/i.test(value||'')?value:fallback;
function bounds(vertices){const lo=[Infinity,Infinity,Infinity],hi=[-Infinity,-Infinity,-Infinity];for(const v of vertices)for(let k=0;k<3;k++){lo[k]=Math.min(lo[k],v[k]);hi[k]=Math.max(hi[k],v[k]);}return {lo,hi};}
function section(vertices,faces,y){
 let x0=Infinity,x1=-Infinity,z0=Infinity,z1=-Infinity;
 for(const face of faces)for(let i=0;i<3;i++){
  const a=vertices[face[i]],b=vertices[face[(i+1)%3]];
  if(Math.min(a[1],b[1])>y||Math.max(a[1],b[1])<y)continue;
  const t=Math.abs(a[1]-b[1])<1e-9?0:(y-a[1])/(b[1]-a[1]);
  const x=mix(a[0],b[0],t),z=mix(a[2],b[2],t);
  x0=Math.min(x0,x);x1=Math.max(x1,x);z0=Math.min(z0,z);z1=Math.max(z1,z);
 }
 return Number.isFinite(x0)&&x1>x0&&z1>z0?{cx:(x0+x1)/2,cz:(z0+z1)/2,rx:(x1-x0)/2,rz:(z1-z0)/2}:null;
}
function hemRing(vertices,faces){
 const counts=new Map();for(const f of faces)for(let i=0;i<3;i++){const a=f[i],b=f[(i+1)%3],key=a<b?`${a}:${b}`:`${b}:${a}`;counts.set(key,(counts.get(key)||0)+1);}
 const links=new Map();for(const [edge,n]of counts)if(n===1){const [a,b]=edge.split(':').map(Number);if(!links.has(a))links.set(a,[]);if(!links.has(b))links.set(b,[]);links.get(a).push(b);links.get(b).push(a);}
 const seen=new Set(),loops=[];for(const start of links.keys())if(!seen.has(start)){const ids=[],stack=[start];while(stack.length){const id=stack.pop();if(seen.has(id))continue;seen.add(id);ids.push(id);for(const n of links.get(id)||[])if(!seen.has(n))stack.push(n);}if(ids.length>=3)loops.push(ids.map(i=>vertices[i]));}
 loops.sort((a,b)=>a.reduce((n,v)=>n+v[1],0)/a.length-b.reduce((n,v)=>n+v[1],0)/b.length);
 return loops[0]||null;
}
function surfaceSampler(vertices,faces){
 const triangles=faces.map(f=>{const v=f.map(i=>vertices[i]);return {v,x0:Math.min(...v.map(p=>p[0])),x1:Math.max(...v.map(p=>p[0])),y0:Math.min(...v.map(p=>p[1])),y1:Math.max(...v.map(p=>p[1]))};});
 const cache=new Map();
 function sectionEdges(y){const key=y.toFixed(5);if(cache.has(key))return cache.get(key);const result=[];
  for(const t of triangles){if(y<t.y0||y>t.y1)continue;const hits=[];
   for(let i=0;i<3;i++){const a=t.v[i],b=t.v[(i+1)%3];if((a[1]<=y&&b[1]>y)||(b[1]<=y&&a[1]>y)){const k=(y-a[1])/(b[1]-a[1]);hits.push([mix(a[0],b[0],k),mix(a[2],b[2],k)]);}}
   if(hits.length===2)result.push(hits);
  }cache.set(key,result);return result;
 }
 return {radial(cx,cz,dx,dz,y){let nearest=Infinity;
  for(const [a,b]of sectionEdges(y)){const ex=b[0]-a[0],ez=b[1]-a[1],ax=a[0]-cx,az=a[1]-cz,det=dx*ez-dz*ex;if(Math.abs(det)<1e-12)continue;
   const ray=(ax*ez-az*ex)/det,edge=(ax*dz-az*dx)/det;if(ray>0&&edge>=0&&edge<=1)nearest=Math.min(nearest,ray);
  }return Number.isFinite(nearest)?nearest:null;
 },depth(x,y){let front=-Infinity,back=Infinity;
  for(const t of triangles){if(x<t.x0||x>t.x1||y<t.y0||y>t.y1)continue;const [a,b,c]=t.v;
   const d=(b[1]-c[1])*(a[0]-c[0])+(c[0]-b[0])*(a[1]-c[1]);if(Math.abs(d)<1e-12)continue;
   const u=((b[1]-c[1])*(x-c[0])+(c[0]-b[0])*(y-c[1]))/d,v=((c[1]-a[1])*(x-c[0])+(a[0]-c[0])*(y-c[1]))/d;
   if(u>=-1e-8&&v>=-1e-8&&u+v<=1+1e-8){const z=a[2]*u+b[2]*v+c[2]*(1-u-v);front=Math.max(front,z);back=Math.min(back,z);}
  }return front>-Infinity?{front,back}:null;
 }};
}
export function addGarmentLayers(model,spec={}){
 if(!spec.detected)return model;
 if(!Array.isArray(model.vertices)||!Array.isArray(model.faces))throw new Error('缺少可用的外层网格');
 const vertices=model.vertices.map(v=>[...v]),faces=model.faces.map(f=>[...f]),outerFaceCount=faces.length;
 const box=bounds(vertices),height=box.hi[1]-box.lo[1],surface=surfaceSampler(model.vertices,model.faces);
 if(!(height>.1))throw new Error('外层服装高度无效');
 const hemY=Number.isFinite(spec.outerHemY)?spec.outerHemY:box.lo[1];
 const hemHeight=clamp(spec.hemFraction??.055,.018,.12)*height;
 const bodyTop=hemY+height*.57,thickness=height*.0018;
 const hem=hemRing(model.vertices,model.faces);
 const hemBounds=hem?bounds(hem):null;
 const bodySection=hemBounds?{cx:(hemBounds.lo[0]+hemBounds.hi[0])/2,cz:(hemBounds.lo[2]+hemBounds.hi[2])/2,rx:(hemBounds.hi[0]-hemBounds.lo[0])/2,rz:(hemBounds.hi[2]-hemBounds.lo[2])/2}:section(model.vertices,model.faces,hemY+height*.12);
 if(!bodySection)throw new Error('无法确定内层下摆的衣身截面');
 const angles=64,rows=17,innerStart=vertices.length;
 const ring=(hem||[]).map(v=>({angle:(Math.atan2(v[2]-bodySection.cz,v[0]-bodySection.cx)+Math.PI*2)%(Math.PI*2),v})).sort((a,b)=>a.angle-b.angle);
 const hemPoint=theta=>{
  if(!ring.length)return [bodySection.cx+bodySection.rx*Math.cos(theta),hemY,bodySection.cz+bodySection.rz*Math.sin(theta)];
  let j=ring.findIndex(p=>p.angle>=theta);if(j<0)j=0;const a=ring[(j+ring.length-1)%ring.length],b=ring[j];
  const before=a.angle>b.angle?a.angle-Math.PI*2:a.angle,at=theta>b.angle?theta-Math.PI*2:theta;
  const t=clamp((at-before)/Math.max(1e-9,b.angle-before),0,1);return a.v.map((v,k)=>mix(v,b.v[k],t));
 };
 // Follow the actual outer hem, including its photographed diagonal drape.
 // The center stays hollow; the two cloth surfaces meet only at thin end rims.
 for(let wall=0;wall<2;wall++)for(let row=0;row<rows;row++){
  const t=row/(rows-1),sampleY=mix(hemY,bodyTop,t);
  const shape=section(model.vertices,model.faces,clamp(sampleY,hemY+height*.16,bodyTop))||bodySection;
  for(let a=0;a<angles;a++){
   const theta=a/angles*Math.PI*2,edge=hemPoint(theta),y=mix(edge[1]-hemHeight,bodyTop,t);
   const blend=clamp(t/.3,0,1),edgeScale=1.003-wall*thickness/Math.max(bodySection.rx,.01);
   const ex=bodySection.cx+(edge[0]-bodySection.cx)*edgeScale,ez=bodySection.cz+(edge[2]-bodySection.cz)*edgeScale;
   const rx=Math.max(height*.08,shape.rx*.971-wall*thickness),rz=Math.max(height*.035,shape.rz*.971-wall*thickness);
   let x=mix(ex,shape.cx+rx*Math.cos(theta),blend),z=mix(ez,shape.cz+rz*Math.sin(theta),blend);
   if(y>edge[1]+height*.004){const dx=x-shape.cx,dz=z-shape.cz,length=Math.hypot(dx,dz),hit=surface.radial(shape.cx,shape.cz,dx/length,dz/length,y);
    if(hit!=null){const allowed=Math.max(height*.015,hit-height*(.019+wall*.0018));if(length>allowed){x=shape.cx+dx/length*allowed;z=shape.cz+dz/length*allowed;}}}
   vertices.push([x,y,z]);
  }
 }
 const at=(wall,row,a)=>innerStart+wall*rows*angles+row*angles+(a+angles)%angles;
 const quad=(a,b,c,d,reverse=false)=>{faces.push(...(reverse?[[a,c,b],[a,d,c]]:[[a,b,c],[a,c,d]]));};
 for(let wall=0;wall<2;wall++)for(let row=0;row<rows-1;row++)for(let a=0;a<angles;a++)quad(at(wall,row,a),at(wall,row,a+1),at(wall,row+1,a+1),at(wall,row+1,a),wall===1);
 for(let a=0;a<angles;a++){
  quad(at(0,0,a+1),at(0,0,a),at(1,0,a),at(1,0,a+1));
  quad(at(0,rows-1,a),at(0,rows-1,a+1),at(1,rows-1,a+1),at(1,rows-1,a));
 }
 const innerFaceCount=faces.length-outerFaceCount;
 const calibration=model.quality?.views?.find(v=>v.side==='FRONT')?.camera;
 const measured=spec.strapBox&&calibration?.scale>0&&calibration?.rasterSize>0;
 const side=spec.strapSide==='LEFT'?1:-1;
 let strapX=bodySection.cx+side*bodySection.rx*.56;
 let strapWidth=height*.068,apex=box.hi[1]-height*.042,lean=side*height*.025,measuredCenterline=null,rearOffset=0;
 if(measured){
  const r=spec.strapBox,size=calibration.rasterSize;
  strapX=((r.left+r.width*.5)*size-calibration.cx)/calibration.scale;
  strapWidth=clamp((spec.strapWidthFraction??r.width*size/calibration.scale/height*.42)*height,height*.035,height*.11);
  apex=clamp((calibration.cy-r.top*size)/calibration.scale,bodyTop+height*.14,box.hi[1]-height*.007);
  if(Number.isFinite(spec.strapTopX)&&Number.isFinite(spec.strapBottomX)){
   strapX=(spec.strapBottomX*size-calibration.cx)/calibration.scale;
   lean=clamp((spec.strapTopX-spec.strapBottomX)*size/calibration.scale,-height*.12,height*.12);
   if(Number.isFinite(spec.strapTopY)&&Number.isFinite(spec.strapBottomY)){
    const topY=(calibration.cy-spec.strapTopY*size)/calibration.scale,bottomY=(calibration.cy-spec.strapBottomY*size)/calibration.scale;
    if(topY-bottomY>height*.025)measuredCenterline={x:strapX,y:bottomY,slope:clamp(lean/(topY-bottomY),-.65,.65)};
   }
   // A second visible ribbon segment can be wider than the front strip alone.
   // Infer its separate rear anchor from the component extent, while keeping
   // the measured front centerline fixed. Hidden anchors remain a hypothesis.
   const farEdge=side<0?r.left+r.width:r.left;
   const rearX=(farEdge*size-calibration.cx)/calibration.scale+side*strapWidth*.5;
   const offset=rearX-strapX;
   if(offset*side<0)rearOffset=clamp(offset,-height*.08,height*.08);
  }
 }
 strapX=clamp(strapX,bodySection.cx-bodySection.rx*.88,bodySection.cx+bodySection.rx*.88);
 const anchorY=bodyTop-height*.04;
 const depthShape=section(model.vertices,model.faces,anchorY)||bodySection;
 const lateral=(strapX-depthShape.cx)/Math.max(depthShape.rx,1e-6);
 const strapDepth=Math.max(height*.06,depthShape.rz*Math.sqrt(Math.max(.2,1-lateral*lateral))*.98);
 const strapStart=vertices.length,segments=48,centers=[];
 for(let i=0;i<=segments;i++){
  const angle=i/segments*Math.PI;
  const y=anchorY+(apex-anchorY)*Math.sin(angle);
  // The visible ribbon's measured slope belongs to its visible span. Applying
  // that displacement across the much longer hidden arch makes it look upright.
  let x=measuredCenterline?measuredCenterline.x+(y-measuredCenterline.y)*measuredCenterline.slope:strapX+lean*Math.sin(angle);
  if(i>segments/2&&measuredCenterline)x+=rearOffset*clamp((apex-y)/Math.max(height*.03,apex-measuredCenterline.y),0,1);
  const center=[clamp(x,box.lo[0]+height*.015,box.hi[0]-height*.015),y,depthShape.cz+strapDepth*Math.cos(angle)];
  const cover=[-.5,0,.5].map(w=>surface.depth(center[0]+w*strapWidth,center[1])).filter(Boolean);
  // A back panel visible through an open front neckline is not front coverage.
  // Only tuck the ribbon under cloth on its own side of the torso.
  if(i<=segments/2){const front=cover.filter(p=>p.front>depthShape.cz+height*.008);if(front.length)center[2]=Math.min(center[2],Math.min(...front.map(p=>p.front))-height*.022);}
  else {const back=cover.filter(p=>p.back<depthShape.cz-height*.008);if(back.length)center[2]=Math.max(center[2],Math.max(...back.map(p=>p.back))+height*.022);}
  centers.push(center);
 }
 for(let i=0;i<=segments;i++){
  const center=centers[i],before=centers[Math.max(0,i-1)],after=centers[Math.min(segments,i+1)];
  const tangent=unit(after.map((v,k)=>v-before[k]));
  const widthVector=unit([1-tangent[0]*tangent[0],-tangent[0]*tangent[1],-tangent[0]*tangent[2]]);
  const normal=unit(cross(widthVector,tangent));
  for(const [w,n]of[[-1,-1],[1,-1],[1,1],[-1,1]])vertices.push(center.map((v,k)=>v+w*strapWidth*.5*widthVector[k]+n*thickness*.5*normal[k]));
 }
 for(let i=0;i<segments;i++)for(let corner=0;corner<4;corner++){const a=strapStart+i*4+corner,b=strapStart+i*4+(corner+1)%4;quad(a,b,b+4,a+4);}
 quad(strapStart+3,strapStart+2,strapStart+1,strapStart);
 const end=strapStart+segments*4;quad(end,end+1,end+2,end+3);
 if(vertices.length>15000||faces.length>30000)throw new Error('分层模型超出模板网格上限');
 const quality={...model.quality,meshParts:[
  {start:0,count:outerFaceCount,name:'outer',color:color(spec.outerColor,'#dcc777')},
  {start:outerFaceCount,count:innerFaceCount,name:'inner',color:color(spec.innerColor,'#a6a9ad')},
  {start:outerFaceCount+innerFaceCount,count:faces.length-outerFaceCount-innerFaceCount,name:'strap',color:color(spec.innerColor,'#a6a9ad')}
 ],layeredGarment:{detected:true,strapSide:spec.strapSide==='LEFT'?'LEFT':'RIGHT',hemFraction:hemHeight/height,
    strapWidth,strapApex:apex,placementFromSource:!!measured,hiddenConstructionInferred:true,physicalSimulation:false}};
 return {...model,vertices:vertices.map(v=>v.map(n=>Math.round(n*1e7)/1e7)),faces,quality};
}
