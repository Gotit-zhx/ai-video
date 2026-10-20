// Disposable local page fixtures only. These bindings are newly allocated
// objects, never the original client's identity, cookies or SDK registry.
const assert = require('node:assert/strict');
const {WindowsAdapter} = require('./windows-adapter.cjs');

function create({nativeWindow, allContents}) {
  if (!process.env.HEYI_WORKBENCH_TEST_DATA || process.env.HEYI_WORKBENCH_TEST_PORT !== '0') {
    throw Error('Disposable workbench profile required');
  }
  let unexpectedCalls = 0;
  const forbidden = () => {unexpectedCalls++;throw Error('Unexpected legacy or network fixture call');};
  const adapter = new WindowsAdapter({
    bindings: {
      sdk: {_sdk_getLoginStatus: async () => true, _sdk_chatSocket_sendTextMessage: forbidden},
      identity: {userInfo: {userId: '101'}},
      authentication: {getAuthCookieHeader: forbidden, clearLoginCookies() {}},
      sender: {send: forbidden, dispatchMessage() {}}, receiver: {receiveMessage() {}},
      httpClients: [{send: forbidden}], requestBuilder: forbidden,
    },
    net: {}, session: {}, endpoints: [], nativeWindow, allContents, show() {},
    logoutTargets: [], transport: forbidden,
  });
  return {adapter, verify() {
    assert.equal(unexpectedCalls, 0);
    return {windowsAdapterPath: true, legacySenderUnused: true, syntheticFixtureIdentity: true};
  }};
}
module.exports = {create};
