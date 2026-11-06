const test = require("node:test");
const assert = require("node:assert/strict");
const { AccountPoolManager, normalizePoolConfig } = require("../src/core/account-pool-manager");

class FakeBrowserActClient {
  constructor(loginByEmail = {}) {
    this.opened = [];
    this.closed = [];
    this.imported = [];
    this.adopted = [];
    this.navigated = [];
    this.loginByEmail = new Map(Object.entries(loginByEmail));
    this.profileImportEmail = null;
  }
  async ensureSession(runtime, url) {
    this.opened.push({ runtime, url });
    return runtime.sessionId;
  }
  async closeSession(sessionId) {
    this.closed.push(sessionId);
    return { ok: true };
  }
  async verifyGoogleAccount(_sessionId, expectedEmail) {
    const ok = this.loginByEmail.get(expectedEmail) !== false;
    return {
      ok,
      expectedEmail,
      observedEmails: ok ? [expectedEmail] : [],
      checkedAt: new Date().toISOString(),
      reason: ok ? null : "未检测到有效的 Google 登录状态"
    };
  }
  async checkGoogleSessionHealth(sessionId) {
    const accountId = String(sessionId).match(/(?:pool_)?(user-\d+)/)?.[1];
    const number = accountId?.match(/\d+/)?.[0];
    const email = number ? `user${number}@example.com` : null;
    const ok = email ? this.loginByEmail.get(email) !== false : true;
    return {
      ok,
      alive: true,
      authRequired: !ok,
      checkedAt: new Date().toISOString(),
      reason: ok ? null : "Google login has expired"
    };
  }
  async navigateSession(sessionId, url) {
    this.navigated.push({ sessionId, url });
    return { ok: true, url };
  }
  adoptSession(sessionId, browserId) {
    this.adopted.push({ sessionId, browserId });
  }
  async importProfile(browserId, profileId) {
    this.imported.push({ browserId, profileId });
    if (this.profileImportEmail) this.loginByEmail.delete(this.profileImportEmail);
    return { ok: true };
  }
  async remoteAssist(sessionId) {
    return { ok: true, sessionId, url: "https://assist.example.test/session" };
  }
}

function fixture(count = 7) {
  const accounts = Array.from({ length: count }, (_, index) => ({
    id: `user-${index + 1}`,
    label: `用户 ${index + 1}`,
    email: `user${index + 1}@example.com`,
    browserId: `browser-${index + 1}`,
    profileId: `profile-${index + 1}`,
    aliases: [`账号${index + 1}`],
    enabled: true
  }));
  return {
    config: {
      enabled: true,
      poolSize: 5,
      startUrl: "https://myaccount.google.com/",
      accounts
    },
    browsers: accounts.map(account => ({ id: account.browserId }))
  };
}

test("一次启动五个不同账号", async () => {
  const client = new FakeBrowserActClient();
  const manager = new AccountPoolManager(client);
  const { config, browsers } = fixture();
  const result = await manager.start(config, browsers);
  assert.equal(result.activeCount, 5);
  assert.equal(new Set(result.active.map(item => item.email)).size, 5);
  assert.equal(new Set(result.active.map(item => item.browserId)).size, 5);
  assert.equal(result.waiting.length, 2);
});

test("账号启动时直接打开平台目标页面", async () => {
  const client = new FakeBrowserActClient();
  const manager = new AccountPoolManager(client);
  const { config, browsers } = fixture(1);
  config.poolSize = 1;
  config.healthCheckUrl = "https://myaccount.google.com/";
  config.startUrl = "https://gemini.google.com/videos";
  await manager.start(config, browsers);
  assert.equal(client.opened.length, 1);
  assert.equal(client.opened[0].runtime.headed, true);
  assert.equal(client.opened[0].url, config.startUrl);
  assert.equal(client.navigated.length, 0);
});

test("已验证账号再次启动时仍直接打开平台目标页面", async () => {
  const client = new FakeBrowserActClient();
  const manager = new AccountPoolManager(client);
  const { config, browsers } = fixture(1);
  config.poolSize = 1;
  config.healthCheckUrl = "https://myaccount.google.com/";
  config.startUrl = "https://gemini.google.com/videos";
  config.accounts[0].lastVerifiedAt = new Date().toISOString();
  await manager.start(config, browsers);
  assert.equal(client.opened.length, 1);
  assert.equal(client.opened[0].runtime.headed, true);
  assert.equal(client.opened[0].url, config.startUrl);
  assert.equal(client.navigated.length, 0);
});

