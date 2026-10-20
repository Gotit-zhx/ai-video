const {runtimeExecutable} = require('./runtime-paths.cjs');

class NativeNetwork {
  constructor({app, session, egress, offline = false}) {
    this.app = app;
    this.session = session;
    this.egress = egress;
    this.offline = offline;
    this.stopped = false;
    this.prepared = new WeakMap();
    egress.on('stopped', () => {this.stopped = true;});
    app.on('login', (event, _contents, _details, info, callback) =>
      egress.authenticate(event, info, callback));
    app.on('before-quit', () => {this.stopped = true;void egress.close();});
  }
  ensure() {
    if (!this.starting) {
      this.starting = this.app.whenReady().then(async () => {
        await this.egress.start(this.offline);
        if (this.stopped) throw Error('网卡直连已停止，请重新启动助手');
        return this.egress;
      });
    }
    return this.starting.then(egress => {
      if (this.stopped) throw Error('网卡直连已停止，请重新启动助手');
      return egress;
    });
  }
  async configure(target) {
    await this.ensure();
    if (!this.prepared.has(target)) {
      this.prepared.set(target, this.egress.configure(target));
    }
    await this.prepared.get(target);
    if (this.stopped) throw Error('网卡直连已停止，请重新启动助手');
    return this.egress;
  }
  proxy() {
    if (this.stopped || !this.egress.info) {
      throw Error('网卡直连尚未就绪，已停止外部请求');
    }
    return {proxy: `http://127.0.0.1:${this.egress.info.port}`,
      username: 'heyi', password: this.egress.info.secret};
  }
  attachNative(service) {
    // The original HTTP and WebSocket transports keep their implementation,
    // authentication and TLS payload. Their proxy source is our NIC relay.
    // Do not persist the ephemeral relay credential in the vendor settings.
    service.getHttpProxy = () => this.proxy();
    service.getDNSProxy = () => undefined;
    service.getIPProxy = () => undefined;
    service.setProxy = () => this.configure(this.session.defaultSession);
    service.stopProxy = () => this.configure(this.session.defaultSession);
    service.stopServices = async () => {};
    return this;
  }
}

let shared;
function instance(options) {
  if (options?.offline && (!process.env.HEYI_WORKBENCH_TEST_DATA ||
      process.env.HEYI_WORKBENCH_TEST_PORT !== '0')) {
    throw Error('Offline networking requires an isolated test profile');
  }
  if (shared && options && shared.offline !== Boolean(options.offline)) {
    throw Error('Native networking mode is already fixed for this process');
  }
  if (!shared) {
    const {app, session} = require('electron');
    const {DirectEgress} = require('./src/direct-network.cjs');
    shared = new NativeNetwork({app, session,
      egress: new DirectEgress(runtimeExecutable()), offline: Boolean(options?.offline)});
  }
  return shared;
}
module.exports = {NativeNetwork, instance};
