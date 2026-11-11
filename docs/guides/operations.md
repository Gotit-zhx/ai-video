# 源码运行、恢复与运维交接

核对时间：2026-11-11（北京时间）。共享服务器主应用和前端已实际切换，验证结果与未完成事项见[源码运行现场状态](source-runtime-status.md)。本文规定日常运行边界和操作保护。旧版本、镜像和自动发布结果保留在历史回执中。

共享服务器操作先读 [AGENTS.md](../../AGENTS.md)，重新核对实际进程、源码工作区、配置、挂载、数据库和其他会话维护状态。多个会话共用服务器，任何启动、热重启、迁移、恢复和清理都须串行协调。

## 当前入口与状态判断

本地源码入口：

```bash
python3 scripts/dev.py init
python3 scripts/dev.py run
```

共享服务器规范源码根为 `/opt/heyi/dev/ai-video`。服务器已使用 JDK 21.0.9+10、Maven 3.9.11 和 dev profile；前端由既有 Nginx bind 该工作区的 `frontend/`，本地才由 Spring 直接提供静态源码。完整 Compose 叠加链与实测耗时见现场状态。

共享 watcher 由已 enabled、active 的 `heyi-source-watch.service` 管理，其入口为：

```bash
python3 scripts/shared_dev.py watch --config /etc/heyi-dev/runtime.json
```

中断恢复和只读状态分别使用 `recover`、`status`。受限配置和常驻入口已经安装验收；执行恢复前仍须核对本次门控归属及其他会话维护状态。

当前仍复用 `heyi-ops` 的 `Store`/`Host` 维护锁、门控与安全点，保留 `doctor`、`monitor` 和受限 SSH 网关。日常只读巡检使用 `heyi-ops doctor`；源码上下文与恢复状态使用上述 `scripts/shared_dev.py status`。`scripts/ci/host_status.py` 和旧 `heyi-ops status` 输出的是历史发布版本/指纹协议，不能作为当前源码内容或上下文的验收证明。完整 Compose 链继续参与运行，不能把基础配置和其他覆盖文件一并停用。

GitHub 的 Operations 从 `develop` 读取巡检代码，日常菜单只保留 `doctor` 和通知测试；`monitoring` 环境也只允许 `develop`。旧 `release-check` 已退出日常菜单，历史发布状态仍可在明确的恢复核对中读取。

后续接手和变更验收继续核对：

- 实际源码路径及工作区 revision/diff 边界；
- Java/Maven 版本、启动命令和 dev profile；
- `http://localhost:8080/actuator/health`、外部 TLS/登录/权限及关键页面；
- 前端源码保存后实际 HTTP/页面更新，Java 修改编译到新上下文后的实际结果；
- 编译失败时上一可用上下文仍可服务；
- 真实数据库、Redis、卷、密钥、会话、同步检查点、租约、限流和任务门控保持。

本次后端切换、成功/失败编译、健康及会话证据已落盘；服务器前端同页自动刷新已通过真实图形浏览器验证，源码新增及还原内容均自动反映在保持登录的原页面。业务模块的完整回归仍按各自变更范围执行。

## 源码变更与热重启

允许通过 VS Code Remote SSH 直接编辑规范工作区，或使用团队批准的定向同步。同步前读取服务器差异，只覆盖本任务已核对文件。不得自动上传所有本地 worktree，不得覆盖其他会话的未提交修改，不得同步 `.env.development`、生产密钥、Cookie 或业务导出。

前端 HTML/JS/CSS 直接读取源码，不生成静态包、gzip 或前端镜像。Java watcher 只编译到 `target/classes`；编译成功后才更新 DevTools trigger。Java 修改需要实际编译和上下文重启的数秒时间，不能承诺零延迟。`pom.xml` 依赖变化会受控重启 app；Flyway 迁移、环境配置、工具链及 watcher/运行工具本身变化必须显式维护并单独验证。

共享真实数据环境的 Java 重启顺序：

1. 获取现有共享维护锁，确认没有其他会话正在发布、迁移、恢复或清理。
2. 启用本次业务门控，停止领取新工作；等待已领取任务达到现有安全点。未知租约、残留锁或已跨外部副作用边界的工作继续阻止重启。
3. 完成编译。编译失败不触发重启，保留上一可用上下文并报告错误。
4. 触发受控重启，核对健康、TLS、鉴权、权限、关键页面和本次变更。
5. 仅恢复本次设置的门控，确认原同步计划、检查点、租约和限流继续工作。

