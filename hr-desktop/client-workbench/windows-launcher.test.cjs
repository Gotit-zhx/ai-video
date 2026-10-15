const test = require('node:test');
const assert = require('node:assert/strict');
const net = require('node:net');
const {ensureInspectorPortFree} = require('./windows-launcher.cjs');

test('occupied inspector port is refused without connecting to or closing its owner', async () => {
  let connections = 0;
  const owner = net.createServer(socket => {connections++;socket.destroy();});
  await new Promise(resolve => owner.listen(0, '127.0.0.1', resolve));
  const port = owner.address().port;
  try {
    await assert.rejects(ensureInspectorPortFree(port), /already occupied/);
    assert.equal(connections, 0);
    assert.equal(owner.listening, true);
  } finally {await new Promise(resolve => owner.close(resolve));}
  await ensureInspectorPortFree(port);
  const nextOwner = net.createServer();
  await new Promise(resolve => nextOwner.listen(port, '127.0.0.1', resolve));
  await new Promise(resolve => nextOwner.close(resolve));
});
