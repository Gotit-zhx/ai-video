// The Windows renderer's original _sdk_exit calls app.exit directly, without
// before-quit. Drain our state before handing that exact action back to it.
function installNativeExitGuard({targets, beforeExit}) {
  if (!Array.isArray(targets) || !targets[0] || typeof beforeExit !== 'function') {
    throw Error('Original exit targets and session drain required');
  }
  const changes = [];
  let pending;
  const close = () => {
    for (const {target, previous, hook} of changes.splice(0).reverse()) {
      if (Object.getOwnPropertyDescriptor(target, '_sdk_exit')?.value !== hook) continue;
      if (previous) Object.defineProperty(target, '_sdk_exit', previous);
      else delete target._sdk_exit;
    }
  };
  try {
    for (const target of new Set(targets)) {
      if (!target) continue;
      const previous = Object.getOwnPropertyDescriptor(target, '_sdk_exit');
      const original = previous?.value || (!previous ? target._sdk_exit : undefined);
      if (typeof original !== 'function') {
        if (target === targets[0]) throw Error('Original exit method unavailable');
        continue;
      }
      if (previous?.configurable === false) throw Error('Original exit method cannot be wrapped');
      const hook = function(...args) {
        // Repeated close clicks must share the drain and cannot bypass it.
        if (!pending) pending = Promise.resolve().then(beforeExit)
          .then(() => Reflect.apply(original, this, args));
        return pending;
      };
      Object.defineProperty(target, '_sdk_exit', {
        value: hook, configurable: true, writable: true, enumerable: previous?.enumerable ?? false,
      });
      changes.push({target, previous, hook});
    }
    return {close};
  } catch (error) {close();throw error;}
}
module.exports = {installNativeExitGuard};
