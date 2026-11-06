const {Runtime} = require('./core.cjs');

class NativeRuntime extends Runtime {
  constructor(executable, network, native, account) {
    super(executable, network);
    this.native = native;
    this.account = account;
  }
  run(input, timeout) {
    return super.run({...input, native: {
      url: this.native.url, secret: this.native.secret, accountId: this.account(),
    }}, timeout);
  }
}
module.exports = {NativeRuntime};