test("明确关闭后等待平台决定是否再启动其他账号", async () => {
  const client = new FakeBrowserActClient();
  const manager = new AccountPoolManager(client);
  const { config, browsers } = fixture();
  await manager.start(config, browsers);
  const result = await manager.handleInstruction("请关闭 user2@example.com", config, browsers);
  assert.equal(result.closed.email, "user2@example.com");
  assert.equal(result.replacement, null);
  assert.equal(result.pool.activeCount, 4);
  assert.ok(result.pool.waiting.some(account => account.email === "user2@example.com"));
  assert.ok(client.closed.includes(result.closed.sessionId));
});

test("增量启动指定账号不会关闭已经运行的其他账号", async () => {
  const client = new FakeBrowserActClient();
  const manager = new AccountPoolManager(client);
  const { config, browsers } = fixture(3);
  config.poolSize = 3;
  await manager.startAccounts(config, ["user-1"], browsers, []);
  const firstSession = manager.snapshot(config).active.find(item => item.accountId === "user-1");
  const result = await manager.startAccounts(config, ["user-2"], browsers, []);
  assert.equal(result.activeCount, 2);
  assert.ok(result.active.some(item => item.accountId === "user-1"));
  assert.ok(result.active.some(item => item.accountId === "user-2"));
  assert.ok(!client.closed.includes(firstSession.sessionId));
});

test("页面任务可以选择全部活跃账号或唯一账号", async () => {
  const client = new FakeBrowserActClient();
  const manager = new AccountPoolManager(client);
  const { config, browsers } = fixture(3);
  config.poolSize = 3;
  await manager.start(config, browsers);
  assert.equal(manager.interactiveSessions("", true).length, 3);
  const selected = manager.interactiveSessions("user2@example.com", false);
  assert.equal(selected.length, 1);
  assert.equal(selected[0].accountId, "user-2");
  assert.throws(
    () => manager.interactiveSessions("missing@example.com", false),
    /当前活跃账号中没有找到/
  );
});

test("重新绑定账号前会释放旧的运行会话", async () => {
  const client = new FakeBrowserActClient();
  const manager = new AccountPoolManager(client);
  const { config, browsers } = fixture(1);
  config.poolSize = 1;
  await manager.start(config, browsers);
  await manager.releaseAccount("user-1");
  const result = manager.snapshot(config);
  assert.equal(result.activeCount, 0);
  assert.ok(client.closed.some(sessionId => sessionId.startsWith("heyi_pool_user-1_")));
});

test("没有明确关闭词时拒绝执行", async () => {
  const manager = new AccountPoolManager(new FakeBrowserActClient());
  const { config, browsers } = fixture();
  await manager.start(config, browsers);
  await assert.rejects(
    () => manager.handleInstruction("查看 user1@example.com", config, browsers),
    /明确关闭指令/
  );
});

test("少于五个独立账号时拒绝启动", async () => {
  const manager = new AccountPoolManager(new FakeBrowserActClient());
  const { config, browsers } = fixture(4);
  await assert.rejects(() => manager.start(config, browsers), /至少 5 个/);
});

test("未登录账号不会被计入活跃席位，并自动尝试下一个候补", async () => {
  const client = new FakeBrowserActClient({ "user1@example.com": false });
  const manager = new AccountPoolManager(client);
  const { config, browsers } = fixture(3);
  config.poolSize = 2;
  const result = await manager.start(config, browsers);
  assert.equal(result.activeCount, 2);
  assert.deepEqual(
    result.active.map(item => item.email).sort(),
    ["user2@example.com", "user3@example.com"]
  );
  assert.equal(result.authRequiredCount, 1);
  assert.equal(result.authRequired[0].email, "user1@example.com");
  assert.ok(client.closed.length >= 1);
});

test("确认恢复后重新导入 Profile、复验邮箱并补齐席位", async () => {
  const client = new FakeBrowserActClient({ "user1@example.com": false });
  client.profileImportEmail = "user1@example.com";
  const manager = new AccountPoolManager(client);
  const { config, browsers } = fixture(2);
  config.poolSize = 2;
  await assert.rejects(() => manager.start(config, browsers), /需要恢复登录/);
  const result = await manager.recoverAccount("user-1", config, browsers, []);
  assert.deepEqual(client.imported, [{ browserId: "browser-1", profileId: "profile-1" }]);
  assert.equal(result.pool.activeCount, 2);
  assert.equal(result.pool.authRequiredCount, 0);
  assert.ok(result.pool.active.some(item => item.email === "user1@example.com"));
});

