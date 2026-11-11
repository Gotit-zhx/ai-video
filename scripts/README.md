# 工程与运维脚本

脚本按用途归类，模块业务服务已归入 `services/`。所有命令默认从仓库根目录运行，除非具体工具说明另有要求。

| 目录 / 入口 | 内容 |
| --- | --- |
| [dev.py](dev.py) | 主应用源码开发入口：`init` 生成缺失的 `.env.development`，`run` 启动依赖、Java watcher/DevTools 与前端源码；无参数等同 `run` |
| [shared_dev.py](shared_dev.py) | 共享服务器源码 watcher：复用现有维护锁、门控、安全点、编译回退和恢复；依赖受限 `/etc/heyi-dev/runtime.json` |
| `release/` | 历史及专项发布 helpers，依赖已验证基线、准备目录和服务端保留文件 |
| `finance/` | 财务 Java 核对、迁移及专项维护程序 |
| `qa/` | 前端、模拟、集成及冒烟脚本；`qa/java/` 为相关 Java 工具 |
| `diagnostics/` | 状态核查、来源审计、诊断、样本与性能检查 |
| `maintenance/` | 修复、恢复、清理及专用维护程序，包含相应单元测试 |
| `local/` | 本地 cpolar 回调白名单网关及启动器 |
| `build/` | 前端国际化等显式构建辅助工具；不是日常前端源码运行前置步骤 |
| `ci/` | 协作检查、历史发布辅助及 Windows/macOS 独立分发构建 |
| `video/` | 既有视频专项操作入口 |
| [compact-release-image.py](compact-release-image.py) | 保持原路径的镜像压缩保护 |
| [heyi-mysql-backup.py](heyi-mysql-backup.py) | 保持原路径的备份实现 |

日常本地开发先运行 `python3 scripts/dev.py init`，再运行 `python3 scripts/dev.py run`；已有兼容 MySQL/Redis 时可加 `--no-deps`。它不执行 Maven `package`、JAR/OCI/静态包构建。目录名不代表只读或可批量安全执行。QA、诊断、恢复和历史发布脚本中均可能存在真实 API、数据库或 Docker 写操作；运行前读取具体实现、确认目标与授权范围，不能对全目录直接执行。

共享服务器日常只读巡检使用 `heyi-ops doctor`，源码状态使用 `python3 scripts/shared_dev.py status --config /etc/heyi-dev/runtime.json`。当前仍复用 `heyi-ops` 的 `Store`/`Host`、`monitor`、受限 SSH 网关和完整 Compose 链。`scripts/ci/host_status.py` 为旧正式发布版本/指纹协议保留，不能证明当前源码上下文，也不替代日常 `doctor` 或源码 `status`；Candidate、镜像构建与部署动作才属于历史恢复范围。

历史发布脚本中的 `git show <旧提交>:<旧路径>` 用于读取当时源码，保留旧路径是必要行为。当前工作树读取使用新路径；服务器 `/opt/...`、容器 `/app/...` 与业务卷路径不随仓库目录重排改写。

客户端及服务的模块内部构建脚本仍放所属模块附近。独立桌面制品只在明确分发任务中构建。新增工具归入对应目录；常用开发及测试入口见[开发指南](../docs/guides/development.md)，共享服务器源码运行与历史恢复遵守[运维指南](../docs/guides/operations.md)。
