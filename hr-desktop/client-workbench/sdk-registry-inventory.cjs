// Anonymous, OS-firewalled diagnostics only. Bytenode may omit lexical scope
// metadata altogether. Query a bounded set of plain objects through the public
// inspector protocol and export ONLY SDK member names/types, never their values.
const {randomUUID} = require('node:crypto');

async function inspectSdkRegistry(connection, options = {}) {
  if (options.anonymousIsolated !== true) throw Error('Anonymous isolated runtime required');
  const group = 'heyi-sdk-registry-' + randomUUID();
  const command = (method, params) => connection.client.command(method, params);
  let stage = 'plain-object-prototype', failed = false;
  try {
    const prototype = await command('Runtime.evaluate', {
      expression: 'Object.prototype', objectGroup: group, returnByValue: false,
    });
    if (prototype.exceptionDetails || !prototype.result?.objectId) {
      throw Error('Plain object prototype unavailable');
    }
    stage = 'query-plain-objects';
    const objects = await command('Runtime.queryObjects', {
      prototypeObjectId: prototype.result.objectId, objectGroup: group,
    });
    if (!objects.objects?.objectId) throw Error('Plain object query unavailable');
    stage = 'registry-metadata';
    const response = await command('Runtime.callFunctionOn', {
      objectId: objects.objects.objectId, objectGroup: group, returnByValue: true,
      functionDeclaration: `function() {
        const isProxy=process.mainModule.require('node:util').types.isProxy;
        const count=this.length;
        if(!Number.isSafeInteger(count)||count>200000)return {bounded:false,registries:[]};
        const registries=[],authenticationHelpers=[];
        for(let index=0;index<count;index++){
          const candidate=this[index];
          if(!candidate||typeof candidate!=='object'||isProxy(candidate))continue;
          const auth=Object.getOwnPropertyDescriptor(candidate,'getAuthCookieHeader');
          if(typeof auth?.value==='function'){
            if(authenticationHelpers.length>=8)return {bounded:false,registries:[]};
            authenticationHelpers.push({members:Object.entries(Object.getOwnPropertyDescriptors(candidate))
              .filter(([name,d])=>/^[A-Za-z_$][A-Za-z0-9_$]{0,99}$/.test(name)&&typeof d.value==='function')
              .slice(0,80).map(([name])=>({name,type:'function'}))});
          }
          const login=Object.getOwnPropertyDescriptor(candidate,'_sdk_getLoginStatus');
          const network=Object.getOwnPropertyDescriptor(candidate,'_sdk_getNetworkStatus');
          if(typeof login?.value!=='function'||typeof network?.value!=='function')continue;
          if(registries.length>=8)return {bounded:false,registries:[]};
          const sdkNames=Object.entries(Object.getOwnPropertyDescriptors(candidate))
            .filter(([name])=>/^_sdk_[A-Za-z0-9_]{1,100}$/.test(name))
            .slice(0,180).map(([name,descriptor])=>({
              name,type:Object.hasOwn(descriptor,'value')?typeof descriptor.value:'accessor'
            }));
          registries.push({sdkNames});
        }
        return {bounded:true,registries,authenticationHelpers};
      }`,
    });
    if (response.exceptionDetails || response.result?.value?.bounded !== true) {
      throw Error('Bounded registry inspection unavailable');
    }
    const {registries} = response.result.value;
    if (!Array.isArray(registries)) throw Error('Registry metadata unavailable');
    return {mode: 'anonymous-registry-metadata-only', ok: registries.length === 1,
      reason: registries.length === 1 ? 'unique-sdk-registry' :
        registries.length === 0 ? 'sdk-registry-not-found' : 'sdk-registry-ambiguous',
      sdkMethodsInvoked: 0, rawValuesRetained: false, registries,
      authenticationHelpers:response.result.value.authenticationHelpers};
  } catch {
    failed = true;
    throw Object.assign(Error('Original registry inventory unavailable'), {
      inventoryDiagnostic: {stage},
    });
  } finally {
    try {await command('Runtime.releaseObjectGroup', {objectGroup: group});}
    catch (error) {if (!failed) throw error;}
  }
}

module.exports = {inspectSdkRegistry};