test("重启后能够接管并验证助手遗留的账号池 session", async () => {
  const client = new FakeBrowserActClient();
  const manager = new AccountPoolManager(client);
  const { config, browsers } = fixture(1);
  config.poolSize = 1;
  const sessions = [{
    sessionId: "heyi_pool_user-1_old_session",
    browserId: "browser-1",
    updatedAtMs: 100
  }];
  const result = await manager.reconcile(config, browsers, sessions);
  assert.equal(result.activeCount, 1);
  assert.equal(result.active[0].status, "RESTORED");
  assert.deepEqual(client.adopted, [{
    sessionId: "heyi_pool_user-1_old_session",
    browserId: "browser-1"
  }]);
  assert.deepEqual(client.navigated.map(item => item.url), [
    config.startUrl
  ]);
});

test("智能体启动的 BrowserAct 会话会显示启动中且不会重复拉起", async () => {
  const client = new FakeBrowserActClient();
  const manager = new AccountPoolManager(client);
  const { config, browsers } = fixture(1);
  config.poolSize = 1;
  manager.syncControlledSessions(config, [{
    sessionId: "agent_user1_baidu",
    browserId: "browser-1",
    url: "https://www.baidu.com/",
    title: "百度一下",
    headed: true,
    updatedAtMs: 200
  }]);
  const beforeStart = manager.snapshot(config);
  assert.equal(beforeStart.activeCount, 1);
  assert.equal(beforeStart.controlledCount, 1);
  assert.equal(beforeStart.localAccounts[0].status, "CONTROLLED");
  assert.equal(beforeStart.localAccounts[0].sessionId, "agent_user1_baidu");
  const result = await manager.start(config, browsers);
  assert.equal(result.activeCount, 1);
  assert.equal(client.opened.length, 0);
});

test("受控 BrowserAct 会话可以按邮箱关闭且不会自行补位", async () => {
  const client = new FakeBrowserActClient();
  const manager = new AccountPoolManager(client);
  const { config, browsers } = fixture(2);
  config.poolSize = 1;
  manager.syncControlledSessions(config, [{
    sessionId: "agent_user1_baidu",
    browserId: "browser-1",
    updatedAtMs: 200
  }]);
  const result = await manager.handleInstruction("关闭 user1@example.com", config, browsers);
  assert.ok(client.closed.includes("agent_user1_baidu"));
  assert.equal(result.closed.controlled, true);
  assert.equal(result.replacement, null);
  assert.equal(result.pool.activeCount, 0);
});

test("运行中的账号登录失效后会被巡检移出活跃席位", async () => {
  const client = new FakeBrowserActClient();
  const manager = new AccountPoolManager(client);
  const { config, browsers } = fixture(1);
  config.poolSize = 1;
  await manager.start(config, browsers);
  client.loginByEmail.set("user1@example.com", false);
  const result = await manager.healthCheck(config, browsers);
  assert.equal(result.activeCount, 0);
  assert.equal(result.authRequiredCount, 1);
  assert.equal(result.status, "AUTH_REQUIRED");
});

test("登录巡检移除失效账号后不会自行启动候补账号", async () => {
  const client = new FakeBrowserActClient();
  const manager = new AccountPoolManager(client);
  const { config, browsers } = fixture(2);
  config.poolSize = 1;
  await manager.start(config, browsers);
  const openedBeforeHealthCheck = client.opened.length;
  client.loginByEmail.set("user1@example.com", false);
  const result = await manager.healthCheck(config, browsers);
  assert.equal(result.activeCount, 0);
  assert.equal(result.authRequiredCount, 1);
  assert.equal(client.opened.length, openedBeforeHealthCheck);
  assert.ok(result.waiting.some(account => account.email === "user2@example.com"));
});

test("巡检不会反复拉起一直不可用的空缺实例", async () => {
  const client = new FakeBrowserActClient();
  const originalEnsureSession = client.ensureSession.bind(client);
  client.ensureSession = async (runtime, url) => {
    if (runtime.browserId === "browser-2") throw new Error("Browser window not found");
    return originalEnsureSession(runtime, url);
  };
  const manager = new AccountPoolManager(client);
  const { config, browsers } = fixture(2);
  config.poolSize = 2;
  await assert.rejects(() => manager.start(config, browsers), /1\/2/);
  const openedAfterManualStart = client.opened.length;
  const result = await manager.healthCheck(config, browsers);
  assert.equal(result.activeCount, 1);
  assert.equal(client.opened.length, openedAfterManualStart);
});

