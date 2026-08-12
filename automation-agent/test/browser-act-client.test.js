const test = require("node:test");
const assert = require("node:assert/strict");
const { BrowserActClient } = require("../src/core/browser-act-client");

test("登录完成后能从 Google 账号页识别邮箱", async () => {
  const client = new BrowserActClient();
  client.runSession = async () => ({ ok: true });
  client.state = async () => ({
    url: "https://myaccount.google.com/",
    title: "Google 账号",
    text: "Google 账号 user@example.com 退出"
  });
  const result = await client.detectGoogleAccount("heyi_test_session");
  assert.equal(result.ok, true);
  assert.equal(result.email, "user@example.com");
});

test("登录页不会被误判为已添加账号", async () => {
  const client = new BrowserActClient();
  client.runSession = async () => ({ ok: true });
  client.state = async () => ({
    url: "https://accounts.google.com/signin",
    title: "登录",
    text: "使用 Google 账号登录 user@example.com"
  });
  const result = await client.detectGoogleAccount("heyi_test_session");
  assert.equal(result.ok, false);
  assert.equal(result.email, "");
});

test("存在多个邮箱时只识别账号按钮中的当前主账号", async () => {
  const client = new BrowserActClient();
  client.runSession = async () => ({ ok: true });
  client.state = async () => ({
    url: "https://myaccount.google.com/",
    title: "Google 账号",
    text: "aria-label=Google 账号： 用户A\n(active@example.com) role=button 备用账号 other@example.com"
  });
  const detected = await client.detectGoogleAccount("heyi_test_session");
  assert.equal(detected.email, "active@example.com");
  const verification = await client.verifyGoogleAccount("heyi_test_session", "other@example.com");
  assert.equal(verification.ok, false);
  assert.equal(verification.activeEmail, "active@example.com");
  assert.match(verification.reason, /当前主账号是 active@example.com/);
});

test("删除账号环境会传入明确的 BrowserAct 删除用途", async () => {
  const client = new BrowserActClient();
  let captured;
  client.run = async (args) => {
    captured = args;
    return { stdout: JSON.stringify({ ok: true }) };
  };
  const result = await client.deleteBrowser(
    "chrome_local_test_1",
    "从赫逸助手删除不再使用的 Google 账号环境"
  );
  assert.equal(result.ok, true);
  assert.equal(result.browserId, "chrome_local_test_1");
  assert.deepEqual(captured, [
    "--format", "json", "browser", "delete", "chrome_local_test_1",
    "--objective", "从赫逸助手删除不再使用的 Google 账号环境"
  ]);
});

test("只返回已识别 Google 邮箱的本机 Chrome Profile", async () => {
  const client = new BrowserActClient();
  client.run = async () => ({
    stdout: JSON.stringify({
      local_profiles: [
        {
          profile_id: "local_profile_1",
          profile_name: "用户1",
          account_email: "User@Example.com",
          browser_name: "Chrome",
          source_type: "local",
          path: "C:\\Chrome\\Profile 1"
        },
        {
          profile_id: "local_profile_2",
          profile_name: "未登录",
          account_email: null,
          browser_name: "Chrome",
          source_type: "local"
        }
      ]
    })
  });
  const profiles = await client.listProfiles();
  assert.deepEqual(profiles, [{
    id: "local_profile_1",
    name: "用户1",
    accountEmail: "user@example.com",
    browserName: "Chrome",
    sourceType: "local",
    path: "C:\\Chrome\\Profile 1"
  }]);
});

test("导入时把 Chrome 共用目录占用错误转换为简洁提示", async () => {
  const client = new BrowserActClient();
  client.run = async () => {
    throw new Error(JSON.stringify({
      ok: false,
      error: "Other Chrome profiles are running. Detected active profiles: Default, Profile 3. Conflicting profile paths: C:\\Chrome\\Default.",
      error_code: 230205,
      error_name: "REGISTRY_BROWSER_RUNNING"
    }));
  };
  await assert.rejects(
    () => client.importProfile("chrome_local_test", "local_profile_test"),
    /普通 Chrome 仍有账号窗口在运行（Default、Profile 3）。请关闭所有普通 Chrome 窗口后重试导入。/
  );
});

test("网页授权只返回安全链接，不暴露 API Key", async () => {
  const client = new BrowserActClient();
  client.run = async args => {
    assert.deepEqual(args, ["--format", "json", "auth", "login"]);
    return {
      stdout: JSON.stringify({
        ok: true,
        quick_url: "https://www.browseract.com/cli/auth/example",
        remaining_minutes: 10,
        api_key: "must-not-be-returned"
      })
    };
  };
  const result = await client.startWebAuthorization();
  assert.deepEqual(result, {
    ok: true,
    url: "https://www.browseract.com/cli/auth/example",
    remainingMinutes: 10
  });
  assert.equal("apiKey" in result, false);
});

test("网页授权状态轮询会标准化结果", async () => {
  const client = new BrowserActClient();
  client.run = async args => {
    assert.deepEqual(args, ["--format", "json", "auth", "poll"]);
    return {
      stdout: JSON.stringify({
        ok: true,
        status: "SUCCESS",
        message: "Authorization completed",
        api_key: "must-not-be-returned"
      })
    };
  };
  const result = await client.pollWebAuthorization();
  assert.equal(result.status, "success");
  assert.equal("apiKey" in result, false);
});

test("Pi Agent 页面动作完整映射到 BrowserAct 并在动作后重新读取状态", async () => {
  const client = new BrowserActClient();
  const calls = [];
  client.ensureSession = async () => "heyi_agent_test";
  client.runSessionJson = async (_session, args) => {
    calls.push(args);
    return { ok: true };
  };
  client.state = async () => ({
    url: "https://example.com/after",
    title: "完成页",
    text: "操作完成"
  });

  await client.executeBrowserAction({ type: "SELECT", elementRef: "12", option: "视频" }, {});
  await client.executeBrowserAction({ type: "KEYS", keys: "Enter" }, {});
  await client.executeBrowserAction({ type: "HOVER", elementRef: "8" }, {});
  await client.executeBrowserAction({ type: "SCROLL", direction: "down", amount: 900 }, {});
  await client.executeBrowserAction({ type: "BACK" }, {});
  await client.executeBrowserAction({ type: "GET_TEXT", elementRef: "3" }, {});
  await client.executeBrowserAction({ type: "GET_HTML" }, {});
  await client.executeBrowserAction({
    type: "NETWORK_REQUESTS",
    filter: "api/videos",
    resourceTypes: "xhr,fetch",
    method: "get",
    status: "2xx"
  }, {});
  await client.executeBrowserAction({ type: "NETWORK_REQUEST", requestId: "req:123" }, {});
  await client.executeBrowserAction({ type: "SCREENSHOT", full: true }, {});

  assert.deepEqual(calls, [
    ["select", "12", "视频"],
    ["keys", "Enter"],
    ["hover", "8"],
    ["scroll", "down", "--amount", "900"],
    ["back"],
    ["get", "text", "3"],
    ["get", "html"],
    ["network", "requests", "--filter", "api/videos", "--type", "xhr,fetch", "--method", "GET", "--status", "2xx"],
    ["network", "request", "req:123"],
    ["screenshot", "--full"]
  ]);
});
