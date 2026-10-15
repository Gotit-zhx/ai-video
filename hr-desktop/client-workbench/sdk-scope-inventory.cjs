// Anonymous, firewalled probe tooling. Inspect original closure metadata only;
// do not invoke SDK methods, export object references or retain primitive values.
const {randomUUID} = require('node:crypto');

async function inspectSdkScopes(connection, expression = 'globalThis.$socket?.handleEvent') {
  const group = 'heyi-sdk-metadata-' + randomUUID();
  const command = (method, params) => connection.client.command(method, params);
  try {
    const reference = await command('Runtime.evaluate', {
      expression, objectGroup: group, returnByValue: false,
    });
    if (reference.exceptionDetails || reference.result?.type !== 'function' ||
        !reference.result.objectId) throw Error('Original dispatcher function is unavailable');
    const properties = await command('Runtime.getProperties', {
      objectId: reference.result.objectId, ownProperties: true,
    });
    const scopeList = properties.internalProperties?.find(item => item.name === '[[Scopes]]')?.value?.objectId;
    if (!scopeList) throw Error('Original dispatcher scopes are unavailable');
    const scopes = await command('Runtime.getProperties', {objectId: scopeList, ownProperties: true});
    const result = [];
    for (const scope of (scopes.result || []).filter(item => /^\d+$/.test(item.name)).slice(0, 8)) {
      // Never enumerate the global object or copy values from it.
      const kind = /^(Closure|Script|Module|Block|Local|Catch)(?:\b|$)/
        .exec(scope.value?.description || '')?.[1];
      if (!kind || !scope.value?.objectId) continue;
      const metadata = await command('Runtime.callFunctionOn', {
        objectId: scope.value.objectId, objectGroup: group, returnByValue: true,
        functionDeclaration: `function() {
          const hidden = Object.getOwnPropertyDescriptor(this, 'object');
          const environment = hidden && hidden.value;
          if (!environment || typeof environment !== 'object') return {available:false};
          const bindings = Object.entries(Object.getOwnPropertyDescriptors(environment));
          const registries = [], functionBindings = [], objectBindings = [];
          for (const [binding, descriptor] of bindings) {
            if (!/^[A-Za-z_$][A-Za-z0-9_$]{0,79}$/.test(binding) ||
                /token|cookie|password|secret|credential|key/i.test(binding) ||
                !Object.hasOwn(descriptor, 'value')) continue;
            const value = descriptor.value;
            if (typeof value === 'function') {
              if (functionBindings.length < 500) functionBindings.push(binding);
              continue;
            }
            if (!value || typeof value !== 'object') continue;
            const prototype = Object.getPrototypeOf(value);
            if (prototype !== null && prototype !== Object.prototype) continue;
            const members = Object.getOwnPropertyDescriptors(value);
            const sdkNames = Object.entries(members)
              .filter(([name]) => /^_sdk_[A-Za-z0-9_]{1,100}$/.test(name))
              .slice(0, 180).map(([name, member]) => ({
                name, type:Object.hasOwn(member,'value') ? typeof member.value : 'accessor'
              }));
            if (members._sdk_getLoginStatus && typeof members._sdk_getLoginStatus.value === 'function' &&
                members._sdk_getNetworkStatus && typeof members._sdk_getNetworkStatus.value === 'function') {
              registries.push({binding, sdkNames});
            }
            const signature = ['userInfo','receiveMessage','send','getProtoMsg']
              .filter(name => Object.hasOwn(members, name))
              .map(name => ({name, type:Object.hasOwn(members[name],'value') ?
                typeof members[name].value : 'accessor'}));
            if (signature.length && objectBindings.length < 40) objectBindings.push({binding, members:signature});
          }
          return {available:true, registries, functionBindings, objectBindings};
        }`,
      });
      if (metadata.exceptionDetails || !metadata.result?.value) {
        throw Error('Original scope metadata could not be inspected');
      }
      result.push({kind, ...metadata.result.value});
    }
    return {mode: 'closure-metadata-only', sdkMethodsInvoked: 0,
      rawValuesRetained: false, scopes: result};
  } finally {
    await command('Runtime.releaseObjectGroup', {objectGroup: group});
  }
}

module.exports = {inspectSdkScopes};