test("独立环境手动登录完成后能够重新验证并补位", async () => {
  const client = new FakeBrowserActClient({ "user1@example.com": false });
  const manager = new AccountPoolManager(client);
  const { config, browsers } = fixture(1);
  config.poolSize = 1;
  await assert.rejects(() => manager.start(config, browsers), /需要恢复登录/);
  const assist = await manager.startLoginAssist("user-1", config, browsers);
  assert.equal(assist.url, "https://assist.example.test/session");
  assert.equal(client.opened.at(-1).runtime.headed, false);
  client.loginByEmail.delete("user1@example.com");
  const result = await manager.finishLoginAssist("user-1", config, browsers);
  assert.equal(result.pool.activeCount, 1);
  assert.equal(result.pool.authRequiredCount, 0);
});

test("本机 Chrome 新 Profile 不会自动加入 BrowserAct 账号池", () => {
  const manager = new AccountPoolManager(new FakeBrowserActClient());
  const { config } = fixture(2);
  config.poolSize = 2;
  manager.syncLocalProfiles(config, [
    { id: "profile-1", name: "用户 1", accountEmail: "user1@example.com", browserName: "Chrome" },
    { id: "profile-2", name: "用户 2", accountEmail: "user2@example.com", browserName: "Chrome" },
    { id: "profile-3", name: "新用户", accountEmail: "new@example.com", browserName: "Chrome" }
  ]);
  const result = manager.snapshot(config);
  assert.equal(result.discoveredCount, 0);
  assert.equal(result.localAccounts.length, 2);
  assert.ok(!result.localAccounts.some(account => account.email === "new@example.com"));
});

test("本机 Profile 邮箱变化不会改写 BrowserAct 账号", () => {
  const manager = new AccountPoolManager(new FakeBrowserActClient());
  const { config } = fixture(1);
  config.poolSize = 1;
  manager.syncLocalProfiles(config, [
    { id: "profile-1", name: "用户 1", accountEmail: "changed@example.com", browserName: "Chrome" }
  ]);
  const result = manager.snapshot(config);
  assert.equal(result.discoveredCount, 0);
  assert.equal(result.localAccounts[0].email, "user1@example.com");
});

test("账号配置会保留本机 Profile 和 Cookie 同步时间", () => {
  const pool = normalizePoolConfig({
    accounts: [{
      id: "user-1",
      email: "user1@example.com",
      browserId: "browser-1",
      profileId: "profile-1",
      profileName: "用户 1",
      profileDirectory: "Default",
      sourceCookieUpdatedAtMs: 2000,
      lastImportedCookieUpdatedAtMs: 1000,
      lastVerifiedAt: "2026-08-04T10:00:00.000Z"
    }]
  });
  assert.equal(pool.accounts[0].profileId, "profile-1");
  assert.equal(pool.accounts[0].profileDirectory, "Default");
  assert.equal(pool.accounts[0].sourceCookieUpdatedAtMs, 2000);
  assert.equal(pool.accounts[0].lastImportedCookieUpdatedAtMs, 1000);
});

test("本机 Cookie 比上次导入更新时显示同步并启动", () => {
  const manager = new AccountPoolManager(new FakeBrowserActClient());
  const { config } = fixture(1);
  config.poolSize = 1;
  config.accounts[0].lastImportedCookieUpdatedAtMs = 1000;
  manager.syncLocalProfiles(config, [{
    id: "profile-1",
    name: "用户 1",
    accountEmail: "user1@example.com",
    browserName: "Chrome",
    cookieUpdatedAtMs: 3000
  }]);
  const result = manager.snapshot(config);
  assert.equal(result.localAccounts[0].status, "SYNC_REQUIRED");
  assert.equal(result.localAccounts[0].syncRequired, true);
  assert.equal(result.localAccounts[0].reason, "本机 Chrome 登录态已更新");
});

test("本机 Cookie 时间变化不会阻止 BrowserAct 直接打开目标页", async () => {
  const client = new FakeBrowserActClient();
  const manager = new AccountPoolManager(client);
  const { config, browsers } = fixture(1);
  config.poolSize = 1;
  config.healthCheckUrl = "https://myaccount.google.com/";
  config.startUrl = "https://gemini.google.com/app";
  config.accounts[0].lastImportedCookieUpdatedAtMs = 1000;
  manager.syncLocalProfiles(config, [{
    id: "profile-1",
    name: "用户 1",
    accountEmail: "user1@example.com",
    browserName: "Chrome",
    cookieUpdatedAtMs: 3000
  }]);
  const started = await manager.start(config, browsers);
  assert.equal(started.activeCount, 1);
  const result = manager.snapshot(config);
  assert.equal(result.activeCount, 1);
  assert.equal(result.localAccounts[0].status, "SYNC_REQUIRED");
  assert.equal(client.opened.length, 1);
  assert.equal(client.opened[0].runtime.headed, true);
  assert.equal(client.opened[0].url, config.startUrl);
  assert.equal(client.navigated.length, 0);
});
