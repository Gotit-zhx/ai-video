const crypto = require("node:crypto");

const DEFAULT_POOL_SIZE = 5;
const DEFAULT_GOOGLE_HEALTH_URL = "https://myaccount.google.com/";
const SAFE_ACCOUNT_ID = /^[A-Za-z0-9._-]{1,80}$/;

function normalizeText(value) {
  return String(value || "").trim().toLocaleLowerCase();
}

function normalizePoolConfig(value = {}) {
  const poolSize = Math.max(1, Math.min(10, Number(value.poolSize) || DEFAULT_POOL_SIZE));
  const accounts = Array.isArray(value.accounts) ? value.accounts.map((item, index) => ({
    id: String(item.id || `account-${index + 1}`).trim().replace(/[^A-Za-z0-9._-]/g, "-").slice(0, 80),
    label: String(item.label || "").trim().slice(0, 120),
    email: String(item.email || "").trim().toLowerCase().slice(0, 254),
    browserId: String(item.browserId || "").trim().slice(0, 120),
    profileId: String(item.profileId || "").trim().slice(0, 120),
    profileName: String(item.profileName || "").trim().slice(0, 120),
    profileDirectory: String(item.profileDirectory || "").trim().slice(0, 120),
    sourceCookieUpdatedAtMs: Math.max(0, Number(item.sourceCookieUpdatedAtMs) || 0),
    lastImportedCookieUpdatedAtMs: Math.max(0, Number(item.lastImportedCookieUpdatedAtMs) || 0),
    lastVerifiedAt: String(item.lastVerifiedAt || "").trim().slice(0, 40),
    aliases: Array.isArray(item.aliases)
      ? item.aliases.map(alias => String(alias || "").trim().slice(0, 80)).filter(Boolean).slice(0, 20)
      : [],
    enabled: item.enabled !== false
  })).filter(account => SAFE_ACCOUNT_ID.test(account.id)) : [];
  return {
    enabled: value.enabled !== false,
    autoStart: value.autoStart === true,
    poolSize,
    startUrl: String(value.startUrl || DEFAULT_GOOGLE_HEALTH_URL).trim().slice(0, 2000),
    healthCheckUrl: String(value.healthCheckUrl || DEFAULT_GOOGLE_HEALTH_URL).trim().slice(0, 2000),
    accounts
  };
}

class AccountPoolManager {
  constructor(browserActClient, options = {}) {
    this.browserActClient = browserActClient;
    this.onEvent = options.onEvent || (() => {});
    this.active = new Map();
    this.controlled = new Map();
    this.authRequired = new Map();
    this.recovering = new Set();
    this.loginAssist = new Map();
    this.discoveredAccounts = [];
    this.localProfiles = [];
    this.seenProfileIds = new Set();
    this.rotation = [];
    this.starting = false;
    this.healthChecking = false;
    this.lastHealthCheckAt = null;
    this.lastError = null;
  }

  configuredAccounts(config) {
    return normalizePoolConfig(config).accounts.filter(account =>
      account.enabled && account.email && account.browserId
    );
  }

  syncLocalProfiles(config, profiles = []) {
    const pool = normalizePoolConfig(config);
    this.localProfiles = profiles.map(profile => ({
      profileId: String(profile.id || "").trim(),
      profileName: String(profile.name || "未命名用户").trim(),
      email: String(profile.accountEmail || "").trim().toLowerCase(),
      browserName: String(profile.browserName || "Chrome").trim(),
      cookieUpdatedAtMs: Math.max(0, Number(profile.cookieUpdatedAtMs) || 0)
    })).filter(profile => profile.profileId && profile.email);
    const configuredEmails = new Set(pool.accounts.map(account => account.email).filter(Boolean));
    const configuredByProfile = new Map(
      pool.accounts.filter(account => account.profileId).map(account => [account.profileId, account])
    );
    const discovered = profiles.map(profile => ({
      profileId: String(profile.id || "").trim(),
      profileName: String(profile.name || "未命名 Profile").trim(),
      email: String(profile.accountEmail || "").trim().toLowerCase(),
      browserName: String(profile.browserName || "Chrome").trim()
    })).filter(profile => profile.profileId && profile.email).flatMap(profile => {
      const configured = configuredByProfile.get(profile.profileId);
      if (configured?.email === profile.email || (!configured && configuredEmails.has(profile.email))) return [];
      return [{
        ...profile,
        kind: configured ? "PROFILE_EMAIL_CHANGED" : "NEW_PROFILE",
        previousEmail: configured?.email || "",
        detectedAt: new Date().toISOString()
      }];
    });
    for (const account of discovered) {
      const key = `${account.profileId}:${account.email}`;
      if (!this.seenProfileIds.has(key)) {
        this.seenProfileIds.add(key);
        this.onEvent(`检测到新的 Google 账号：${account.email}（${account.profileName}）`, "success");
      }
    }
    this.discoveredAccounts = discovered;
    return discovered;
  }

