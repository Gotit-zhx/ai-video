// Diagnostic metadata in an anonymous firewalled process only. Never call the
// constructors, getters, or methods being located; do not export instance data.
const {randomUUID} = require('node:crypto');
const ROLES = Object.freeze({
  identity: {userInfo:'accessor', userId:'accessor', avatar:'accessor'},
  network: {getProxyConfig:'function', getHttpProxy:'function', getDNSProxy:'function',
    setProxy:'function', stopProxy:'function'},
  cookies: {get:'function', getZpToken:'function', setLoginCookies:'function',
    clearLoginCookies:'function'},
  receiver: {receiveMessage:'function', addMessageById:'function', getMessageById:'function'},
  sender: {send:'function', connect:'function', onMessage:'function', sendPresence:'function'},
  messageFactory: {getProtoMsg:'function', generateMsgProto:'function'},
});

async function inspectNativeRoles(connection, options = {}) {
  if (options.anonymousIsolated !== true) throw Error('Anonymous isolated runtime required');
  const group = 'heyi-native-roles-' + randomUUID();
  const command = (method, params) => connection.client.command(method, params);
  let stage = 'function-prototype', failed = false;
  try {
    const prototype = await command('Runtime.evaluate', {
      expression: 'Function.prototype', objectGroup: group, returnByValue: false,
    });
    if (prototype.exceptionDetails || !prototype.result?.objectId) throw Error('Function prototype unavailable');
    stage = 'query-constructors';
    const functions = await command('Runtime.queryObjects', {
      prototypeObjectId: prototype.result.objectId, objectGroup: group,
    });
    if (!functions.objects?.objectId) throw Error('Constructor query unavailable');
    stage = 'match-prototypes';
    const matched = await command('Runtime.callFunctionOn', {
      objectId: functions.objects.objectId, objectGroup: group, returnByValue: false,
      arguments: [{value: ROLES}],
      functionDeclaration: `function(roles) {
        const isProxy=process.mainModule.require('node:util').types.isProxy;
        if(!Number.isSafeInteger(this.length)||this.length>200000)throw Error('query limit');
        const found=Object.fromEntries(Object.keys(roles).map(role=>[role,[]]));
        for(const candidate of this) {
          if(typeof candidate!=='function'||isProxy(candidate))continue;
          const prototype=Object.getOwnPropertyDescriptor(candidate,'prototype')?.value;
          if(!prototype||typeof prototype!=='object'||isProxy(prototype))continue;
          for(const [role,signature] of Object.entries(roles)){
            if(!Object.entries(signature).every(([key,type])=>{
              const d=Object.getOwnPropertyDescriptor(prototype,key);
              return type==='function'?typeof d?.value==='function':
                typeof d?.get==='function'&&!Object.hasOwn(d,'value');
            }))continue;
            if(!found[role].includes(prototype))found[role].push(prototype);
            if(found[role].length>8)throw Error('prototype limit');
          }
        }
        return found;
      }`,
    });
    if (matched.exceptionDetails || !matched.result?.objectId) throw Error('Prototype mapping unavailable');
    const roles = {};
    for (const [role, signature] of Object.entries(ROLES)) {
      stage = 'role-prototype';
      const prototypes = await command('Runtime.callFunctionOn', {
        objectId: matched.result.objectId, objectGroup: group, returnByValue: false,
        functionDeclaration: 'function(role){return this[role]}', arguments: [{value:role}],
      });
      if (prototypes.exceptionDetails || !prototypes.result?.objectId) throw Error('Role list unavailable');
      const properties = await command('Runtime.getProperties', {
        objectId: prototypes.result.objectId, ownProperties: true,
      });
      const items = (properties.result || []).filter(item => /^\d+$/.test(item.name));
      if (items.length > 8) throw Error('Role prototype limit exceeded');
      const instances = [];
      for (const item of items) {
        if (!item.value?.objectId) throw Error('Role prototype reference unavailable');
        stage = 'query-role-instances';
        const queried = await command('Runtime.queryObjects', {
          prototypeObjectId: item.value.objectId, objectGroup: group,
        });
        if (!queried.objects?.objectId) throw Error('Role instances unavailable');
        const count = await command('Runtime.callFunctionOn', {
          objectId: queried.objects.objectId, objectGroup: group, returnByValue: true,
          functionDeclaration: 'function(){return this.length}',
        });
        const value = count.result?.value;
        if (count.exceptionDetails || !Number.isSafeInteger(value) || value < 0 || value > 200000) {
          throw Error('Role instance count unavailable');
        }
        instances.push(value);
      }
      roles[role] = {prototypeMatches: items.length, instanceCounts: instances,
        uniqueSingleton: items.length === 1 && instances[0] === 1,
        members: Object.entries(signature).map(([name,type]) => ({name,type}))};
    }
    return {mode:'anonymous-native-role-metadata-only', rawValuesRetained:false,
      originalMethodsInvoked:0, roles,
      singletonsAvailable: ['identity','network','cookies','receiver','sender']
        .every(role => roles[role].uniqueSingleton)};
  } catch {
    failed = true;
    throw Object.assign(Error('Original native role inventory unavailable'), {
      inventoryDiagnostic: {stage},
    });
  } finally {
    try {await command('Runtime.releaseObjectGroup', {objectGroup:group});}
    catch (error) {if (!failed) throw error;}
  }
}

module.exports = {inspectNativeRoles};
