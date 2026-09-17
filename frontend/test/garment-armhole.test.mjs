import test from 'node:test';
import assert from 'node:assert/strict';
import {detectSideArmhole} from '../vendor/three/garment-armhole-detection.js';
function sample(oval){const width=160,height=200,data=new Uint8ClampedArray(width*height*4),mask=new Uint8Array(width*height);
 for(let y=0;y<height;y++)for(let x=0;x<width;x++){const p=y*width+x,inside=x>=40&&x<120&&y>=10&&y<190;mask[p]=+inside;let rgb=inside?[220,199,136]:[255,255,255];
  if(inside&&oval&&((x-85)/12)**2+((y-66)/25)**2<1)rgb=[130,115,70];
  // A long edge fold is not the central opening.
  if(inside&&x<45&&y<120)rgb=[120,110,65];data.set([...rgb,255],p*4);
 }return {image:{width,height,data},foreground:{mask}};}
test('locates the isolated oval and retains square-letterbox coordinates',()=>{const s=sample(true),r=detectSideArmhole(s.image,s.foreground,'#dcc788');assert.equal(r.detected,true);assert.ok(Math.abs(r.outerRelative.top-31/180)<.02);assert.ok(r.outerRelative.height>.25&&r.outerRelative.height<.30);assert.ok(Math.abs(r.box.left-(74+20)/200)<.02);assert.equal(r.physicalOpeningVerified,false);});
test('an edge shadow without a separate opening is not promoted',()=>{const s=sample(false);assert.deepEqual(detectSideArmhole(s.image,s.foreground,'#dcc788'),{detected:false});});