  validate(config, browsers = []) {
    const pool = normalizePoolConfig(config);
    const accounts = this.configuredAccounts(pool);
    const browserIds = new Set();
    const emails = new Set();
    const profileIds = new Set();
    for (const account of accounts) {
      if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(account.email)) {
        throw new Error(`账号池邮箱格式不正确：${account.email}`);
      }
      if (browserIds.has(account.browserId)) {
        throw new Error(`账号池不能共用同一个 BrowserAct 环境：${account.browserId}`);
      }
      if (emails.has(account.email)) throw new Error(`账号池邮箱重复：${account.email}`);
      if (account.profileId && profileIds.has(account.profileId)) {
        throw new Error(`账号池不能共用同一个本机 Chrome Profile：${account.profileId}`);
      }
      browserIds.add(account.browserId);
      emails.add(account.email);
      if (account.profileId) profileIds.add(account.profileId);
      if (browsers.length && !browsers.some(browser => browser.id === account.browserId)) {
        throw new Error(`BrowserAct 环境不存在：${account.browserId}`);
      }
    }
    if (accounts.length < pool.poolSize) {
      throw new Error(`需要至少 ${pool.poolSize} 个已启用且已绑定的不同账号，目前只有 ${accounts.length} 个`);
    }
    new URL(pool.startUrl);
    new URL(pool.healthCheckUrl);
    return { pool, accounts };
  }

  syncRotation(accounts) {
    const availableIds = new Set(accounts.map(account => account.id));
    this.rotation = this.rotation.filter(id => availableIds.has(id));
    for (const id of this.authRequired.keys()) {
      if (!availableIds.has(id)) this.authRequired.delete(id);
    }
    for (const account of accounts) {
      if (!this.rotation.includes(account.id)) this.rotation.push(account.id);
    }
  }

  nextWaitingAccount(accounts) {
    this.syncRotation(accounts);
    const activeIds = new Set([...this.active.keys(), ...this.controlled.keys()]);
    const id = this.rotation.find(accountId =>
      !activeIds.has(accountId) && !this.authRequired.has(accountId)
    );
    return accounts.find(account => account.id === id) || null;
  }

  occupiedCount() {
    return new Set([...this.active.keys(), ...this.controlled.keys()]).size;
  }

  localProfileFor(account) {
    return this.localProfiles.find(profile =>
      (account.profileId && profile.profileId === account.profileId)
      || profile.email === account.email
    ) || null;
  }

  sourceLoginChanged(account) {
    const profile = this.localProfileFor(account);
    return Boolean(
      profile
      && account.lastImportedCookieUpdatedAtMs > 0
      && profile.cookieUpdatedAtMs > account.lastImportedCookieUpdatedAtMs + 1000
    );
  }

  syncControlledSessions(config, sessions = []) {
    const accounts = this.configuredAccounts(config);
    this.syncRotation(accounts);
    const configuredIds = new Set(accounts.map(account => account.id));
    for (const id of this.controlled.keys()) {
      if (!configuredIds.has(id) || this.active.has(id)) this.controlled.delete(id);
    }
    const transient = /^(heyi_(?:import_verify|health|login|add)_)/i;
    const ownedSessionIds = new Set([
      ...[...this.active.values()].map(session => session.sessionId),
      ...[...this.loginAssist.values()].map(session => session.sessionId)
    ]);
    for (const account of accounts) {
      if (this.active.has(account.id)) {
        this.controlled.delete(account.id);
        continue;
      }
      const matches = sessions
        .filter(session => session.browserId === account.browserId)
        .filter(session => session.sessionId && !ownedSessionIds.has(session.sessionId))
        .filter(session => !transient.test(session.sessionId))
        .sort((left, right) => Number(right.updatedAtMs || 0) - Number(left.updatedAtMs || 0));
      if (!matches.length) {
        this.controlled.delete(account.id);
        continue;
      }
      const primary = matches[0];
      this.controlled.set(account.id, {
        accountId: account.id,
        label: account.label,
        email: account.email,
        aliases: account.aliases || [],
        browserId: account.browserId,
        profileId: account.profileId,
        sessionId: primary.sessionId,
        sessionIds: matches.map(session => session.sessionId),
        startUrl: primary.url || "",
        title: primary.title || "",
        status: "CONTROLLED",
        controlled: true,
        headed: primary.headed !== false,
        updatedAtMs: Number(primary.updatedAtMs || 0)
      });
      this.authRequired.delete(account.id);
    }
    return [...this.controlled.values()];
  }

  recordAuthRequired(account, verification = {}) {
    const entry = {
      accountId: account.id,
      label: account.label,
      email: account.email,
      browserId: account.browserId,
      profileId: account.profileId,
      status: "AUTH_REQUIRED",
      reason: String(verification.reason || "Google 登录状态已失效"),
      observedEmails: Array.isArray(verification.observedEmails) ? verification.observedEmails : [],
      detectedAt: new Date().toISOString(),
      recoverable: Boolean(account.profileId)
    };
    this.authRequired.set(account.id, entry);
    this.onEvent(`${account.email} 登录状态无效，已停止计入活跃席位`, "warning");
    return entry;
  }

  sessionRecord(account, sessionId, pool, verification, status = "VERIFIED") {
    return {
      accountId: account.id,
      label: account.label,
      email: account.email,
      aliases: account.aliases || [],
      browserId: account.browserId,
      profileId: account.profileId,
      sessionId,
      startUrl: pool.startUrl,
      status,
      verifiedAt: verification.checkedAt || new Date().toISOString(),
      openedAt: new Date().toISOString()
    };
  }

  activateVerifiedAccount(account, sessionId, pool, verification, status = "VERIFIED") {
    const session = this.sessionRecord(account, sessionId, pool, verification, status);
    this.active.set(account.id, session);
    this.controlled.delete(account.id);
    this.authRequired.delete(account.id);
    this.onEvent(`账号席位已验证并上线：${account.email}`, "success");
    return session;
  }

  async openAccount(account, pool) {
    if (this.active.has(account.id)) return this.active.get(account.id);
    if (this.controlled.has(account.id)) return this.controlled.get(account.id);
    const nonce = crypto.randomBytes(3).toString("hex");
    const sessionId = `heyi_pool_${account.id}_${Date.now().toString(36)}_${nonce}`.slice(0, 80);
    try {
      await this.browserActClient.ensureSession({
        browserId: account.browserId,
        sessionId,
        headed: true,
        allowedDomains: []
      }, pool.startUrl);
      const health = await this.browserActClient.checkGoogleSessionHealth(sessionId);
      if (!health.ok) {
        this.recordAuthRequired(account, health);
        const error = new Error(`${account.email} 的 Google 登录已失效`);
        error.code = "AUTH_REQUIRED";
        error.verification = health;
        throw error;
      }
      return this.activateVerifiedAccount(account, sessionId, pool, health);
    } catch (error) {
      await this.browserActClient.closeSession(sessionId).catch(() => {});
      throw error;
    }
  }

  async pruneDisabledAccounts(accounts) {
    const configuredIds = new Set(accounts.map(account => account.id));
    const stale = [...this.active.values()].filter(session => !configuredIds.has(session.accountId));
    await Promise.allSettled(stale.map(session => this.browserActClient.closeSession(session.sessionId)));
    for (const session of stale) this.active.delete(session.accountId);
    for (const accountId of this.controlled.keys()) {
      if (!configuredIds.has(accountId)) this.controlled.delete(accountId);
    }
  }

  async reconcile(config, browsers = [], sessions = []) {
    const { pool, accounts } = this.validate(config, browsers);
    await this.pruneDisabledAccounts(accounts);
    this.syncRotation(accounts);
    this.syncControlledSessions(pool, sessions);
    for (const account of accounts) {
      if (this.active.has(account.id)) continue;
      const prefix = `heyi_pool_${account.id}_`;
      const candidates = sessions
        .filter(session => session.browserId === account.browserId && session.sessionId.startsWith(prefix))
        .sort((left, right) => right.updatedAtMs - left.updatedAtMs);
      if (!candidates.length) continue;
      const [candidate, ...duplicates] = candidates;
      await Promise.allSettled(duplicates.map(session =>
        this.browserActClient.closeSession(session.sessionId)
      ));
      try {
        this.browserActClient.adoptSession(candidate.sessionId, account.browserId);
        await this.browserActClient.navigateSession(candidate.sessionId, pool.startUrl);
        const health = await this.browserActClient.checkGoogleSessionHealth(candidate.sessionId);
        if (!health.ok) {
          this.recordAuthRequired(account, health);
          const authError = new Error(`${account.email} 的 Google 登录已失效`);
          authError.code = "AUTH_REQUIRED";
          authError.verification = health;
          throw authError;
        }
        this.activateVerifiedAccount(account, candidate.sessionId, pool, health, "RESTORED");
      } catch (error) {
        await this.browserActClient.closeSession(candidate.sessionId).catch(() => {});
        if (error.code !== "AUTH_REQUIRED") {
          this.lastError = `${account.email} 的旧会话无法恢复：${error.message}`;
        }
      }
    }
    this.syncControlledSessions(pool, sessions);
    return this.snapshot(pool);
  }

  async fill(config, browsers = []) {
    const { pool, accounts } = this.validate(config, browsers);
    this.syncRotation(accounts);
    const results = [];
    let attempts = 0;
    while (this.occupiedCount() < pool.poolSize && attempts < accounts.length) {
      attempts += 1;
      const account = this.nextWaitingAccount(accounts);
      if (!account) break;
      try {
        results.push(await this.openAccount(account, pool));
      } catch (error) {
        this.rotation = this.rotation.filter(id => id !== account.id).concat(account.id);
        this.lastError = `${account.email} 启动失败：${error.message}`;
        this.onEvent(this.lastError, error.code === "AUTH_REQUIRED" ? "warning" : "error");
        if (results.length === 0 && this.occupiedCount() === 0 && error.code !== "AUTH_REQUIRED") throw error;
      }
    }
    return results;
  }

  async start(config, browsers = []) {
    if (this.starting) throw new Error("账号池正在启动");
    this.starting = true;
    this.lastError = null;
    try {
      const { pool, accounts } = this.validate(config, browsers);
      await this.pruneDisabledAccounts(accounts);
      this.syncRotation(accounts);
      const needed = Math.max(0, pool.poolSize - this.occupiedCount());
      const activeIds = new Set([...this.active.keys(), ...this.controlled.keys()]);
      const candidates = this.rotation
        .filter(accountId => !activeIds.has(accountId) && !this.authRequired.has(accountId))
        .map(accountId => accounts.find(account => account.id === accountId))
        .filter(Boolean)
        .slice(0, needed);
      const settled = await Promise.allSettled(
        candidates.map(account => this.openAccount(account, pool))
      );
      const failures = settled.filter(result => result.status === "rejected");
      if (failures.length) {
        this.lastError = failures.map(result => result.reason?.message || "启动失败").join("；");
      }
      if (this.occupiedCount() < pool.poolSize) await this.fill(pool, browsers).catch(() => {});
      if (this.occupiedCount() < pool.poolSize) {
        const authCount = this.authRequired.size;
        throw new Error(
          `账号池只有 ${this.occupiedCount()}/${pool.poolSize} 个运行席位`
          + (authCount ? `，${authCount} 个账号需要恢复登录` : "")
          + (this.lastError ? `：${this.lastError}` : "")
        );
      }
      this.lastError = null;
      this.onEvent(`账号池已启动：${this.occupiedCount()} 个账号环境`, "success");
      return this.snapshot(pool);
    } finally {
      this.starting = false;
    }
  }

  async startAccounts(config, requestedAccountIds = [], browsers = [], sessions = []) {
    if (this.starting) throw new Error("账号池正在启动");
    this.starting = true;
    this.lastError = null;
    try {
      const { pool, accounts } = this.validate(config, browsers);
      const requestedIds = new Set(
        (Array.isArray(requestedAccountIds) ? requestedAccountIds : [])
          .map(value => String(value || "").trim())
          .filter(Boolean)
      );
      const selected = requestedIds.size
        ? accounts.filter(account => requestedIds.has(account.id))
        : accounts;
      if (!selected.length || selected.length !== requestedIds.size && requestedIds.size) {
        throw new Error("指定启动的账号不在当前账号池中");
      }
      this.syncRotation(accounts);
      this.syncControlledSessions(pool, sessions);
      const settled = await Promise.allSettled(selected.map(async account => {
        const existing = this.active.get(account.id) || this.controlled.get(account.id);
        if (!existing) return this.openAccount(account, pool);
        try {
          await this.browserActClient.navigateSession(existing.sessionId, pool.startUrl);
          const health = await this.browserActClient.checkGoogleSessionHealth(existing.sessionId);
          if (!health.ok) {
            this.recordAuthRequired(account, health);
            const authError = new Error(`${account.email} 的 Google 登录已失效`);
            authError.code = "AUTH_REQUIRED";
            authError.verification = health;
            throw authError;
          }
          return this.activateVerifiedAccount(
            account,
            existing.sessionId,
            pool,
            health,
            existing.status === "CONTROLLED" ? "RESTORED" : existing.status
          );
        } catch (error) {
          await this.browserActClient.closeSession(existing.sessionId).catch(() => {});
          this.active.delete(account.id);
          this.controlled.delete(account.id);
          throw error;
        }
      }));
      const failures = settled.filter(result => result.status === "rejected");
      if (failures.length) {
        this.lastError = failures.map(result => result.reason?.message || "启动失败").join("；");
        throw new Error(this.lastError);
      }
      const snapshotPool = { ...pool, poolSize: this.occupiedCount() };
      this.onEvent(`已按指令启动 ${selected.length} 个账号，当前共 ${this.occupiedCount()} 个账号环境`, "success");
      return this.snapshot(snapshotPool);
    } finally {
      this.starting = false;
    }
  }

  findActive(selector) {
    const target = normalizeText(selector);
    if (!target) throw new Error("关闭指令必须明确包含账号邮箱、名称、ID 或 sessionId");
    const matches = [...this.active.values(), ...this.controlled.values()].filter(session => [
      session.accountId, session.label, session.email, session.sessionId, ...(session.aliases || [])
    ].some(value => normalizeText(value) === target));
    if (matches.length === 0) throw new Error(`当前活跃席位中没有找到：${selector}`);
    if (matches.length > 1) throw new Error(`关闭目标不唯一，请使用邮箱或 sessionId：${selector}`);
    return matches[0];
  }

  interactiveSessions(selector = "", allActiveAccounts = false, selectors = []) {
    const sessions = [...this.active.values(), ...this.controlled.values()];
    if (!sessions.length) throw new Error("当前没有可执行页面任务的活跃 Google 账号");
    const requested = [
      ...(Array.isArray(selectors) ? selectors : []),
      ...(selector ? [selector] : [])
    ].map(normalizeText).filter(Boolean)
      .filter((value, index, items) => items.indexOf(value) === index);
    if (!requested.length) return allActiveAccounts ? sessions : sessions;
    const selected = [];
    for (const target of requested) {
      const matches = sessions.filter(session => [
        session.accountId, session.label, session.email, session.sessionId, ...(session.aliases || [])
      ].some(value => normalizeText(value) === target));
      if (matches.length === 0) throw new Error(`当前活跃账号中没有找到：${target}`);
      if (matches.length > 1) throw new Error(`页面任务目标不唯一，请使用邮箱或 sessionId：${target}`);
      if (!selected.some(session => session.accountId === matches[0].accountId)) selected.push(matches[0]);
    }
    return selected;
  }

  async closeAndBackfill(selector, config, browsers = []) {
    const session = this.findActive(selector);
    const sessionIds = Array.isArray(session.sessionIds) && session.sessionIds.length
      ? session.sessionIds : [session.sessionId];
    await Promise.allSettled(sessionIds.map(sessionId => this.browserActClient.closeSession(sessionId)));
    this.active.delete(session.accountId);
    this.controlled.delete(session.accountId);
    this.rotation = this.rotation.filter(id => id !== session.accountId).concat(session.accountId);
    this.onEvent(`收到明确关闭指令：${session.email}`, "warning");
    return { closed: session, replacement: null, pool: this.snapshot(config) };
  }

  async releaseAccount(accountId) {
    const id = String(accountId || "");
    const active = this.active.get(id);
    const controlled = this.controlled.get(id);
    const assist = this.loginAssist.get(id);
    await Promise.allSettled([
      active ? this.browserActClient.closeSession(active.sessionId) : Promise.resolve(),
      ...(controlled?.sessionIds || (controlled ? [controlled.sessionId] : []))
        .map(sessionId => this.browserActClient.closeSession(sessionId)),
      assist ? this.browserActClient.closeSession(assist.sessionId) : Promise.resolve()
    ]);
    this.active.delete(id);
    this.controlled.delete(id);
    this.loginAssist.delete(id);
    this.authRequired.delete(id);
  }

  async handleInstruction(instruction, config, browsers = []) {
    const text = String(instruction || "").trim();
    if (!/(关闭|关掉|退出|close)/i.test(text)) {
      throw new Error("账号池只接受包含“关闭/close”的明确关闭指令");
    }
    const sessions = [...this.active.values(), ...this.controlled.values()];
    const matches = sessions.filter(session => [
      session.accountId, session.label, session.email, session.sessionId, ...(session.aliases || [])
    ].some(value => value && normalizeText(text).includes(normalizeText(value))));
    if (matches.length !== 1) {
      throw new Error(matches.length ? "关闭指令匹配了多个账号，请明确邮箱" : "关闭指令没有匹配当前活跃账号");
    }
    return this.closeAndBackfill(matches[0].sessionId, config, browsers);
  }

  prepareRecovery(accountId, config, profiles = []) {
    const pool = normalizePoolConfig(config);
    const account = this.configuredAccounts(pool).find(item => item.id === String(accountId || ""));
    if (!account) throw new Error(`没有找到待恢复账号：${accountId}`);
    if (!account.profileId) throw new Error(`${account.email} 尚未绑定本机 Chrome Profile`);
    const profile = profiles.find(item => item.id === account.profileId);
    if (profiles.length && !profile) throw new Error(`本机 Chrome Profile 不存在：${account.profileId}`);
    return {
      accountId: account.id,
      email: account.email,
      browserId: account.browserId,
      profileId: account.profileId,
      profileName: profile?.name || account.profileId,
      warning: "这会把选中的本机 Chrome Profile 复用到目标 BrowserAct 环境并重新验证；如果 Profile 本身仍有效，通常不需要再次输入密码。导入可能覆盖目标环境状态或关闭正在运行的窗口，请先保存工作。"
    };
  }

  async recoverAccount(accountId, config, browsers = [], sessions = []) {
    const { pool, accounts } = this.validate(config, browsers);
    const account = accounts.find(item => item.id === String(accountId || ""));
    if (!account) throw new Error(`没有找到待恢复账号：${accountId}`);
    if (!account.profileId) throw new Error(`${account.email} 尚未绑定本机 Chrome Profile`);
    if (this.recovering.has(account.id)) throw new Error(`${account.email} 正在恢复登录`);
    this.recovering.add(account.id);
    try {
      const active = this.active.get(account.id);
      if (active) {
        await this.browserActClient.closeSession(active.sessionId).catch(() => {});
        this.active.delete(account.id);
      }
      const controlled = this.controlled.get(account.id);
      if (controlled) {
        await Promise.allSettled((controlled.sessionIds || [controlled.sessionId]).map(sessionId =>
          this.browserActClient.closeSession(sessionId)
        ));
        this.controlled.delete(account.id);
      }
      const prefix = `heyi_pool_${account.id}_`;
      const ownedSessions = sessions.filter(session =>
        session.browserId === account.browserId && session.sessionId.startsWith(prefix)
      );
      await Promise.allSettled(ownedSessions.map(session =>
        this.browserActClient.closeSession(session.sessionId)
      ));
      await this.browserActClient.importProfile(account.browserId, account.profileId);
      this.authRequired.delete(account.id);
      let replacement = null;
      if (this.occupiedCount() < pool.poolSize) replacement = await this.openAccount(account, pool);
      await this.fill(pool, browsers);
      if (!this.authRequired.size) this.lastError = null;
      this.onEvent(`${account.email} 已重新导入 Profile 并通过登录验证`, "success");
      return { account, replacement, pool: this.snapshot(pool) };
    } catch (error) {
      this.recordAuthRequired(account, { reason: `恢复失败：${error.message}` });
      this.lastError = `${account.email} 恢复失败：${error.message}`;
      throw error;
    } finally {
      this.recovering.delete(account.id);
    }
  }

  async healthCheck(config, browsers = []) {
    if (this.healthChecking || this.starting || this.active.size === 0) return this.snapshot(config);
    this.healthChecking = true;
    try {
      const { pool, accounts } = this.validate(config, browsers);
      const checks = await Promise.allSettled([...this.active.values()].map(async session => {
        const account = accounts.find(item => item.id === session.accountId);
        if (!account) return;
        let health;
        try {
          health = await this.browserActClient.checkGoogleSessionHealth(session.sessionId);
        } catch (error) {
          // A temporary state-read failure must not open an account page or
          // evict a healthy account. Keep it active for the next check.
          session.lastHealthError = error?.message || "Session health check failed";
          return;
        }
        if (health.ok) {
          session.verifiedAt = health.checkedAt;
          session.status = "VERIFIED";
          session.lastHealthError = null;
          return;
        }
        await this.browserActClient.closeSession(session.sessionId).catch(() => {});
        this.active.delete(account.id);
        this.recordAuthRequired(account, health);
      }));
      const failures = checks.filter(result => result.status === "rejected");
      if (failures.length) {
        this.lastError = failures.map(result => result.reason?.message || "登录巡检失败").join("；");
      }
      // Health checks only observe and evict invalid sessions. The local
      // helper must never start replacement accounts on its own; the
      // platform Agent owns the exact target account set for every task.
      this.lastHealthCheckAt = new Date().toISOString();
      return this.snapshot(pool);
    } finally {
      this.healthChecking = false;
    }
  }

  async startLoginAssist(accountId, config, browsers = []) {
    const { pool, accounts } = this.validate(config, browsers);
    const account = accounts.find(item => item.id === String(accountId || ""));
    if (!account) throw new Error(`没有找到待登录账号：${accountId}`);
    const existing = this.loginAssist.get(account.id);
    if (existing) return existing;
    const nonce = crypto.randomBytes(3).toString("hex");
    const sessionId = `heyi_login_${account.id}_${Date.now().toString(36)}_${nonce}`.slice(0, 80);
    await this.browserActClient.ensureSession({
      browserId: account.browserId,
      sessionId,
      headed: false,
      allowedDomains: []
    }, pool.healthCheckUrl);
    try {
      const assist = await this.browserActClient.remoteAssist(
        sessionId,
        `登录并确认 Google 账号 ${account.email}，用于赫逸自动化助手账号池`
      );
      const result = {
        accountId: account.id,
        email: account.email,
        sessionId,
        url: assist.url,
        startedAt: new Date().toISOString()
      };
      this.loginAssist.set(account.id, result);
      this.onEvent(`${account.email} 的安全登录窗口已打开`, "warning");
      return result;
    } catch (error) {
      await this.browserActClient.closeSession(sessionId).catch(() => {});
      throw error;
    }
  }

  async finishLoginAssist(accountId, config, browsers = []) {
    const { pool, accounts } = this.validate(config, browsers);
    const account = accounts.find(item => item.id === String(accountId || ""));
    if (!account) throw new Error(`没有找到待登录账号：${accountId}`);
    const assist = this.loginAssist.get(account.id);
    if (!assist) throw new Error(`${account.email} 没有等待确认的登录窗口`);
    await this.browserActClient.navigateSession(assist.sessionId, pool.healthCheckUrl);
    const verification = await this.browserActClient.verifyGoogleAccount(assist.sessionId, account.email);
    if (!verification.ok) throw new Error(`${account.email} 尚未完成登录：${verification.reason}`);
    await this.browserActClient.closeSession(assist.sessionId).catch(() => {});
    this.loginAssist.delete(account.id);
    this.authRequired.delete(account.id);
    let replacement = null;
    if (this.occupiedCount() < pool.poolSize) replacement = await this.openAccount(account, pool);
    await this.fill(pool, browsers);
    if (!this.authRequired.size) this.lastError = null;
    this.onEvent(`${account.email} 已完成独立环境登录并重新加入账号池`, "success");
    return { account, replacement, pool: this.snapshot(pool) };
  }

  snapshot(config = {}) {
    const pool = normalizePoolConfig(config);
    const accounts = this.configuredAccounts(pool);
    this.syncRotation(accounts);
    const activeIds = new Set([...this.active.keys(), ...this.controlled.keys()]);
    const authIds = new Set(this.authRequired.keys());
    const authRequired = [...this.authRequired.values()]
      .filter(item => accounts.some(account => account.id === item.accountId))
      .map(item => ({
        ...item,
        recovering: this.recovering.has(item.accountId),
        loginAssistActive: this.loginAssist.has(item.accountId)
      }));
    const localAccounts = [];
    for (const account of pool.accounts.filter(item => item.email && item.browserId)) {
      const active = this.active.get(account.id);
      const controlled = this.controlled.get(account.id);
      const auth = this.authRequired.get(account.id);
      const localProfile = this.localProfiles.find(profile =>
        (account.profileId && profile.profileId === account.profileId)
        || profile.email === account.email
      );
      const sourceCookieUpdatedAtMs = Math.max(
        Number(account.sourceCookieUpdatedAtMs) || 0,
        Number(localProfile?.cookieUpdatedAtMs) || 0
      );
      const sourceChanged = Boolean(
        localProfile
        && account.lastImportedCookieUpdatedAtMs > 0
        && sourceCookieUpdatedAtMs > account.lastImportedCookieUpdatedAtMs + 1000
      );
      const syncRequired = Boolean(localProfile && (auth || sourceChanged));
      const loginRequired = Boolean(auth && !localProfile);
      localAccounts.push({
        profileName: account.label || account.email,
        browserName: "BrowserAct",
        email: account.email,
        accountId: account.id,
        browserId: account.browserId || null,
        sessionId: active?.sessionId || controlled?.sessionId || null,
        controlled: Boolean(controlled),
        controlledSessionCount: controlled?.sessionIds?.length || 0,
        currentUrl: controlled?.startUrl || active?.startUrl || "",
        loginAssistActive: this.loginAssist.has(account.id),
        status: syncRequired
          ? "SYNC_REQUIRED"
          : loginRequired
            ? "LOGIN_REQUIRED"
            : active
              ? "RUNNING"
              : controlled
                ? "CONTROLLED"
                : "READY",
        reason: auth?.reason || (sourceChanged ? "本机 Chrome 登录态已更新" : null),
        profileId: account.profileId || localProfile?.profileId || null,
        profileName: account.profileName || localProfile?.profileName || account.label || account.email,
        profileDirectory: account.profileDirectory || "",
        sourceCookieUpdatedAtMs,
        lastImportedCookieUpdatedAtMs: account.lastImportedCookieUpdatedAtMs || 0,
        lastVerifiedAt: account.lastVerifiedAt || "",
        sourceChanged,
        syncRequired,
        loginRequired
      });
    }
    return {
      enabled: pool.enabled,
      autoStart: pool.autoStart,
      poolSize: pool.poolSize,
      startUrl: pool.startUrl,
      healthCheckUrl: pool.healthCheckUrl,
      starting: this.starting,
      healthChecking: this.healthChecking,
      lastHealthCheckAt: this.lastHealthCheckAt,
      status: this.starting
        ? "STARTING"
        : authRequired.length && this.occupiedCount() < pool.poolSize
          ? "AUTH_REQUIRED"
          : this.occupiedCount() ? "RUNNING" : "STOPPED",
      activeCount: this.occupiedCount(),
      controlledCount: this.controlled.size,
      loginAssistCount: this.loginAssist.size,
      configuredCount: accounts.length,
      authRequiredCount: authRequired.length,
      discoveredCount: 0,
      discoveredAccounts: [],
      localAccounts,
      active: [...this.active.values(), ...this.controlled.values()],
      authRequired,
      waiting: this.rotation
        .filter(id => !activeIds.has(id) && !authIds.has(id))
        .map(id => {
          const account = accounts.find(item => item.id === id);
          return account ? { id: account.id, label: account.label, email: account.email } : null;
        }).filter(Boolean),
      lastError: this.lastError
    };
  }

  async shutdown() {
    const sessions = [...this.active.values()];
    const assistSessions = [...this.loginAssist.values()];
    await Promise.allSettled([
      ...sessions.map(session => this.browserActClient.closeSession(session.sessionId)),
      ...assistSessions.map(session => this.browserActClient.closeSession(session.sessionId))
    ]);
    this.active.clear();
    this.loginAssist.clear();
  }
}

module.exports = { AccountPoolManager, normalizePoolConfig };
