const {test}=require('node:test');
const assert=require('node:assert/strict');
const {orbBounds,bossBounds,ORB_SIZE}=require('../src/window-layout.cjs');
test('orb recovers from unplugged monitors and stays in the usable desktop',()=>{
  const work={x:0,y:25,width:1440,height:850};
  assert.deepEqual(orbBounds({x:9000,y:-400},work),{x:1336,y:25,width:104,height:104});
  const initial=orbBounds(null,work);
  assert.ok(initial.x+ORB_SIZE<=1440&&initial.y+ORB_SIZE<=875);
  assert.deepEqual(orbBounds({x:NaN,y:1},work),initial);
});
test('orb uses display coordinates on monitors to the left and above the primary',()=>{
  assert.deepEqual(orbBounds({x:-1100,y:-600},{x:-1920,y:-1080,width:1920,height:1080}),
    {x:-1100,y:-600,width:104,height:104});
});
test('the native page never covers window controls or the execution light gutter',()=>{
  assert.deepEqual(bossBounds({width:1200,height:800}),{x:6,y:46,width:1188,height:748});
  assert.deepEqual(bossBounds({width:8,height:20}),{x:6,y:46,width:1,height:1});
});
