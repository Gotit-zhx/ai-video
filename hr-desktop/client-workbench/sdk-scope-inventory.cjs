// Anonymous, firewalled probe tooling. Inspect original closure metadata only;
// do not invoke SDK methods, export object references or retain primitive values.
const {randomUUID} = require('node:crypto');

async function inspectSdkScopes(connection, expression = 'globalThis.$socket?.handleEvent', options = {}) {
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
    const result = [];
    const inspectScope = async (objectId, kind) => {
      stage = 'scope-metadata';
      const metadata = await command('Runtime.callFunctionOn', {
        objectId, objectGroup: group, returnByValue: true,
        functionDeclaration: `function() {
          const own = Object.getOwnPropertyDescriptors(this);
          const wrapper = Object.keys(own).length <= 3 &&
            typeof own.description?.value === 'string' && own.object;
          const environment = wrapper ? own.object.value : this;
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
    };
    let inspectionPath = 'internal-scopes', scopeTriggerInvocations = 0;
    if (scopeList && options.preferPublicScope !== true) {
      stage = 'scope-list';
      const scopes = await command('Runtime.getProperties', {objectId: scopeList, ownProperties: true});
      for (const scope of (scopes.result || []).filter(item => /^\d+$/.test(item.name)).slice(0, 8)) {
        // Never enumerate the global object or copy values from it.
        const kind = /^(Closure|Script|Module|Block|Local|Catch)(?:\b|$)/
          .exec(scope.value?.description || '')?.[1];
        if (kind && scope.value?.objectId) await inspectScope(scope.value.objectId, kind);
      }
    } else {
      if (typeof options.triggerExpression !== 'string' || !options.triggerExpression) {
        throw unavailable('dispatcher-scopes-unavailable');
      }
      // Electron 22 omits [[Scopes]] from Runtime.getProperties. Use the
      // standard paused scopeChain instead, without changing any V8 flags.
      // The caller supplies only an owned fixture or private registration;
      // no original SDK method is invoked by that trigger.
      inspectionPath = 'public-function-breakpoint';
      let breakpoint, invoked, debuggerEnabled = false;
      try {
        stage = 'public-debugger-enable';
        await command('Debugger.enable');debuggerEnabled = true;
        stage = 'function-breakpoint';
        breakpoint = await command('Debugger.setBreakpointOnFunctionCall', {objectId: current});
        const paused = connection.client.event('Debugger.paused');
        paused.catch(() => {});
        stage = 'private-trigger';
        scopeTriggerInvocations = 1;
        invoked = command('Runtime.evaluate', {
          expression: 'void (' + options.triggerExpression + ')', returnByValue: true,
        });
        invoked.catch(() => {});
        const state = await paused;
        if (!state.hitBreakpoints?.includes(breakpoint.breakpointId)) {
          throw unavailable('unrelated-debugger-pause');
        }
        const chain = state.callFrames?.[0]?.scopeChain;
        if (!chain) throw unavailable('paused-scopes-unavailable');
        for (const scope of chain.slice(0, 8)) {
          if (!['local', 'closure', 'script', 'module', 'block', 'catch'].includes(scope.type) ||
              !scope.object?.objectId) continue;
          const kind = scope.type[0].toUpperCase() + scope.type.slice(1);
          await inspectScope(scope.object.objectId, kind);
        }
      } finally {
        if (breakpoint?.breakpointId) {
          try {await command('Debugger.removeBreakpoint', {breakpointId: breakpoint.breakpointId});} catch {}
        }
        if (debuggerEnabled) {
          try {await command('Debugger.resume');} catch {}
          try {await command('Debugger.disable');} catch {}
        }
        if (invoked) {
          const value = await invoked;
          if (value.exceptionDetails) throw unavailable('private-trigger-failed');
        }
      }
    }
    const registries = result.flatMap(scope => scope.registries || []);
    return {mode: 'closure-metadata-only', ok: registries.length === 1,
      reason: registries.length === 1 ? 'unique-sdk-registry' :
        registries.length === 0 ? 'sdk-registry-not-found' : 'sdk-registry-ambiguous',
      sdkMethodsInvoked: 0,
      rawValuesRetained: false, inspectionPath, scopeTriggerInvocations,
      functionInternals, scopes: result};
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
