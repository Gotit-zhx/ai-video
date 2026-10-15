// A single read of the unique authentication helper in a disposable,
// anonymous, network-blocked process. Only type/empty-state metadata escapes.
const {randomUUID} = require('node:crypto');

async function inspectAnonymousAuthentication(connection, options = {}) {
  if (options.anonymousIsolated !== true) throw Error('Anonymous isolated runtime required');
  const group = 'heyi-anonymous-auth-' + randomUUID();
  const command = (method, params) => connection.client.command(method, params);
  let stage = 'function-prototype', failed = false;
  try {
    const functions = await command('Runtime.evaluate', {
      expression: 'Function.prototype', objectGroup: group, returnByValue: false,
    });
    if (functions.exceptionDetails || !functions.result?.objectId) throw Error();
    stage = 'query-functions';
    const query = await command('Runtime.queryObjects', {
      prototypeObjectId: functions.result.objectId, objectGroup: group,
    });
    if (!query.objects?.objectId) throw Error();
    stage = 'unique-authentication-prototype';
    const prototype = await command('Runtime.callFunctionOn', {
      objectId: query.objects.objectId, objectGroup: group, returnByValue: false,
      functionDeclaration: `function() {
        const isProxy=process.mainModule.require('node:util').types.isProxy;
        if(!Number.isSafeInteger(this.length)||this.length>200000)throw Error();
        const found=[];
        for(const fn of this){
          if(typeof fn!=='function'||isProxy(fn))continue;
          const prototype=Object.getOwnPropertyDescriptor(fn,'prototype')?.value;
          if(!prototype||typeof prototype!=='object'||isProxy(prototype))continue;
          if(['getAuthCookieHeader','get','setLoginCookies','clearLoginCookies'].every(name=>
            typeof Object.getOwnPropertyDescriptor(prototype,name)?.value==='function')&&
            !found.includes(prototype))found.push(prototype);
          if(found.length>1)throw Error();
        }
        if(found.length!==1)throw Error();
        return found[0];
      }`,
    });
    if (prototype.exceptionDetails || !prototype.result?.objectId) throw Error();
    stage = 'query-authentication-instances';
    const instances = await command('Runtime.queryObjects', {
      prototypeObjectId: prototype.result.objectId, objectGroup: group,
    });
    if (!instances.objects?.objectId) throw Error();
    stage = 'anonymous-authentication-read';
    const result = await command('Runtime.callFunctionOn', {
      objectId: instances.objects.objectId, objectGroup: group, returnByValue: true,
      awaitPromise: true,
      arguments: [{value: require.resolve('./original-sdk-read.cjs')}],
      functionDeclaration: `async function(readModule) {
        const isProxy=process.mainModule.require('node:util').types.isProxy;
        if(this.length!==1||isProxy(this[0]))return {
          ok:false,reason:'anonymous-unique-instance-required',originalMethodsInvoked:0};
        // This version does not publish global.isLogin. Use the original
        // renderer's memory getter, and require an explicit false result.
        const service=Object.getOwnPropertyDescriptor(globalThis,'$socket')?.value;
        if(!service||isProxy(service))throw Error();
        const loggedIn=await process.mainModule.require(readModule)
          .readOriginalSDK(service,'_sdk_getLoginStatus',1000);
        if(loggedIn!==false)return {ok:false,reason:'anonymous-login-required',
          originalMethodsInvoked:0,loginStatusReads:1};
        const instance=this[0],prototype=Object.getPrototypeOf(instance);
        const method=Object.getOwnPropertyDescriptor(prototype,'getAuthCookieHeader')?.value;
        if(typeof method!=='function'||isProxy(method))throw Error();
        let timer;
        try {
          const result=await Promise.race([
            Promise.resolve().then(()=>Reflect.apply(method,instance,[])),
            new Promise((_,reject)=>{timer=setTimeout(()=>reject(Error()),1000)}),
          ]);
          const type=result===null?'null':typeof result;
          return {ok:true,reason:'anonymous-helper-read',
            originalMethodsInvoked:1,loginStatusReads:1,resultType:type,
            emptyString:type==='string'&&result.length===0,
            hasWt2Field:type==='string'&&/(^|;\\s*)wt2=/.test(result),
            rawValuesRetained:false};
        } finally {clearTimeout(timer)}
      }`,
    });
    if (result.exceptionDetails || !result.result?.value) throw Error();
    return {mode: 'anonymous-authentication-return-shape-only', ...result.result.value};
  } catch {
    failed = true;
    throw Object.assign(Error('Original anonymous authentication check unavailable'), {
      authenticationDiagnostic: {stage},
    });
  } finally {
    try {await command('Runtime.releaseObjectGroup', {objectGroup: group});}
    catch (error) {if (!failed) throw error;}
  }
}
module.exports = {inspectAnonymousAuthentication};
