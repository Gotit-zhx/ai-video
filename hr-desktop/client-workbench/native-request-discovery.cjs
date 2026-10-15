const {randomUUID} = require('node:crypto');
const path = require('node:path');
const {bindNativeModule} = require('./native-bindings.cjs');

async function discoverNativeRequestBuilder(connection, options = {}) {
  if (options.anonymousIsolated !== true || options.pinnedOriginalVerified !== true) {
    throw Error('Verified anonymous isolated runtime required');
  }
  const file = options.modulePath || require.resolve('./native-request-discovery-target.cjs');
  if (!path.isAbsolute(file) || !file.endsWith('.cjs')) throw Error('Absolute discovery module required');
  const group = 'heyi-request-discovery-' + randomUUID();
  const command = (method, params) => connection.client.command(method, params);
  const load = `process.mainModule.require(${JSON.stringify(file)})`;
  let breakpoint, enabled = false, stage = 'prepare-boundary', failed = false;
  const callFrames = [];
  const reference = response => {
    if (response.exceptionDetails || !response.result?.objectId) throw Error();
    return response.result.objectId;
  };
  try {
    const prepared = await bindNativeModule(connection, {modulePath: file,
      config: {anonymousIsolated: true, verifyBroker: options.verifyBroker === true},
      pinnedOriginalVerified: true});
    if (prepared?.ok !== true) throw Error();
    stage = 'boundary-reference';
    const boundary = reference(await command('Runtime.evaluate', {
      expression: load + '.boundary', returnByValue: false, objectGroup: group,
    }));
    await command('Debugger.enable');enabled = true;
    breakpoint = await command('Debugger.setBreakpointOnFunctionCall', {objectId: boundary});
    const paused = connection.client.event('Debugger.paused');
    paused.catch(() => {});
    stage = 'single-sdk-trigger';
    const invoked = command('Runtime.evaluate', {expression: `void ${load}.trigger()`, returnByValue: true});
    invoked.catch(() => {});
    const state = await paused;
    if (!state.hitBreakpoints?.includes(breakpoint.breakpointId)) throw Error();
    const frames = state.callFrames?.slice(1, 12) || [];
    for (const frame of frames) {
      const name = frame.functionName;
      callFrames.push({name: /^[A-Za-z_$][A-Za-z0-9_$]{0,99}$/.test(name || '') ? name : '',
        hasLocation: Boolean(frame.functionLocation)});
    }
    const names = [...new Set(callFrames.map(frame => frame.name).filter(Boolean))];
    if (!names.length) throw Error();
    stage = 'query-actual-caller-functions';
    const prototype = reference(await command('Runtime.evaluate', {
      expression: 'Function.prototype', returnByValue: false, objectGroup: group,
    }));
    const query = await command('Runtime.queryObjects', {prototypeObjectId: prototype, objectGroup: group});
    if (!query.objects?.objectId) throw Error();
    const candidates = reference(await command('Runtime.callFunctionOn', {
      objectId: query.objects.objectId, objectGroup: group, returnByValue: false,
      arguments: [{value: names}],
      functionDeclaration: `function(names){
        const types=process.mainModule.require('node:util').types;
        if(this.length>200000)throw Error();
        const found=this.filter(fn=>typeof fn==='function'&&!types.isProxy(fn)&&
          types.isAsyncFunction(fn)&&Object.getOwnPropertyDescriptor(fn,'length')?.value===1&&
          names.includes(Object.getOwnPropertyDescriptor(fn,'name')?.value));
        if(found.length<1||found.length>32)throw Error();
        return found;
      }`,
    }));
    const properties = await command('Runtime.getProperties', {objectId: candidates, ownProperties: true});
    const matches = [];
    stage = 'match-actual-caller-location';
    for (const item of properties.result || []) {
      if (!/^\d+$/.test(item.name) || !item.value?.objectId) continue;
      const metadata = await command('Runtime.getProperties', {objectId: item.value.objectId, ownProperties: true});
      const location = metadata.internalProperties?.find(property =>
        property.name === '[[FunctionLocation]]')?.value?.value;
      if (!location) continue;
      const frame = frames.find(frame => frame.functionLocation &&
        ['scriptId', 'lineNumber', 'columnNumber'].every(key =>
          frame.functionLocation[key] === location[key]));
      if (frame) {
        const name = metadata.result?.find(property => property.name === 'name')?.value?.value;
        if (!/^[A-Za-z_$][A-Za-z0-9_$]{0,99}$/.test(name || '')) throw Error();
        matches.push({objectId: item.value.objectId, index: frames.indexOf(frame),
          descriptor: {name, lineNumber: location.lineNumber, columnNumber: location.columnNumber}});
      }
    }
    // The nearest actual async one-argument caller is the request builder.
    // Ties are ambiguous. No minified identifier is hardcoded.
    matches.sort((left, right) => left.index - right.index);
    if (!matches.length || matches[1]?.index === matches[0].index) throw Error();
    stage = 'capture-actual-builder';
    const attached = await command('Runtime.callFunctionOn', {
      objectId: matches[0].objectId, returnByValue: true, objectGroup: group,
      functionDeclaration: 'function(file){return process.mainModule.require(file).attachBuilder(this)}',
      arguments: [{value: file}],
    });
    if (attached.exceptionDetails || attached.result?.value !== true) throw Error();
    await command('Debugger.removeBreakpoint', {breakpointId: breakpoint.breakpointId});breakpoint = null;
    await command('Debugger.resume');
    await command('Debugger.disable');enabled = false;
    const triggerResult = await invoked;
    if (triggerResult.exceptionDetails) throw Error();
    stage = 'verify-actual-builder';
    const checked = await command('Runtime.evaluate', {
      expression: load + '.verify()', returnByValue: true, awaitPromise: true,
    });
    if (checked.exceptionDetails || !checked.result?.value) throw Error();
    return {...checked.result.value, builderDescriptor: matches[0].descriptor, callerMetadata: callFrames};
  } catch {
    failed = true;
    throw Object.assign(Error('Original request builder discovery did not complete; no automatic replay'), {
      requestDiscoveryDiagnostic: {stage, callerMetadata: callFrames},
    });
  } finally {
    if (breakpoint?.breakpointId) {
      try {await command('Debugger.removeBreakpoint', {breakpointId: breakpoint.breakpointId});} catch {}
    }
    if (enabled) {
      try {await command('Debugger.resume');} catch {}
      try {await command('Debugger.disable');} catch {}
    }
    try {await command('Runtime.evaluate', {expression: load + '.close()', returnByValue: true});} catch {}
    try {await command('Runtime.releaseObjectGroup', {objectGroup: group});}
    catch (error) {if (!failed) throw error;}
  }
}
module.exports = {discoverNativeRequestBuilder};
