import { surfaceFrame } from './surface-decal.js';

const dot=(a,b)=>a.reduce((sum,v,i)=>sum+v*b[i],0);
const difference=(a,b)=>a.map((v,i)=>v-b[i]);
const vector=value=>Array.isArray(value)&&value.length===3&&value.every(Number.isFinite);
const point=value=>Array.isArray(value)&&value.length===2&&value.every(Number.isFinite);
const clone=print=>({...print,position:[...print.position],normal:[...print.normal],tangent:[...print.tangent]});

/** Corners remain in original model units, including the print's own rotation. */
export function surfacePrintCorners(print){
 const frame=surfaceFrame(print);
 return[[-1,1],[1,1],[1,-1],[-1,-1]].map(([x,y])=>print.position.map((value,i)=>value+x*frame.tangent[i]*print.width/2+y*frame.bitangent[i]*print.height/2));
}

/** Screen-space hit targets stay usable after the garment is rotated/zoomed. */
export function hitResizeHandle(pointer,handles,radius=12){
 if(!point(pointer)||!Number.isFinite(radius)||radius<=0)return null;
 let result=null,distance=radius*radius;
 handles.forEach((handle,index)=>{
  if(!handle||handle.visible===false||!point(handle.screen))return;
  const d=dot(difference(pointer,handle.screen),difference(pointer,handle.screen));
  if(d<=distance){distance=d;result=index;}
 });
 return result;
}

/**
 * Start from the actual grab point to avoid a jump inside the handle's hit area.
 * A near-edge-on plane uses the projected diagonal instead of an unstable ray.
 */
export function beginSurfaceResize({print,corner,garmentHeight,startPoint=null,startScreen,screenCenter,screenCorner}){
 const corners=surfacePrintCorners(print);
 if(!Number.isInteger(corner)||corner<0||corner>3||!Number.isFinite(garmentHeight)||garmentHeight<=0||
  !point(startScreen)||!point(screenCenter)||!point(screenCorner))throw new Error('印花缩放坐标无效');
 const minimum=garmentHeight*.01,maximum=garmentHeight*1.5;
 const minRatio=Math.max(minimum/print.width,minimum/print.height),maxRatio=Math.min(maximum/print.width,maximum/print.height);
 // Individually legal dimensions always have an overlapping interval. Refuse
 // malformed old data rather than silently distorting its aspect ratio.
 if(minRatio>maxRatio)throw new Error('印花宽高比例超出可缩放范围');
 const diagonal=difference(corners[corner],print.position),screenDiagonal=difference(screenCorner,screenCenter);
 if(!vector(startPoint)&&dot(screenDiagonal,screenDiagonal)<1)throw new Error('请转动模型后再缩放印花');
 return{previous:clone(print),corner,diagonal,startPoint:vector(startPoint)?[...startPoint]:null,startScreen:[...startScreen],screenDiagonal,minRatio,maxRatio,ended:false};
}

export function resizeSurfacePrint(session,{surfacePoint=null,screenPoint}={}){
 if(session.ended)return null;
 let delta,diagonal;
 if(session.startPoint){if(!vector(surfacePoint))return null;delta=difference(surfacePoint,session.startPoint);diagonal=session.diagonal;}
 else{if(!point(screenPoint))return null;delta=difference(screenPoint,session.startScreen);diagonal=session.screenDiagonal;}
 const denominator=dot(diagonal,diagonal);if(denominator<1e-14)return null;
 const ratio=Math.min(session.maxRatio,Math.max(session.minRatio,1+dot(delta,diagonal)/denominator));
 if(!Number.isFinite(ratio))return null;
 return{...clone(session.previous),width:session.previous.width*ratio,height:session.previous.height*ratio};
}

/** End exactly once; pointer cancellation is a rollback with no history entry. */
export function finishSurfaceResize(session,current,eventType){
 if(session.ended)return null;session.ended=true;
 if(eventType!=='pointerup')return{print:clone(session.previous),detail:null};
 if(!current||current.id!==session.previous.id)return null;
 const changed=Math.abs(current.width-session.previous.width)>1e-10||Math.abs(current.height-session.previous.height)>1e-10;
 return{print:clone(current),detail:changed?{id:current.id,print:clone(current),previous:clone(session.previous),commit:true,reason:'resize'}:null};
}
