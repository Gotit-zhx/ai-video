# 赫逸招聘助手

Mac（Apple Silicon）和 Windows x64 共用的招聘客户端。使用 Electron 内置 Chromium，
运行经过固定版本校验的 boss-agent-cli 2.0.0，不需要 HR 安装 Python、命令行或浏览器插件。

## 使用

1. 安装并打开客户端。启动时不会打开 BOSS，也不会自动登录、搜索或发消息。
2. 打开 `https://hoyee.org.cn/?lang=zh#hr-overview`，登录获授权的赫逸管理员账号。
3. 点击“连接本机助手”。浏览器提示本地网络权限时允许连接。
4. 点击扫码连接；官方 BOSS 登录页在客户端打开，网页可以显示同一张二维码。
5. 招聘工作仍在原网页中完成；也可以使用应用里的“招聘工作台”。

只开放原系统已有的职位、投递、推荐、候选人、会话、在线简历、索取与接收附件功能。
不会因为登录而发送招呼。索取需要用户确认；发送结果不明时保留记录，不自动重发。
遇到平台验证/账号限制立即暂停，不尝试绕过。客户端不保证或承诺免于平台限制。

换电脑需安装对应版本并重新扫码，浏览器登录资料不会在 Mac 和 Windows 之间复制。
平台原有业务资料仍在服务器。本版新增的简历索取追踪保存在执行电脑，
只在同一浏览器会话下恢复；新登录或换电脑不会自动迁移旧会话的待接收队列。
这些历史记录保留在原电脑，切换前应先核对未完成的索取。

关闭窗口会驻留菜单栏/托盘；退出会结束运行时。休眠、重启、连接失效后保持暂停，
由本人恢复。未启动客户端或客户端失联时，不会退回服务器执行 BOSS 请求。
“断开本机连接”只清理本应用的会话，不调用 BOSS 全局退出。

## 连接边界

- 网页只调用 `127.0.0.1:17861`，严格校验 Host、Origin、随机本机会话凭证。
- 服务器签发 60 秒、只能使用一次的配对凭证；本机向赫逸兑换身份。
- 同一用户只有一台设备持有有效执行凭证；每 15 秒以内重新校验，失效停止新任务。
- 有效连接目前为 30 分钟，到期需要重新连接助手。后台不会自动抢回另一台设备。
- 内置赫逸页面与 BOSS 浏览器独立持久化，远程页面无 Node/preload 权限。
- CDP 仅监听本机随机端口。BOSS Cookie 经 stdin 交给本地子进程，不写入日志或上传。
- 实际请求仍受原来的互斥、预算、账号限制保护；使用经过哈希审核的 SDK，不自动升级 SDK。

## 网卡直连

默认选择有默认网关的实际有线或 Wi-Fi 网卡。Mac 使用 `IP_BOUND_IF`，Windows 使用
`IP_UNICAST_IF`，同时绑定该网卡的本地 IPv4 地址；DNS 查询也绑定相同网卡。
不采用系统代理、环境代理或虚拟网卡的默认路由，不更改其他软件的网络设置。

内置浏览器通过仅监听 loopback、有随机凭证的本机转发端口连接同一网卡；
HTTPS 的 TLS 字节原样传递，不解密。CLI 接口和附件下载绑定同一网卡。
QUIC 和非代理 WebRTC UDP 已关闭，避免旁路流量。绑定/DNS/连接失败时停止，
不会切换到系统代理。没有 IPv4 实际网卡的网络暂不支持；换网卡后重新启动助手。
这解决本应用的出口选择，不改变 BOSS 官方的账号或 PC 登录限制。

## 构建

每个目标系统分别构建 Python 运行时，不交叉打包另一系统的可执行文件。

```sh
# 在 hr-desktop 目录，Node 22+、pnpm 11.25、Python 3.12
pnpm install --frozen-lockfile
node node_modules/electron/install.js
python -m venv runtime/.venv
# Mac:
runtime/.venv/bin/python runtime/install.py
runtime/.venv/bin/python runtime/build.py
# Windows:
runtime\\.venv\\Scripts\\python runtime\\install.py
runtime\\.venv\\Scripts\\python runtime\\build.py
pnpm test
pnpm run dist:mac
# Windows 使用 pnpm run dist:win
```

构建时从仓库 `scripts/boss-agent` 提取同一份连接规则，只调整 Windows 文件锁、
未使用的 Unix 服务类以及本机 CDP 端口。上游许可在 `vendor/BOSS-AGENT-CLI-LICENSE.txt`。

`--self-test` 必须指定临时目录 `HEYI_TEST_USER_DATA`，阻断外网并使用合成 Cookie，
验证嵌入浏览器、会话隔离、运行时完整性、CDP、配对、直连路由和实际窗口渲染，不访问 BOSS。
GitHub Actions 同时生成 Mac/Windows 安装包和测试记录。

当前打包不含 Apple Developer ID 公证或 Windows 发布者证书。
对外分发前可配置组织的签名证书；现有版本可能出现操作系统的未知发布者提示。

## 线上更新

只更新招聘前端、连接服务及 CSP 中本机端口白名单。连接服务设置
`APP_HR_DESKTOP_ONLY=1` 后拒绝服务器招聘执行；原账号浏览器和暂停记录保留。
无需重启商品服务、数据库或服务器 Chrome，也不更改 SSH、代理或网卡配置。
