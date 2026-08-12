# 赫逸自动化助手

独立安装在运营人员电脑上的本机执行程序，用于连接赫逸智能化平台与飞跨浏览器。

当前 MVP 已包含：

- Windows/macOS 共用的 Electron 桌面程序骨架
- 平台健康检查
- 飞跨本地 WebDriver API 检测
- 飞跨店铺启动、动态 CDP 端口校验和精确停止适配器
- 平台心跳、任务领取、执行状态与结果回传
- `FEIKUA_INSPECT` 飞跨环境只读检查命令
- `FEIKUA_OPEN_STORE` 按完整店铺名称精确启动并连接动态 CDP
- 本机设备信息与接单开关
- 本地配置持久化
- 系统托盘与日志目录
- 安全的 Renderer/Main 进程隔离
- Google 五席账号池：一次启动 5 个不同账号的 BrowserAct 窗口
- 明确关闭账号后从等待队列自动补位，并将已关闭账号排到队尾
- Windows NSIS 和 macOS DMG 打包配置

当前联调版本允许平台检查飞跨，以及按完整店铺名称启动对应的隔离浏览器。店铺名称
必须精确匹配，避免误开店铺。页面点击、发布、删除与付款尚未加入命令白名单，
高风险动作应在桌面端二次确认。

本地开发默认连接 `http://localhost:8080`，Agent Token 可通过环境变量覆盖：

```powershell
$env:HEYI_AGENT_TOKEN = "your-agent-token"
npm start
```

## 本地开发

```powershell
cd automation-agent
npm install
npm start
```

调试渲染界面：

```powershell
npm run start:debug
```

## Google 五席账号池

在“设置 → Google 账号池”中录入至少 5 个账号。每个账号必须绑定不同的 BrowserAct `chrome` 环境；这些环境应分别导入对应 Google 账号的 Chrome Profile 登录状态。

配置完成后，可在主界面点击“启动五席”，或由平台发送：

- `GOOGLE_ACCOUNT_POOL_START`：启动或补齐账号池。
- `GOOGLE_ACCOUNT_CLOSE`：在 `input.email`、`input.accountId`、`input.accountName` 或 `input.sessionId` 中明确指定关闭目标，关闭后自动补位。
- `GOOGLE_ACCOUNT_COMMAND`：在 `input.instruction` 中发送如“关闭 user@example.com”的明确指令。没有“关闭/close”或目标不唯一时会拒绝执行。

账号池只关闭本应用创建的 BrowserAct session，不会删除 BrowserAct 浏览器环境或登录资料。

每个邮箱会长期保存其本机 Chrome Profile、Cookie 文件更新时间、上次导入时间和最后验证时间。启动任务前先在 `myaccount.google.com` 核验当前邮箱；如果 BrowserAct 登录失效，或本机 Cookie 在上次导入后发生更新，账号卡片只显示“同步并启动”。用户确认后，助手会关闭普通 Chrome、完整导入对应 Profile、再次核验邮箱，再打开账号池起始页面（默认 Gemini）。本机 Profile 本身也未登录时，助手会打开普通 Chrome 的对应 Profile，由用户完成密码、验证码或二次验证。

## Windows 安装包

```powershell
npm run dist:win
```

生成文件位于 `automation-agent/release`。macOS 安装包需要在 macOS 构建机上执行：

```bash
npm run dist:mac
```

正式发布前需要配置 Windows 代码签名证书和 Apple Developer ID、公证流程。
