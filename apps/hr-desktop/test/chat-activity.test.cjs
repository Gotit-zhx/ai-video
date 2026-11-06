const test=require('node:test');
const assert=require('node:assert/strict');
const vm=require('node:vm');
const {ChatActivityTracker,sampleChatActivity}=require('../src/chat-activity.cjs');
const now=1790680000000;
const row=(mid='100',extra={})=>({friend:'123',job:'job',mid,at:now/1000,incoming:true,...extra});
test('incoming high-water marks ignore our sends, read receipts, history and virtualization',()=>{
  const tracker=new ChatActivityTracker();
  assert.equal(tracker.update([row()],now).length,1);
  for(const data of [[row()],[row('99')],[row('101',{incoming:false})],[row('101')],[],[row('100')]]){
    assert.deepEqual(tracker.update(data,now),[]);
  }
  assert.equal(tracker.update([row('102')],now).length,1);
  assert.deepEqual(tracker.update([row('200',{friend:'456',at:now/1000-181})],now),[]);
  assert.deepEqual(tracker.update([row('201',{at:now/1000+61})],now),[]);
  tracker.reset();
  assert.equal(tracker.update([row('102')],now).length,1);
});
test('list and open-conversation updates coalesce, including a resume without unread badge',()=>{
  const tracker=new ChatActivityTracker();
  assert.deepEqual(tracker.update([row('100'),row('101')],now).map(x=>x.mid),['101']);
  assert.equal(tracker.update([row('102',{job:''})],now).length,1);
});
test('browser sample exports minimal metadata only and never requests network or cookies',()=>{
  const source={friendId:123,encryptJobId:'job',lastMsgId:100,lastTS:now,
    lastIsSelf:false,newMsgCount:0,name:'private',securityId:'secret',lastText:'private'};
  const message={mid:101,time:now,isSelf:false,fromId:123,text:'private',resume:{url:'private'}};
  const result=vm.runInNewContext(`(${sampleChatActivity.toString()})()`,{
    document:{querySelectorAll:selector=>selector==='.geek-item'
      ?[{__vue__:{$props:{source}}}]
      :[{__vue__:{$props:{message,conversation:{friendId:123}}}}]},
  });
  assert.deepEqual(JSON.parse(JSON.stringify(result)),[row('100'),row('101',{job:''})]);
  assert.doesNotMatch(JSON.stringify(result),/secret|private/);
});
