# 源码运行现场状态与验收交接

现场复核日期为 2026-11-23（北京时间）。共享服务器主 Java 应用和 Web 前端使用源码运行。Pi Runtime、招聘、ERP、文档转换、水印、下载和桌面程序保留各自的运行与验收入口。

## 已生效的运行方式

| 项目 | 现场状态 |
| --- | --- |
| 源码根 | `/opt/heyi/dev/ai-video`；编辑前仍须核对其他会话的未提交差异 |
| 工具链 | JDK `21.0.9+10`、Maven `3.9.11` |
| 后端 | 实际 Java 进程使用 `target/classes` 与 DevTools，不通过 `-jar` 启动；Maven `-Pdev spring-boot:run`，启动阶段使用 `-Dmaven.test.skip=true -Dmaven.main.skip=true -Dmaven.resources.skip=true`；watcher 单独编译到 `target/classes`，本次生成 JAR 数为 0 |
| 服务器前端 | 既有 Nginx 直接 bind 源码 `frontend/`，保留 TLS、登录与 API/独立服务代理；不是由 Spring 替换现有前端代理链 |
| 本地前端 | dev profile 的 Spring 直接读取本地 `frontend/`，API/UI 同源 |
| Compose | 保留 base → production → `.2images` → `source-development.frontend.json` → `source-development.app.json` 完整叠加链；精确路径以受限 runtime 配置及现场回执为准，不只运行最后一份覆盖文件 |
| watcher | `heyi-source-watch.service` 已 enabled、active，最终状态 ready、健康且无遗留 gate；配置 `/etc/heyi-dev/runtime.json` |
| 数据库 | 11 月 23 日两次生效回执均记录最高已应用版本 V187。V177–V187 于 11 月 21 日显式追加，迁移前历史校验通过。本轮归档了 V166–V187，逐条与生产 Flyway 成功记录核对。详见[三处仓库核对记录](../releases/infrastructure/source-sync-20261123.md) |
| 自动迁移 | 共享环境 Flyway 自动执行已禁用；migration 源码/hash/history 守卫阻止普通保存执行 DDL |

共享源码状态与中断恢复由 root 在源码根执行：

```bash
cd /opt/heyi/dev/ai-video
python3 scripts/shared_dev.py status --config /etc/heyi-dev/runtime.json
# 仅在确认本次中断及门控归属后恢复
python3 scripts/shared_dev.py recover --config /etc/heyi-dev/runtime.json
```

Java 保存仍执行维护锁、停止领取新工作、等待安全点、编译、上下文健康检查和本次门控恢复。`pom.xml` 依赖变化会受控重启 app；迁移、环境配置、JDK/Maven 及 watcher/运行工具本身的变化须显式维护和专项验证。

## 2026-11-23 现场复核

- `heyi-source-watch.service` 为 enabled、active。只读状态确认应用健康、最近一次编译成功、watcher 就绪、维护门控已释放。MySQL 备份 timer 为 enabled、active、`Persistent=false`。
- 财务广告月度范围和投影恢复的当日回执均记录生效成功。投影恢复的后续抽样观察到财务同步和投影继续推进，同时仍有待处理事件和需要广告授权的账户。样本时间及回执见[三处仓库核对记录](../releases/infrastructure/source-sync-20261123.md)。
- 服务器运行目录带有已生效和待核对的工作区增量。运行代码应按具体回执及文件摘要识别，`git rev-parse HEAD` 只提供已提交基线。

## 2026-11-11 源码切换验收样本

| 环境与样本 | 结果 |
| --- | --- |
| 共享服务器冷启动 | 11.68 秒 |
| 共享服务器 Java 保存到新上下文 | 24.262 秒；成功编译后生效 |
| 共享服务器失败编译 | 7.464 秒；原 context、编译输出和健康保持 |
| 本地前端保存到刷新 | 1.908 秒 |
| 本地 Java 热更新 | 9.492 秒 |
| 本地失败编译 | 2.851 秒，保留可用状态 |
| 服务器前端图形验收 | 原页面无需手动刷新，源码新增验证字样后自动显示，源码还原后自动消失；临时 Java/index 探针均已还原 |
| 会话与页面 | Redis 会话跨重载验证通过；图形验收全程保持 Admin 登录，广告分析页面及真实查询正常加载 |
| 数据保护 | 本次 schema/history 指纹保持，普通保存未执行 DDL；真实 DB、Redis、业务卷及既有配置继续保留 |

以上为单次真实样本，不代表长期 P50/P95。服务器原始小型回执位于 `/opt/heyi/dev/operations/source-runtime-20261111/verified.json` 和同目录 `hot-reload-verified.json`。不把私有配置、Cookie、密钥或完整业务数据复制进本文件。

## 日志、巡检和协作

- watcher 使用 `heyi-source` journal namespace，总量上限 64 MiB、保留 3 天；revision 路由 `access_log off`，避免浏览器轮询形成访问日志积压。日志继续脱敏。
- 旧自动自愈为 false，CD/release_flow 开关为 false；保留监控、备份和历史恢复资料。备份 unit `heyi-mysql-backup.timer` 已复核 enabled、active、`Persistent=false`；源码重启不触发漏跑补备份。
- GitHub `Development checks` 运行 `42961363055` 已成功，`develop` 的必要检查已改为新检查；`main-tree` 和旧标签保留历史。
- 默认分支已改为 `develop`，PR #56 的检查已全部通过；合并记录以该 PR 为准。检查覆盖源码运行和维护边界，不代表完整业务回归。当前仅在 PR 和 `develop` 更新时自动检查，避免同一功能提交重复触发；检查不阻止工作区保存生效。

后续接手仍需先核对现场维护、配置、挂载、源码差异与最新回执。日常操作边界见[运维指南](operations.md)，主机资产与备份见[服务器运维交接](server-handoff.md)。
