const ORB_SIZE=104;
const EDGE=6;
const TITLE_HEIGHT=46;

function orbBounds(position,workArea){
  const valid=position&&Number.isFinite(position.x)&&Number.isFinite(position.y);
  const x=valid?position.x:workArea.x+workArea.width-ORB_SIZE-24;
  const y=valid?position.y:workArea.y+Math.round(workArea.height*.66)-ORB_SIZE/2;
  return {
    x:Math.round(Math.max(workArea.x,Math.min(x,workArea.x+Math.max(0,workArea.width-ORB_SIZE)))),
    y:Math.round(Math.max(workArea.y,Math.min(y,workArea.y+Math.max(0,workArea.height-ORB_SIZE)))),
    width:ORB_SIZE,height:ORB_SIZE,
  };
}
function bossBounds({width,height}){
  return {x:EDGE,y:TITLE_HEIGHT,width:Math.max(1,width-EDGE*2),height:Math.max(1,height-TITLE_HEIGHT-EDGE)};
}
module.exports={ORB_SIZE,orbBounds,bossBounds};
