# CI/CD 加速历史边界

更新：2026-11-11。主应用已改为源码开发运行模式。本文不再提供日常发布命令；旧 GitHub Actions、Candidate、私有 GHCR、OCI 增量传输和统一发布 CLI 属于已实施过的历史发布体系，未部署的“生产文件发布入口”也不再继续作为当前方案。

当前日常入口是：

```bash
python3 scripts/dev.py init
python3 scripts/dev.py run
```

已有兼容 MySQL/Redis 时可使用 `python3 scripts/dev.py run --no-deps`。共享服务器主应用与前端已实际切换，证据见[源码运行现场状态](source-runtime-status.md)。

## 当前 CI 的作用

CI 用于协作检查和后台回归，不是保存源码后的运行门禁：

- `feature/*` 通过 PR 合入 `develop`，按影响运行必要检查；需要保留分支关系时使用 `--no-ff`。
- Java 普通开发使用 `compile`/定向测试，不为代码生效执行 `package`、repackage、JAR 或 OCI 构建。
- 前端直接读取 `frontend/`，检查引用、语法和相关页面行为，不生成静态发布目录、gzip 或镜像。
- 全量 Java、数据库、UI、桌面与旧恢复演练可在后台或手动运行；失败和跳过仍须如实记录，不能用源码运行掩盖真实缺陷。
- 依赖、Flyway 迁移、权限、金额计算、租约和共享基础设施变化扩大验证范围；`pom.xml` 依赖变化会受控重启 app，迁移和运行配置走显式维护。

## 网络与服务器边界

源码模式不要求 GitHub Runner 向大陆服务器传输完整源码包、OCI blob、Candidate 或 Release 附件。共享服务器规范源码根为 `/opt/heyi/dev/ai-video`，只允许以下两种来源：

1. 通过 VS Code Remote SSH 等方式直接编辑服务器工作区。
2. 使用团队批准的定向源码同步，只传本次明确范围并在覆盖前核对服务器差异。

不得自动上传所有本地 worktree，不得覆盖其他会话的未提交修改，也不得把开发私有环境文件、密钥或完整业务导出放入同步内容。

Java 修改仍需在服务器完成编译和上下文重启，不能把网络传输缩短解释为 Java 零延迟。服务器前端由 Nginx bind `frontend/`，本地由 Spring 直接读取源码；服务器同页自动刷新已通过真实图形浏览器验收，见现场状态。

## 旧体系保留范围

旧 `scripts/ci/release_flow.py`、Candidate/Release 工作流、镜像缓存和传输工具保留作历史审计或明确的恢复任务，不是当前日常命令。原工作流文件见[旧发布工作流归档](../history/legacy-release-workflows/)，相关开关、证明链、磁盘预算和当时的真实性能数据见[2026-11-10 改造验收](../releases/infrastructure/cicd-acceleration-20261110.md)。

旧流程曾经成功并不代表它仍是当前入口；仓库中仍存在工作流文件也不代表服务器正在使用它。若明确恢复旧镜像或执行独立桌面分发，须重新核对当前数据库、配置、凭据、卷、接口和回滚兼容性。

## 性能记录口径

源码模式只记录真实自然样本：前端文件保存到浏览器看到更新、Java 编译到新上下文健康、失败编译保留上一可用状态，以及共享环境 gate/drain/restart/restore 的实际耗时。测试、编译、重启和浏览器刷新分开计时；单次本地样本不宣称长期 P50/P95。
