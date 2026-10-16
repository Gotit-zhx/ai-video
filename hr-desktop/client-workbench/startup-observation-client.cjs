// Test-only protocol wrapper. Tag every JS expression executed by the actual
// binding implementation; metadata-only protocol operations remain unchanged.
const path = require('node:path');
function observeBindingConnection(connection, modulePath) {
  if (!path.isAbsolute(modulePath) || !modulePath.endsWith('.cjs')) {
    throw Error('Absolute startup observation module required');
  }
  let scopedCommands = 0;
  const observer = `process.mainModule.require(${JSON.stringify(modulePath)})`;
  const metadataCommands = new Set([
    'Runtime.queryObjects', 'Runtime.getProperties', 'Runtime.releaseObjectGroup',
  ]);
  return {
    get scopedCommands() {return scopedCommands;},
    client: {
      command(method, params = {}) {
        let wrapped;
        if (method === 'Runtime.evaluate') {
          scopedCommands++;
          wrapped = {...params,
            expression: `${observer}.observeBindingCall(() => (${params.expression}))`};
        } else if (method === 'Runtime.callFunctionOn') {
          scopedCommands++;
          wrapped = {...params, functionDeclaration: `function(...args) {
            return ${observer}.observeBindingCall(
              () => Reflect.apply((${params.functionDeclaration}), this, args));
          }`};
        } else if (metadataCommands.has(method)) wrapped = params;
        else throw Error('Unmeasured startup inspection command');
        return connection.client.command(method, wrapped);
      },
    },
  };
}
module.exports = {observeBindingConnection};
