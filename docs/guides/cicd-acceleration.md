# 快速迭代的 CI/CD 使用指南

本轮保留 GitHub Actions、私有 GHCR 和大陆服务器的受限执行器，不新增付费资源。实际启用状态见[改造验收](../releases/infrastructure/cicd-acceleration-20261110.md)及仓库变量。生产分支 `main-tree`、集成分支 `develop`，所有合并仍使用 Gitflow 的 no-ff。

## 日常发布

在干净工作区中使用已有 GitHub CLI 登录：

```bash
python3 scripts/ci/release_flow.py --source develop --dry-run
python3 scripts/ci/release_flow.py --source develop
```

第二条命令预留未占用版本、创建 release 分支和 PR、等待验证、生成候选、核对主机、合并生产分支、打标签、等待部署与回执，再合回 develop。紧急修复使用 `--source hotfix/<版本或主题>`，来源必须是已发布生产历史；活动 release 也会收到必要合回。

中断时保留输出的 journal ID，在同仓库恢复：

```bash
python3 scripts/ci/release_flow.py --resume <32位journal-id>
```

状态保存在 Git common dir 的 `heyi-release-flow/`，同仓库 worktree 共用锁。恢复重新核实 GitHub 和原部署回执，已完成的部署不重复执行。不同主机的会话仍须协调生产窗口；本地文件锁不能代替服务器双锁。

未提交内容、业务合并冲突、生产基线变化、权限不足及未完成的迁移/控制面维护会明确停止。处理原因后恢复原 journal，不创建第二次部署掩盖未知结果。纯文档变化返回 `no_runtime_release`，文档随下次应用发布同步。

## 验证与缓存

| 变化范围 | 策略 |
| --- | --- |
| 纯文档 | 策略校验，不生成应用发布 |
| 已映射前端或普通模块 | 公共策略、相关回归、实际镜像冒烟 |
| 财务后端、权限、依赖、迁移、公共基础设施 | 完整回归 |
| 未知路径、新增未映射测试、规则变化 | 完整回归；无法纳入完整测试清单则失败 |
| 缺少合格全量结果、最近全量失败或超过 48 小时 | 完整回归 |

Java 每轮 CI 编译一次，单元和三组隔离数据库测试复用编译输出。前端只安装 Node，Java 任务按需安装媒体工具。报告按 run attempt 分离，缺报告、异常跳过和失败都不能通过 `CI required`。

候选只消费 GitHub API 核实的成功 CI，绑定仓库、事件、提交树、run/attempt、产物摘要、规则及套件输入。PR 合成结果用于范围判断；有发布权限的包装使用受信任分支的执行证明。证明不足自动补测。

runtime 固定 JRE、FFmpeg、字体及全部系统包 BOM，按已验收不可变摘要复用。受信任 push/dispatch 的 CI 可准备合格 runtime；PR 保持无私有包凭据，未取得合格 runtime 时走冷构建。前端稳定资源和频繁改动文件分层，应用镜像不逐次叠加旧 JAR。

每天 02:00（Asia/Shanghai）触发 develop 全量回归；调度任务自身不能作为 develop 的执行证明。

## 大陆服务器传输

Runner 下载源码、证明及镜像 blob，通过受限 SSH 发送；已存在且摘要正确的 blob 省略。服务器完整重建和验证 OCI 图，再导入同一镜像摘要。缓存不可用时回退完整传输，内容损坏时拒绝并要求修复。

缓存只接受独立维护核实的 `/etc/heyi-ops/containerd-content.json`，绑定实际 socket、namespace 和 Docker engine ID；不猜测默认 `ctr`。无绑定时通过有界 `docker image save` 流验证元数据，不导出运行容器或业务卷。

每个 registry 文件总预算 300 秒，每次最多 120 秒、最多三次可恢复网络重试。认证、摘要及不安全重定向失败直接拒绝。指标分开记录下载、OCI 打包、客户端上传完成、服务端接收完成、导入、业务排空及健康验收。

10 GiB 安全余量之外仍需满足真实暂存峰值，包含压缩内容、归档副本、daemon 内容、解包和源码。保留当前和两个合格近期回滚，不清理业务卷、备份或容器引用。

## 首次安装和开关

日常只使用发布命令；以下仓库变量由首次维护统一设置，缺失视为关闭。

| 变量 | 作用 |
| --- | --- |
| `HEYI_FAST_CI_ENABLED` | 按风险选择测试范围 |
| `HEYI_DELTA_TRANSFER_ENABLED` | 缺失 blob 协商与增量传输 |
| `HEYI_RELEASE_FLOW_ENABLED` | 允许发布命令写入远端；本地 false 可临时禁用 |
| `HEYI_MANIFEST_V2_ENABLED` | 生成带验证证据的 v2 清单 |
| `HEYI_V2_BOOTSTRAPPED` | 首个 full v2 验收后允许分级候选 |
| `HEYI_RUNTIME_CACHE_ENABLED` | 复用合格 runtime 和构建缓存 |
| `HEYI_RUNTIME_BOOTSTRAP` | 一次性生成和验收 runtime，完成后关闭 |

启用顺序：新 CI 完整验证 → v1/full 候选 → 双锁内独立安装控制面与缓存绑定 → 全量标准发布 → 核对旧回滚资格 → 增量和 v2/full 验收 → 分级及发布命令。不能在旧控制面发送快速 v2，不能只改 operations 指纹代替安装。

`Operations / release-check` 使用既有只读巡检身份，生成五分钟内有效的主机指纹和维护状态；候选不能自批维护。真实 `Release ready` 检查产生且通过后，才加入生产分支保护。保留 `CI required`、严格分支更新、来源证明及数据库兼容门禁。

## 性能观察

命令按真实 operation ID 保存一份样本到 `heyi-release-flow/samples/`。参数入口：

```bash
python3 scripts/ci/performance_summary.py --help
python3 scripts/ci/network_probe.py --help
```

网络探测须显式 `--run`，默认只显示计划；固定公共 HTTPS 目标，有时间/流量上限，不改 DNS 或代理。性能按发布类型、验证范围和缓存状态分组，失败与重试保留；少于十次同类自然发布只报告 `insufficient_samples`。单次 CI、本地热构建或合成传输不能当作生产 P50/P95。
