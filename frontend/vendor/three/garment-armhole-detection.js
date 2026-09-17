/** A side-view dark oval is evidence for an armhole, not proof of hidden construction. */
const median=xs=>{xs.sort((a,b)=>a-b);return xs[Math.floor(xs.length/2)]??0;};
export function detectSideArmhole(image,foreground,outerHex){
 const {width,height,data}=image,ids=[];
 if(!width||!height||foreground?.mask?.length!==width*height)return {detected:false};
 for(let i=0;i<width*height;i++)if(foreground.mask[i])ids.push(i);
 if(ids.length<80)return {detected:false};
 const rgb=/^#[0-9a-f]{6}$/i.test(outerHex||'')?[1,3,5].map(i=>parseInt(outerHex.slice(i,i+2),16)):[0,1,2].map(c=>median(ids.filter((_,i)=>i%3===0).map(p=>data[p*4+c])));
 const average=rgb.reduce((a,b)=>a+b,0)/3,direction=rgb.map(c=>c-average),length=Math.hypot(...direction);
 if(Math.max(...rgb)-Math.min(...rgb)<26)return {detected:false};
 const material=new Uint8Array(width*height),brightness=[],box={left:width,right:0,top:height,bottom:0};
 for(const p of ids){const color=[0,1,2].map(c=>data[p*4+c]),m=color.reduce((a,b)=>a+b,0)/3,v=color.map(c=>c-m);
  const correlation=v.reduce((n,c,i)=>n+c*direction[i],0)/Math.max(1,Math.hypot(...v)*length);
  if(Math.max(...color)-Math.min(...color)<14||correlation<.75)continue;
  material[p]=1;brightness.push(m);const x=p%width,y=Math.floor(p/width);box.left=Math.min(box.left,x);box.right=Math.max(box.right,x);box.top=Math.min(box.top,y);box.bottom=Math.max(box.bottom,y);
 }
 if(brightness.length<80)return {detected:false};
 const w=box.right-box.left+1,h=box.bottom-box.top+1,threshold=median(brightness)*.8,dark=new Uint8Array(width*height);
 for(const p of ids){const x=p%width,y=Math.floor(p/width);if(material[p]&&x>box.left+w*.12&&x<box.right-w*.12&&y>box.top+h*.08&&y<box.top+h*.7&&(data[p*4]+data[p*4+1]+data[p*4+2])/3<threshold)dark[p]=1;}
 const seen=new Uint8Array(width*height),queue=new Int32Array(width*height),candidates=[];
 for(let start=0;start<dark.length;start++)if(dark[start]&&!seen[start]){
  let head=0,tail=1;queue[0]=start;seen[start]=1;const b={left:width,right:0,top:height,bottom:0};
  while(head<tail){const p=queue[head++],x=p%width,y=Math.floor(p/width);b.left=Math.min(b.left,x);b.right=Math.max(b.right,x);b.top=Math.min(b.top,y);b.bottom=Math.max(b.bottom,y);
   const add=q=>{if(dark[q]&&!seen[q]){seen[q]=1;queue[tail++]=q;}};
   if(x>0)add(p-1);if(x<width-1)add(p+1);if(y>0)add(p-width);if(y<height-1)add(p+width);
  }
  const cw=b.right-b.left+1,ch=b.bottom-b.top+1,fill=tail/(cw*ch);
  if(tail>w*h*.006&&tail<w*h*.18&&ch/cw>1.15&&ch/cw<4&&ch>h*.12&&ch<h*.45&&fill>.5)candidates.push({...b,width:cw,height:ch,area:tail,fill});
 }
 candidates.sort((a,b)=>b.area-a.area);
 if(!candidates.length||(candidates[1]?.area??0)>candidates[0].area*.55)return {detected:false};
 const c=candidates[0],square=Math.max(width,height),dx=(square-width)/2,dy=(square-height)/2;
 return {detected:true,box:{left:(c.left+dx)/square,top:(c.top+dy)/square,width:c.width/square,height:c.height/square},
  outerRelative:{top:(c.top-box.top)/h,bottom:(c.bottom+1-box.top)/h,centerX:((c.left+c.right)/2-box.left)/w,width:c.width/w,height:c.height/h},
  fill:c.fill,evidence:'isolated-dark-vertical-oval-in-side-material',physicalOpeningVerified:false};
}
