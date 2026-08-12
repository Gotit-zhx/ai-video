# Chrome Profile 管理器

一个只在本机运行的 Windows Electron 小工具，用来管理你有权限使用的 Chrome Profile。

当前版本支持：

- 读取 Chrome `User Data` 下的 `Default`、`Profile 1` 等 Profile 目录。
- 读取 Chrome 的本地 Profile 摘要，显示 Profile 名称和脱敏后的账号摘要。
- 一键打开指定 Profile，并访问设置好的网址。
- 给 Profile 设置管理名称和备注。
- 手动记录使用次数、查看最近打开时间、清空记录。
- 更换 Chrome 数据目录。

工具不会读取或保存 Google 密码、Cookie、验证码，也不会自动登录、自动登出、自动轮换账号或执行网页操作。Profile 的登录状态仍由 Chrome 自己维护。

## 启动

在项目根目录执行：

```powershell
cd chrome-profile-manager
.\start.ps1
```

如果项目内没有现成的 Electron 运行时，脚本会在本目录执行 `npm install`，然后启动应用。

默认扫描目录：

```text
%LOCALAPPDATA%\Google\Chrome\User Data
```

如果你使用了其他 Chrome 安装目录，可以在界面中点击“更换目录”。
