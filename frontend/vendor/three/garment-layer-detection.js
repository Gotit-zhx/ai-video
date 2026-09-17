/** Conservative two-tone layer evidence. This detects image components, not garment construction truth. */
const median = values => { const sorted = values.sort((a,b) => a-b); return sorted[Math.floor(sorted.length/2)] || 0; };
const clamp = (x,a,b) => Math.max(a,Math.min(b,x));
function components(mask,width,height) {
  const visited=new Uint8Array(mask.length),queue=new Int32Array(mask.length),results=[];
  for(let i=0;i<mask.length;i++) if(mask[i]&&!visited[i]) {
    let head=0,tail=1; queue[0]=i;visited[i]=1; const box={left:width,right:0,top:height,bottom:0};
    while(head<tail){const p=queue[head++],x=p%width,y=Math.floor(p/width);box.left=Math.min(box.left,x);box.right=Math.max(box.right,x);box.top=Math.min(box.top,y);box.bottom=Math.max(box.bottom,y);
      const add=q=>{if(mask[q]&&!visited[q]){visited[q]=1;queue[tail++]=q;}};
      if(x>0)add(p-1);if(x<width-1)add(p+1);if(y>0)add(p-width);if(y<height-1)add(p+width);
    } results.push({pixels:Array.from(queue.subarray(0,tail)),area:tail,...box});
  } return results.sort((a,b)=>b.area-a.area);
}
function colorOf(data,pixels){return [0,1,2].map(c=>median(pixels.filter((_,i)=>i%3===0).map(p=>data[p*4+c])));}
const hex = rgb => '#'+rgb.map(c=>Math.round(c).toString(16).padStart(2,'0')).join('');
function filled(mask,width,height){
 const bg=new Uint8Array(mask.length),q=new Int32Array(mask.length);let head=0,tail=0;
 const add=p=>{if(!mask[p]&&!bg[p]){bg[p]=1;q[tail++]=p;}};
 for(let x=0;x<width;x++){add(x);add((height-1)*width+x);}for(let y=0;y<height;y++){add(y*width);add(y*width+width-1);}
 while(head<tail){const p=q[head++],x=p%width;if(x)add(p-1);if(x<width-1)add(p+1);if(p>=width)add(p-width);if(p<mask.length-width)add(p+width);}
 return mask.map((v,i)=>v||!bg[i]?1:0);
}
export function detectLayeredGarment(image,foreground) {
 const {data,width,height}=image,ids=[];for(let i=0;i<foreground.mask.length;i++)if(foreground.mask[i])ids.push(i);
 const outerColor=colorOf(data,ids),chroma=Math.max(...outerColor)-Math.min(...outerColor),center=outerColor.reduce((a,b)=>a+b,0)/3;
 if(chroma<38)return {detected:false};
 const direction=outerColor.map(c=>c-center),dn=Math.hypot(...direction),colored=new Uint8Array(width*height),neutral=new Uint8Array(width*height);
 // Cool-gray textile pixels are not perfectly achromatic. Browser downsampling
 // can leave 28–35 channel differences in the same gray hem; excluding those
 // pixels fragments one visible strip into several disconnected components.
 const neutralChroma=clamp(chroma*.42,28,42);
 for(const p of ids){const rgb=[data[p*4],data[p*4+1],data[p*4+2]],c=Math.max(...rgb)-Math.min(...rgb),m=rgb.reduce((a,b)=>a+b,0)/3;
   const vector=rgb.map(v=>v-m),similarity=vector.reduce((s,v,i)=>s+v*direction[i],0)/Math.max(1,Math.hypot(...vector)*dn);
   if(c>Math.max(14,chroma*.26)&&similarity>.75)colored[p]=1;else if(c<neutralChroma&&m>35&&m<240)neutral[p]=1;
 }
 const outer=components(colored,width,height)[0];if(!outer||outer.area<ids.length*.55)return {detected:false};
 const outerHeight=outer.bottom-outer.top+1,parts=components(neutral,width,height);
 const strap=parts.find(p=>p.area>ids.length*.002&&p.area<ids.length*.16&&p.top<outer.top+outerHeight*.23&&p.bottom<outer.top+outerHeight*.45&&(p.bottom-p.top)>(p.right-p.left)*.65);
 const hem=parts.find(p=>p.area>ids.length*.003&&p.top>outer.top+outerHeight*.70&&(p.right-p.left)>(outer.right-outer.left)*.55);
 if(!strap||!hem)return {detected:false};
 const mask=new Uint8Array(width*height);for(const p of outer.pixels)mask[p]=1;
 const square=Math.max(width,height),dx=(square-width)/2,dy=(square-height)/2;
 const frame=p=>({left:(p.left+dx)/square,top:(p.top+dy)/square,width:(p.right-p.left+1)/square,height:(p.bottom-p.top+1)/square});
 const innerColor=colorOf(data,[...strap.pixels,...hem.pixels]);
 const rowWidths=[];
 for(let y=strap.top+2;y<strap.bottom-2;y++){const xs=strap.pixels.filter(p=>Math.floor(p/width)===y).map(p=>p%width);if(xs.length>3)rowWidths.push(Math.max(...xs)-Math.min(...xs)+1);}
 rowWidths.sort((a,b)=>a-b);
 // A slanted ribbon's bbox includes its displacement and sometimes the rear
 // segment. A narrow upper-row span estimates the actual strip width.
 const strapWidthFraction=clamp((rowWidths[Math.floor(rowWidths.length*.1)]||strap.right-strap.left+1)/outerHeight,.035,.12);
 const strapSide=(strap.left+strap.right)<(outer.left+outer.right)?'RIGHT':'LEFT',strapHeight=strap.bottom-strap.top+1,stripPixels=strapWidthFraction*outerHeight,samples=[];
 for(let y=Math.ceil(strap.top+strapHeight*.25);y<strap.bottom-strapHeight*.1;y++){
   const xs=strap.pixels.filter(p=>Math.floor(p/width)===y).map(p=>p%width);if(xs.length<4)continue;
   samples.push([y,strapSide==='RIGHT'?Math.min(...xs)+stripPixels/2:Math.max(...xs)-stripPixels/2]);
 }
 const my=samples.reduce((s,p)=>s+p[0],0)/Math.max(1,samples.length),mx=samples.reduce((s,p)=>s+p[1],0)/Math.max(1,samples.length);
 const slope=samples.reduce((s,p)=>s+(p[0]-my)*(p[1]-mx),0)/Math.max(1,samples.reduce((s,p)=>s+(p[0]-my)**2,0));
 const topY=strap.top+strapHeight*.10,bottomY=strap.bottom-strapHeight*.025;
 const spec={detected:true,outerColor:hex(outerColor),innerColor:hex(innerColor),strapSide,strapBox:frame(strap),outerFrame:frame(outer),
   strapTopX:(mx+slope*(topY-my)+dx)/square,strapTopY:(topY+dy)/square,strapBottomX:(mx+slope*(bottomY-my)+dx)/square,strapBottomY:(bottomY+dy)/square,
   strapWidthFraction,hemFraction:clamp((hem.bottom-outer.bottom)/outerHeight,.018,.12),frameSpace:'square-letterboxed',evidence:'two-color-separated-upper-strap-and-lower-hem',inferredConstruction:true};
 return {detected:true,spec,outerMask:filled(mask,width,height)};
}

/** Apply a confirmed front color family to another view; hidden layers still require reference review. */
export function outerMaskForColor(image,foreground,outerHex){
 const rgb=[1,3,5].map(i=>parseInt(outerHex.slice(i,i+2),16)),center=rgb.reduce((a,b)=>a+b,0)/3,dir=rgb.map(c=>c-center),dn=Math.hypot(...dir);
 const mask=new Uint8Array(image.width*image.height);
 for(let p=0;p<mask.length;p++)if(foreground.mask[p]){const color=[0,1,2].map(c=>image.data[p*4+c]),m=color.reduce((a,b)=>a+b,0)/3,vector=color.map(c=>c-m),chroma=Math.max(...color)-Math.min(...color);
  const cosine=vector.reduce((s,v,i)=>s+v*dir[i],0)/Math.max(1,dn*Math.hypot(...vector));if(chroma>14&&cosine>.75)mask[p]=1;
 }
 const largest=components(mask,image.width,image.height)[0];if(!largest)return null;
 mask.fill(0);for(const p of largest.pixels)mask[p]=1;return filled(mask,image.width,image.height);
}
