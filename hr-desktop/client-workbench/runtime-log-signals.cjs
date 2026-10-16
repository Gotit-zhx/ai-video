// Disposable probe tooling. Keep only fixed diagnostic categories, never
// vendor log lines, response bodies, tokens, paths or account information.
const PATTERNS = {
  nativeFatal: /(?:FATAL ERROR:|Fatal error in|Check failed:|Assertion failed:)/i,
  rustPanic: /(?:panicked at|thread ['"][^'"\r\n]{0,100}['"] panicked)/i,
  jsUnhandled: /(?:uncaughtException|UnhandledPromiseRejection|ERR_UNHANDLED_REJECTION)/,
  debuggerDisconnectWait: /Waiting for (?:the )?debugger to disconnect/i,
  nativeThreadsafeCall: /\bnapi_call_threadsafe_function\b/,
  nativeThreadsafeRelease: /\bnapi_release_threadsafe_function\b/,
  nativeEnvironmentCleanup: /\b(?:napi_add_env_cleanup_hook|napi_remove_env_cleanup_hook)\b/,
  inspectorStarted: /Debugger listening on ws:/,
  inspectorAddressInUse: /Starting inspector[^\r\n]{0,250}address already in use/i,
  invalidRuntimeOption: /(?:bad option:|unknown option:|unrecognized flag)/i,
  cachedBytecodeRejected: /(?:Invalid or incompatible cached data|cachedDataRejected)/i,
};

class RuntimeLogSignals {
  constructor() {
    this.started = Date.now();
    this.tails = new Map();
    this.found = new Map();
  }
  observe(stream, chunk) {
    if (!['stdout', 'stderr'].includes(stream)) throw Error('Invalid probe log stream');
    const text = (this.tails.get(stream) || '') + chunk.toString('utf8');
    for (const [name, pattern] of Object.entries(PATTERNS)) {
      if (!this.found.has(name) && pattern.test(text)) {
        this.found.set(name, {name, stream, firstObservedMs: Date.now() - this.started});
      }
    }
    this.tails.set(stream, text.slice(-512));
  }
  snapshot() {
    return {rawLogsRetained: false, signals: [...this.found.values()]};
  }
  close() {
    this.tails.clear();
    return this.snapshot();
  }
}

module.exports = {RuntimeLogSignals};
