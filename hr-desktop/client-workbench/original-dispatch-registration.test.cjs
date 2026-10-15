const {test} = require('node:test');
const assert = require('node:assert/strict');
const {registerForInspection} = require('./original-dispatch-registration.cjs');

test('private registration binds but never calls the original message handler', () => {
  let calls = 0;
  const service = {
    clientID:new Map([['existing',{sid:'keep'}]]), offlineCache:{existing:['keep']},
    handleEvent(_name,socket) {
      const cid=socket.handshake.query.cid;
      this.clientID.set(cid,{sid:socket.id});this.offlineCache[cid]=[];
      socket.on('message',()=>calls++);
    },
  };
  assert.deepEqual(registerForInspection(service), {messageHandlerBound:true});
  assert.equal(calls,0);
  assert.deepEqual([...service.clientID],[['existing',{sid:'keep'}]]);
  assert.deepEqual(service.offlineCache,{existing:['keep']});
});

test('registration exceptions never delete a replacement client', () => {
  const service = {
    clientID:new Map(),
    handleEvent(_name,socket) {
      this.clientID.set(socket.handshake.query.cid,{sid:'replacement'});
      throw Error('fixture failure');
    },
  };
  assert.throws(()=>registerForInspection(service),/fixture failure/);
  assert.deepEqual([...service.clientID.values()],[{sid:'replacement'}]);
});