持久积压不等于活动工作，不能为了“清零”修改业务记录或清队列。应用重启不修改数据库、业务卷、会话、密钥或官方平台写入。

## 配置与数据保护

- 生产/共享环境继续使用既有私有环境、TLS、登录和权限配置；DevTools 远程更新接口不得对外开放。
- 共享环境 Flyway 自动执行已禁用，migration 源码/hash/history 守卫阻止普通保存执行 DDL；Flyway 只追加新迁移，不编辑、重命名已应用 SQL，不用 `repair` 掩盖校验差异。迁移失败或部分 DDL 不清库，保留门控和证据后处理。
- 保留财务口径、公司/店铺隔离、同步游标、检查点、租约、上游限流和结果未知状态。源码运行方式变化不得顺带改变业务规则。
- watcher 使用 `heyi-source` journal namespace，容量上限 64 MiB、保留 3 天；revision 路由关闭访问日志。其他日志沿用既有脱敏策略，不记录完整请求/响应、凭据、Cookie、令牌或业务导出。
- 备份保持已验收的数量、体积、空间和时长上限；timer 维持 `Persistent=false`，源码重启不触发漏跑补备份。

## 历史恢复

旧 Candidate/Release、应用镜像构建与部署动作、发布版本证明和 `scripts/release/` helpers 保留作历史恢复与审计资料，不是日常源码运行入口；当前复用的 `heyi-ops` 组件与完整 Compose 链仍按上文运行。恢复旧应用前重新核对当前数据库结构、接口、配置、密钥和卷的兼容性。镜像恢复只恢复应用运行方式，不回滚数据库或外部写入。

2026-11-07 的 v3/v4/v5 因定时器缺陷已撤销回滚资格；原证明保留在隔离目录，不能复制资格文件重新启用。其他历史版本的“已验收”也只代表当时状态。精确镜像、配置和回执见[服务器运维交接](server-handoff.md)及对应发布记录。

明确需要旧叠加镜像恢复时，才按历史保护执行 `python3 scripts/compact-release-image.py --prepare SOURCE UNIQUE_TARGET` 并使用返回的不可变基础镜像。不得把该命令写成当前源码运行步骤。

## 存储与清理

- 不手工删除 Docker/containerd 目录，不做 volume prune 或全局 image prune，不导出运行中或挂有业务卷的容器。
- 清理前列明路径、创建会话、用途和运行引用。只删除本次任务创建且已确认无引用的编译输出、上传包、测试库/账号和私有测试环境文件。
- 保留实际运行配置、业务卷、备份、当前源码、历史恢复脚本和小型回执；历史部署目录不能按日期整目录删除。
- 独立桌面安装包或客户端分发只在明确任务中构建和清理，不与主应用源码重启捆绑。

## 常见排障

| 现象 | 先检查 |
| --- | --- |
| 本地依赖无法启动 | `compose.dev.yaml`、3307/6380 端口占用、`.env.development` 是否存在且未被覆盖 |
| 登录后立即 401 | 本地 Cookie Secure 设置、同源 `http://localhost:8080`、已有账号状态；不要先清 Redis 或重置生产账号 |
| 前端保存后页面未更新 | 实际读取的 `frontend/` 路径、浏览器缓存、dev 自动刷新是否启用；无需构建 gzip/镜像 |
| Java 修改未生效 | watcher 编译结果、`target/classes`、trigger、DevTools 重启和健康；不要执行 `package` 作为日常补救 |
| 编译失败后服务异常 | 是否错误触发重启；恢复上一可用 classes/进程状态并修复编译，不清数据库或队列 |
| 依赖或迁移变化未生效 | `pom.xml` 依赖变化检查受控 app 重启；迁移/运行配置/工具变更走显式维护，不能让普通保存执行 DDL |
| 共享服务器状态不明 | 先做只读现场核对并查最新回执，不从旧 Release 或镜像名称推断 |

## 历史事实入口

旧自动发布工作流文件已移至[历史工作流归档](../history/legacy-release-workflows/)。2026-11-06/07/10/11 的版本、镜像、广告连接和 CI/CD 验收继续保留在 `docs/releases/`，但不再是当前日常启动步骤。
