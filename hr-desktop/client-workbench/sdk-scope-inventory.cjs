// Anonymous, firewalled probe tooling. Inspect original closure metadata only;
// do not invoke SDK methods, export object references or retain primitive values.
const {randomUUID} = require('node:crypto');

async function inspectSdkScopes(connection, expression = 'globalThis.$socket?.handleEvent') {
  const group = 'heyi-sdk-metadata-' + randomUUID();
  const command = (method, params) => connection.client.command(method, params);
  let stage = 'dispatcher-reference', failed = false;
  const functionInternals = [];
  const unavailable = code => Object.assign(Error('Original SDK scope inventory unavailable'),
    {inventoryCode: code});
  try {
    const reference = await command('Runtime.evaluate', {
      expression, objectGroup: group, returnByValue: false,
    });
    if (reference.exceptionDetails || reference.result?.type !== 'function' ||
        !reference.result.objectId) throw unavailable('dispatcher-function-unavailable');
    let current = reference.result.objectId, scopeList;
    stage = 'function-properties';
    for (let depth = 0; depth < 4; depth++) {
      const properties = await command('Runtime.getProperties', {
        objectId: current, ownProperties: true,
      });
      functionInternals.push({depth,
        names: (properties.internalProperties || []).map(item => item.name).slice(0, 20)});
      scopeList = properties.internalProperties?.find(item => item.name === '[[Scopes]]')?.value?.objectId;
      if (scopeList) break;
      const target = properties.internalProperties?.find(item =>
        ['[[TargetFunction]]', '[[BoundTargetFunction]]'].includes(item.name))?.value?.objectId;
      if (!target) break;
      current = target;
    }
    if (!scopeList) throw unavailable('dispatcher-scopes-unavailable');
    stage = 'scope-list';
    const scopes = await command('Runtime.getProperties', {objectId: scopeList, ownProperties: true});
    const result = [];
    for (const scope of (scopes.result || []).filter(item => /^\d+$/.test(item.name)).slice(0, 8)) {
      // Never enumerate the global object or copy values from it.
      const kind = /^(Closure|Script|Module|Block|Local|Catch)(?:\b|$)/
        .exec(scope.value?.description || '')?.[1];
      if (!kind || !scope.value?.objectId) continue;
      stage = 'scope-metadata';
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
            const members = Object.getOwnPropertyDescriptors(value);
            const inherited = prototype && prototype !== Object.prototype ?
              Object.getOwnPropertyDescriptors(prototype) : {};
            const signature = ['userInfo','receiveMessage','send','getProtoMsg',
              'getZpToken','getProxyConfig','setProxy','closeNetCheck']
              .filter(name => Object.hasOwn(members, name) || Object.hasOwn(inherited, name))
              .map(name => {
                const member = members[name] || inherited[name];
                return {name, type:Object.hasOwn(member,'value') ? typeof member.value : 'accessor'};
              });
            if (signature.length && objectBindings.length < 80) objectBindings.push({binding, members:signature});
            if (prototype !== null && prototype !== Object.prototype) continue;
            const sdkNames = Object.entries(members)
              .filter(([name]) => /^_sdk_[A-Za-z0-9_]{1,100}$/.test(name))
              .slice(0, 180).map(([name, member]) => ({
                name, type:Object.hasOwn(member,'value') ? typeof member.value : 'accessor'
              }));
            if (members._sdk_getLoginStatus && typeof members._sdk_getLoginStatus.value === 'function' &&
                members._sdk_getNetworkStatus && typeof members._sdk_getNetworkStatus.value === 'function') {
              registries.push({binding, sdkNames});
            }
          }
          return {available:true, registries, functionBindings, objectBindings};
        }`,
      });
      if (metadata.exceptionDetails || !metadata.result?.value) {
        throw unavailable('scope-metadata-unavailable');
      }
      result.push({kind, ...metadata.result.value});
    }
    return {mode: 'closure-metadata-only', sdkMethodsInvoked: 0,
      rawValuesRetained: false, functionInternals, scopes: result};
  } catch (error) {
    failed = true;
    throw Object.assign(Error('Original SDK scope inventory unavailable'), {
      inventoryDiagnostic: {stage, code: error.inventoryCode || 'inspector-command-failed',
        functionInternals},
    });
  } finally {
    try {await command('Runtime.releaseObjectGroup', {objectGroup: group});}
    catch (error) {if (!failed) throw error;}
  }
}

module.exports = {inspectSdkScopes};
