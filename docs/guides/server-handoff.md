# 阿里云共享服务器源码运行交接

核对日期：**2026-11-11（北京时间）**，服务器 `8.163.34.81`。主应用和前端已在 `/opt/heyi/dev/ai-video` 完成源码运行切换。实际入口、耗时、保护项和剩余验收见[源码运行现场状态](source-runtime-status.md)。

## 1. 接手时先处理什么

1. 通过公司交接渠道取得云控制台、SSH、Docker、域名/证书、备份和私有配置权限；不要在文档或日志记录密钥值。
2. 核对其他会话正在进行的维护、实际进程/容器、端口、挂载、数据库、Redis、备份 timer 和磁盘空间。
3. 核对 `/opt/heyi/dev/ai-video` 的 revision、未提交差异、所有者和写权限；不能用本地工作树覆盖服务器未知修改。
4. 读取[源码运行现场状态](source-runtime-status.md)及服务器原始回执，并复核当前状态是否有后续变化。

历史自动发布、版本和回滚资料见[旧发布工作流归档](../history/legacy-release-workflows/)与各期 `docs/releases/` 回执。

## 2. 主机与运行平台

历史核对主机为 Ubuntu 24.04.3 LTS、8 核、约 32 GiB 内存；Docker 29.1.3、Compose 2.40.3。实际版本可能变化，接手时重新读取。源码模式另要求 JDK 21、Maven 3.9.11，并记录实际可执行路径和 Maven 缓存归属。

服务器还运行 Docker/containerd、1Panel、桌面下载服务、备份/证书/巡检 timer 及其他独立服务。源码模式只替换主应用与前端的运行入口，不自动停止、重建或接管无关服务。

## 3. 当前服务与访问链路

- 外部 HTTPS、域名、TLS、登录、角色权限和 API 路由继续沿用现有已验收链路。
- 主应用在本机 `8080` 提供 API；服务器前端仍由 Nginx bind 规范源码根的 `frontend/`，保留外部 TLS 和既有代理链。本地开发才由 Spring 同源提供 API/UI。
- MySQL、Redis、Pi Runtime、招聘、ERP、文档转换、水印、下载和桌面服务保留各自边界。主应用健康不能证明这些链路全部通过。
- 广告官方 HTTPS 连接、私有代理/节点和真实账户授权继续按其专用配置与验收处理；源码切换不重置连接或凭据。

## 4. 源码入口与编辑方式

规范工作区：`/opt/heyi/dev/ai-video`。

允许：

- VS Code Remote SSH 等方式直接编辑该目录；
- 团队批准的定向源码同步，在同步前比较服务器差异并只传本任务文件。

禁止：

- 自动上传所有本地 worktree、整个用户目录或私有 `.env.development`；
- 覆盖服务器其他会话的未提交内容；
- 将 DevTools 远程更新端点暴露到公网；
- 通过源码同步清理真实数据库、Redis、队列、卷或历史回执。

服务器使用既有私有生产环境，不由 `scripts/dev.py init` 生成或覆盖。本地 `compose.dev.yaml` 仅用于隔离 MySQL/Redis，不能部署到共享服务器替代真实依赖。watcher 入口为 `python3 scripts/shared_dev.py watch --config /etc/heyi-dev/runtime.json`，并提供 root 使用的 `recover`/`status`；`heyi-source-watch.service` 已 enabled、active。完整 Compose 链和受限配置见现场状态。

## 5. 热更新和安全点

服务器前端保存后由 Nginx 直接读取 bind 的源码；本地由 Spring 提供静态源码。服务器同页自动刷新已通过真实图形浏览器验证，源码新增和还原内容均无需手动刷新。Java 保存后 watcher 仅编译到 `target/classes`，成功后触发 DevTools 重启。Java 仍需编译和上下文重建时间，不能宣称任意修改零延迟。

共享数据环境每次 Java 重启都必须：持有现有维护锁，停止领取新工作，等待已领取工作达到安全点，成功编译后触发重启，核对健康/TLS/鉴权/关键页面，再恢复本次门控。未知租约、残留锁、已跨外部副作用边界的任务或并行维护会阻止重启。

编译失败不触发重启，上一可用上下文继续服务。`pom.xml` 依赖变化由 watcher 受控重启 app；Flyway 迁移、环境配置、JDK/Maven 与 watcher/运行工具本身变化须显式维护。共享 Flyway 自动执行已禁用，migration 源码/hash/history 守卫禁止普通保存执行 DDL。

## 6. 持久化、配置与日志

保留真实 MySQL、Redis Session/Stream、业务文件卷、OSS 配置、TLS、登录、权限、模型/店铺加密密钥、内部令牌、同步计划、检查点、租约和上游限流。密钥变化必须提供已有密文兼容或迁移方案。

生产沿用现有脱敏日志策略。临时诊断使用有界短期输出和小型回执，不记录完整请求/响应、Cookie、令牌、密钥或业务导出。源码 watcher 使用 `heyi-source` journal namespace，容量上限 64 MiB、保留 3 天；revision 路由 `access_log off`。

## 7. 备份、恢复与容量

历史备份约束为每天 03:20（Asia/Shanghai）、最多 15 分钟随机延迟、两份完成归档、单份 6 GiB、8 GiB 保留空间、3600 秒处理预算和 3900 秒服务超时；timer 为 `Persistent=false`。接手时重新核对实际 unit、最近成功结果和恢复证据。源码切换不触发漏跑补备份。

历史镜像、Compose、`heyi-ops` 和 Release 资料继续保留作恢复。v3/v4/v5 因确认的定时器缺陷已撤销回滚资格；其他历史版本恢复前也要重新核对当前 schema、配置和接口兼容性。应用恢复不回滚数据库或外部写入。

禁止全局 Docker prune、删除 containerd 目录或清理业务卷。只删除本任务创建、用途明确且无运行引用的临时编译/传输文件。

## 8. 日常巡检与故障处理

| 检查 | 通过条件 |
| --- | --- |
| 源码进程 | 实际命令、JDK/Maven、工作目录和环境来源与回执一致 |
| 后端 | `/actuator/health`、关键接口和匿名权限边界符合预期 |
| 前端 | 实际 HTTP 内容来自规范 `frontend/`；保存后更新且不需静态包 |
| Java 更新 | 成功编译后新上下文提供新结果；记录真实耗时 |
| 编译失败 | 不触发重启，上一可用上下文保持健康 |
| 数据保护 | DB/Redis/卷/会话/检查点/租约/限流未被重置 |
| 外部访问 | TLS、登录、角色权限和必要反向代理正常 |
| 维护 | 没有遗留门控、锁或本次暂停未恢复的工作器 |

积压只作为诊断数据，不能自动触发重启。源码运行异常先判断是否为编译、上下文、环境或代理问题；不得通过清队列、删库、重写迁移或停掉无关容器来取得表面健康。

## 9. 实际验收与后续事项

共享后端冷启动、Java 成功热更新、失败编译保留 context/output/health、schema/history 保持与会话验证已完成，回执路径与耗时见[源码运行现场状态](source-runtime-status.md)。服务器前端同一页面自动刷新、还原及登录保持也已通过图形验收；GitHub 合并/默认分支变更仍按该记录跟进，不提前标记完成。
