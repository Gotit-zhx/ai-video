function installWebTypes() {
  if (['Request', 'Response', 'Headers', 'FormData', 'fetch']
    .every(name => typeof globalThis[name] !== 'undefined')) return;
  const web = require('undici');
  for (const name of ['Request', 'Response', 'Headers', 'FormData', 'fetch']) {
    if (typeof globalThis[name] === 'undefined') globalThis[name] = web[name];
  }
}
module.exports = {installWebTypes};
