// Exact known function names only. Authentication values and getters must never
// be read or logged, even when they share one of these names.
const {types} = require('node:util');
const AUTH_FUNCTIONS = Object.freeze(['getAuthCookieHeader','getZpToken',
  'getClientHeaders','createClient','enfetch']);

function describeFunctions(value) {
  if (!value || !['function','object'].includes(typeof value) || types.isProxy(value)) return [];
  const found = [];
  for (const name of AUTH_FUNCTIONS) {
    const descriptor = Object.getOwnPropertyDescriptor(value,name);
    const fn = descriptor?.value;
    if (typeof fn !== 'function' || types.isProxy(fn)) continue;
    const length = Object.getOwnPropertyDescriptor(fn,'length')?.value;
    found.push({name,argumentCount:Number.isSafeInteger(length) && length>=0 && length<100 ? length : null});
  }
  return found;
}

function nativeAuthMetadata(mainModule, globals, cache) {
  const providers = [
    {scope:'main-exports',functions:describeFunctions(mainModule?.exports)},
    {scope:'global',functions:describeFunctions(globals)},
  ];
  for (const [file,module] of Object.entries(cache || {})) {
    if (!/[\\/]node_modules[\\/]@bzl[\\/]electron-node-fetch[\\/]dist[\\/]index\.js$/.test(file)) continue;
    providers.push({scope:'original-native-http-module',functions:describeFunctions(module.exports)});
  }
  return {mode:'known-auth-function-metadata-only',originalMethodsInvoked:0,
    rawValuesRetained:false,providers};
}

module.exports = {describeFunctions,nativeAuthMetadata};
