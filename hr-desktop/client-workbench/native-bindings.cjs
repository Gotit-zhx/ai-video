// Capture references only inside the verified target process. The controller
// never receives account objects, credentials, packets or original functions.
const {randomUUID} = require('node:crypto');
const path = require('node:path');

const SHAPES = {
  identity: {userInfo: 'accessor', userId: 'accessor', avatar: 'accessor'},
  authentication: {get: 'function', getAuthCookieHeader: 'function',
    setLoginCookies: 'function', clearLoginCookies: 'function'},
  network: {getProxyConfig: 'function', getHttpProxy: 'function',
    getDNSProxy: 'function', setProxy: 'function', stopProxy: 'function'},
  receiver: {receiveMessage: 'function', addMessageById: 'function', getMessageById: 'function'},
  sender: {send: 'function', connect: 'function', onMessage: 'function', sendPresence: 'function'},
  messageFactory: {getProtoMsg: 'function', generateMsgProto: 'function'},
};

async function bindNativeModule(connection, {modulePath, config = {}, pinnedOriginalVerified,
  builderDescriptor} = {}) {
  if (pinnedOriginalVerified !== true || !path.isAbsolute(modulePath || '') || !modulePath.endsWith('.cjs')) {
    throw Error('Verified original and an absolute addon module are required');
  }
  if (builderDescriptor !== undefined && (!builderDescriptor ||
      !/^[A-Za-z_$][A-Za-z0-9_$]{0,99}$/.test(builderDescriptor.name || '') ||
      !Number.isInteger(builderDescriptor.lineNumber) || builderDescriptor.lineNumber < 0 ||
      builderDescriptor.lineNumber > 1000000 || !Number.isInteger(builderDescriptor.columnNumber) ||
      builderDescriptor.columnNumber < 0 || builderDescriptor.columnNumber > 20000000)) {
    throw Error('Verified request builder descriptor required');
  }
  const group = 'heyi-native-bindings-' + randomUUID();
  const command = (method, params) => connection.client.command(method, params);
  let stage = 'create-bindings', failed = false;
  const reference = response => {
    if (response.exceptionDetails || !response.result?.objectId) throw Error();
    return response.result.objectId;
  };
  try {
    const bindings = reference(await command('Runtime.evaluate', {
      expression: 'Object.create(null)', objectGroup: group, returnByValue: false,
    }));
    const store = async (key, objectId) => {
      const result = await command('Runtime.callFunctionOn', {
        objectId: bindings, objectGroup: group, returnByValue: true,
        functionDeclaration: 'function(key,value){Object.defineProperty(this,key,{value});return true}',
        arguments: [{value: key}, {objectId}],
      });
      if (result.exceptionDetails || result.result?.value !== true) throw Error();
    };
    stage = 'function-prototype';
    const functions = reference(await command('Runtime.evaluate', {
      expression: 'Function.prototype', objectGroup: group, returnByValue: false,
    }));
    const queried = await command('Runtime.queryObjects', {prototypeObjectId: functions, objectGroup: group});
    if (!queried.objects?.objectId) throw Error();
    stage = 'unique-role-prototypes';
    const prototypes = reference(await command('Runtime.callFunctionOn', {
      objectId: queried.objects.objectId, objectGroup: group, returnByValue: false,
      arguments: [{value: SHAPES}],
      functionDeclaration: `function(shapes) {
        const isProxy=process.mainModule.require('node:util').types.isProxy;
        if(!Number.isSafeInteger(this.length)||this.length>200000)throw Error();
        const found=Object.fromEntries(Object.keys(shapes).map(key=>[key,[]]));
        for(const fn of this) {
          if(typeof fn!=='function'||isProxy(fn))continue;
          const prototype=Object.getOwnPropertyDescriptor(fn,'prototype')?.value;
          if(!prototype||typeof prototype!=='object'||isProxy(prototype))continue;
          for(const [key,shape] of Object.entries(shapes)){
            if(!Object.entries(shape).every(([name,type])=>{
              const d=Object.getOwnPropertyDescriptor(prototype,name);
              return type==='function'?typeof d?.value==='function':typeof d?.get==='function';
            }))continue;
            if(!found[key].includes(prototype))found[key].push(prototype);
            if(found[key].length>1)throw Error();
          }
        }
        if(Object.values(found).some(values=>values.length!==1))throw Error();
        return Object.fromEntries(Object.entries(found).map(([key,values])=>[key,values[0]]));
      }`,
    }));
    for (const role of Object.keys(SHAPES)) {
      stage = 'capture-' + role;
      const prototype = reference(await command('Runtime.callFunctionOn', {
        objectId: prototypes, objectGroup: group, returnByValue: false,
        functionDeclaration: 'function(role){return this[role]}', arguments: [{value: role}],
      }));
      if (role === 'messageFactory') {
        await store('messageFactoryPrototype', prototype);
        continue;
      }
      const objects = await command('Runtime.queryObjects', {prototypeObjectId: prototype, objectGroup: group});
      if (!objects.objects?.objectId) throw Error();
      const singleton = reference(await command('Runtime.callFunctionOn', {
        objectId: objects.objects.objectId, objectGroup: group, returnByValue: false,
        functionDeclaration: `function(){
          if(this.length!==1||process.mainModule.require('node:util').types.isProxy(this[0]))throw Error();
          return this[0];
        }`,
      }));
      await store(role, singleton);
    }
    if (builderDescriptor) {
      stage = 'original-request-builder';
      const factoryMethod = reference(await command('Runtime.callFunctionOn', {
        objectId: bindings, objectGroup: group, returnByValue: false,
        functionDeclaration: 'function(){return this.messageFactoryPrototype.getProtoMsg}',
      }));
      const locationOf = async objectId => {
        const metadata = await command('Runtime.getProperties', {objectId, ownProperties: true});
        return metadata.internalProperties?.find(property =>
          property.name === '[[FunctionLocation]]')?.value?.value;
      };
      const originalLocation = await locationOf(factoryMethod);
      if (!originalLocation?.scriptId) throw Error();
      const candidates = reference(await command('Runtime.callFunctionOn', {
        objectId: queried.objects.objectId, objectGroup: group, returnByValue: false,
        arguments: [{value: builderDescriptor.name}],
        functionDeclaration: `function(name){
          const types=process.mainModule.require('node:util').types;
          const found=this.filter(fn=>typeof fn==='function'&&!types.isProxy(fn)&&
            types.isAsyncFunction(fn)&&Object.getOwnPropertyDescriptor(fn,'name')?.value===name&&
            Object.getOwnPropertyDescriptor(fn,'length')?.value===1);
          if(found.length<1||found.length>32)throw Error();
          return found;
        }`,
      }));
      const entries = await command('Runtime.getProperties', {objectId: candidates, ownProperties: true});
      const matches = [];
      for (const entry of entries.result || []) {
        if (!/^\d+$/.test(entry.name) || !entry.value?.objectId) continue;
        const location = await locationOf(entry.value.objectId);
        if (location?.scriptId === originalLocation.scriptId &&
            location.lineNumber === builderDescriptor.lineNumber &&
            location.columnNumber === builderDescriptor.columnNumber) matches.push(entry.value.objectId);
      }
      if (matches.length !== 1) throw Error();
      await store('requestBuilder', matches[0]);
    }
    stage = 'sdk-registry';
    const plainPrototype = reference(await command('Runtime.evaluate', {
      expression: 'Object.prototype', objectGroup: group, returnByValue: false,
    }));
    const plainObjects = await command('Runtime.queryObjects', {
      prototypeObjectId: plainPrototype, objectGroup: group,
    });
    if (!plainObjects.objects?.objectId) throw Error();
    const registry = reference(await command('Runtime.callFunctionOn', {
      objectId: plainObjects.objects.objectId, objectGroup: group, returnByValue: false,
      functionDeclaration: `function(){
        const isProxy=process.mainModule.require('node:util').types.isProxy;
        if(!Number.isSafeInteger(this.length)||this.length>200000)throw Error();
        const matches=[];
        for(const candidate of this){
          if(!candidate||typeof candidate!=='object'||isProxy(candidate))continue;
          if(['_sdk_getLoginStatus','_sdk_getNetworkStatus','_sdk_getUserInfo',
            '_sdk_chatSocket_sendTextMessage'].every(name=>
              typeof Object.getOwnPropertyDescriptor(candidate,name)?.value==='function'))matches.push(candidate);
          if(matches.length>1)throw Error();
        }
        if(matches.length!==1)throw Error();
        return matches[0];
      }`,
    }));
    await store('sdk', registry);
    stage = 'original-http-client-prototype';
    const httpPrototype = reference(await command('Runtime.evaluate', {
      objectGroup: group, returnByValue: false,
      expression: `(()=>{
        const isProxy=process.mainModule.require('node:util').types.isProxy;
        const modules=Object.entries(process.mainModule.require('node:module')._cache)
          .filter(([file])=>/[\\\\/]node_modules[\\\\/]@bzl[\\\\/]electron-node-fetch[\\\\/]dist[\\\\/]index\\.js$/.test(file));
        if(modules.length!==1)throw Error();
        const exports=modules[0][1].exports;
        if(isProxy(exports))throw Error();
        const constructor=Object.getOwnPropertyDescriptor(exports,'enfetch')?.value;
        if(typeof constructor!=='function'||isProxy(constructor))throw Error();
        const prototype=Object.getOwnPropertyDescriptor(constructor,'prototype')?.value;
        if(!prototype||isProxy(prototype)||
          typeof Object.getOwnPropertyDescriptor(prototype,'send')?.value!=='function')throw Error();
        return prototype;
      })()`,
    }));
    const httpClients = await command('Runtime.queryObjects', {
      prototypeObjectId: httpPrototype, objectGroup: group,
    });
    if (!httpClients.objects?.objectId) throw Error();
    const checkedClients = reference(await command('Runtime.callFunctionOn', {
      objectId: httpClients.objects.objectId, objectGroup: group, returnByValue: false,
      functionDeclaration: `function(){
        const isProxy=process.mainModule.require('node:util').types.isProxy;
        if(this.length<1||this.length>8||this.some(client=>isProxy(client)))throw Error();
        return this;
      }`,
    }));
    await store('httpClients', checkedClients);
    stage = 'install-addon';
    const installed = await command('Runtime.callFunctionOn', {
      objectId: bindings, objectGroup: group, returnByValue: true, awaitPromise: true,
      functionDeclaration: 'async function(file,config){return process.mainModule.require(file).install(this,config)}',
      arguments: [{value: modulePath}, {value: config}],
    });
    if (installed.exceptionDetails || !installed.result?.value) throw Error();
    return installed.result.value;
  } catch {
    failed = true;
    throw Object.assign(Error('Native addon binding did not complete; no automatic replay'), {
      nativeBindingDiagnostic: {stage},
    });
  } finally {
    try {await command('Runtime.releaseObjectGroup', {objectGroup: group});}
    catch (error) {if (!failed) throw error;}
  }
}
module.exports = {bindNativeModule};
