# Windows 职位中文与旧助手授权提示

用户账号 `15859214448` 的生产授权只读核验：ACTIVE、HR_DEPARTMENT、SELF、hr:access。未重新登录或撤销用户会话。完整“请先登录有招聘权限的赫逸管理员账号”文字来自旧版 0.1.15 桌面 `checkOwner`，当前权限校验已允许普通 HR；不通过授予管理员身份兼容旧客户端。

Python 原输出 `sys.stdout.write(json.dumps(result, ensure_ascii=False))` 依赖系统 stdout 编码，Electron 固定按 UTF-8 解码。冻结运行时不能仅依赖 PYTHONUTF8 / PYTHONIOENCODING 环境设置保证一致。现在通过二进制 stdout 写 ASCII 转义 JSON，接收端 JSON.parse 自动还原中文、标点、emoji；职位读取、状态约束及分页逻辑保持原样。不尝试猜测已经损坏的字符串或覆盖职位归属。

网页 health 检查最低助手版本 0.1.17。旧版或版本未知时在配对前给出覆盖安装和完全退出旧进程的提示，停止后台重连，也阻止已缓存配对继续调用业务。正常新版本仍可连接；BOSS 登录不清理。

验证：186 项桌面及 HR 网页检查、105 项运行时检查通过。新增用实际职位列表及两页数据测试 cp936、cp1252、UTF-8 stdout 编码，岗位名称、学历、年限、地址、状态和 emoji 完整往返。新增整包测试通过真实冻结 Python 子进程读取本地中文职位状态；整包测试身份改为非管理员 HR，并验证移除 HR 权限后被拒绝。测试全部离线、临时目录，不读取真实 BOSS 登录或发送消息。

桌面版本更新为 0.1.17；发布前必须完成 Windows 原生 CI 整包和 Mac 整包验证，核验公开下载 SHA256，保留回滚包。具体用户电脑尚无乱码样本，故不宣称已在其 Windows 现场验收。

构建提交 `dfdd56c4776c5933751a5dcfcfd6b5e5f63c9b57`。Windows 原生任务 `37918167402` 成功，Mac 严格签名校验成功；两端整包各 20 项检查通过。GitHub 草稿发布 `hr-desktop-v0.1.17-dfdd56c4` 保留已验收安装包。

| 包 | 字节数 | SHA256 |
| --- | ---: | --- |
| Windows x64 EXE | 160636530 | `70b82946f7c4b81f3a0218b2b0ce05abc7547d73bcb37dd54478c3fbec6d56b3` |
| Mac arm64 DMG | 192724665 | `17d56ca62fa7b6419440c7632b1f00c9fd31faca29a365fb4a3fbe3455207cda` |

发布目录 `/opt/heyi/releases/hr-windows-unicode-017-20261009`。SSH 整包上传及服务器直拉 GitHub 遇到网络停滞，改用私有文件副本作为 rsync 差异传输基础，不改写当前公开包。Windows 只传输约 4.3 MB 数据，重建完整包的 SHA256 与 CI 完全一致。临时 OSS 中转尝试已停止，两个私有 staging 对象及本地限时签名文件已删除；认证凭证不保存至发布目录。

Windows 下载先行切换至 0.1.17，公开响应大小及 nginx 挂载 SHA256 验证通过，保留 0.1.16 于 `windows-early-rollback`，不重启服务。

随后 Mac 差异传输完成，重建 SHA256 与本地已验收包一致。网页和两端安装包已协调发布，记录于 `activation.json` / `installers-activation.json`；两个下载入口的版本参数和提示更新为 0.1.17。网页静态文件公开哈希、下载响应大小及 nginx 挂载文件哈希均验收。Java、Pi、BOSS 及其他服务实例保留，仅更新前端容器。失败下载碎片、临时传输包及发布 artifact 的多余安装包链接已删除，保留当前公开包和前一版本回滚。

用户需完全退出旧助手，再覆盖安装新包并刷新网页。实际运行旧进程时，新网页直接显示升级提示，不再申请配对后撞到旧版管理员校验。登录目录不清理；本轮未在用户实际 Windows 电脑执行操作。
