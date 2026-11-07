# 发布、回滚与运维交接

核对日期：2026-11-07。**当前应用基线为 `v2026.11.7.7`，已修复首次接管引入的定时工作器停调度问题，原上传任务已自然恢复推进。** 自动发布、巡检和有限自愈已启用。`v2026.11.7.8` 用于本批纯文档交接，实际源码版本以 `heyi-ops status` 和正式 Release 为准。日常入口见[CI/CD 指南](cicd.md)，主机分类清单见[服务器运维交接](server-handoff.md)，验收及故障闭环见[一期收尾](../releases/infrastructure/devops-phase1-closure-20261107.md)。

生产操作先读 [AGENTS.md](../../AGENTS.md)，重新核对实际容器、源码指针、镜像摘要及最新回执。多个会话共用生产机，必须按双锁顺序串行协调。

## 最近现场核实的版本

应用修复版本为 `v2026.11.7.7`，候选及已安装控制面源码 `20bb15739b2f8601d1f87c4e342ed15747936466`，生产合并 `27ddaa730c434ccc7a187eb6cf8fdbb5a952293c`。此次标准自动发布只切换后端，前端复用 `867724c` 构建的原镜像，执行器用时 43 秒。后续仅文档发布推进 `/opt/heyi/source/current`，保留相同镜像及容器 ID。

| 服务 | 运行 manifest digest | config digest |
| --- | --- | --- |
| Java 后端 | `5db37670c5f7f70a9df8030b4b9f71cffffcc920ed6198581d0b3c3ead71034a` | `3adc5ef71f16e1047fcff09162f925a3fa9e5a8293e5f06a2529f0e2f0b7f019` |
| Web 前端 | `ddea0f67a282d99e3a8113fb62f4509c1a29486a41360791024ec5f9166c5e59` | `a33e888de618299a7a9aba639b1a51b07bd16cf065652adc782096f19fe764a9` |

摘要均为 SHA-256，仓库分别为 `ghcr.io/gotit-zhx/ai-video/app`、`frontend`。Docker 29 containerd 的 image ID 可等于 manifest，不能直接与 config digest 比较。

两组旧体系回滚组合仍在主机保留：`finance-advertising-20261106` 后端/前端（`705cc4678025` / `34dccb8abc37`），以及 `finance-cold-queries-20261106` 后端 / `brush-source-summary-20261106` 前端（`4b27e931e273` / `a86f6307e654`）。它们有历史验收，恢复前仍须核对当时的 schema、配置和业务兼容性；旧维护协议不能直接冒充新入口已验收版本。

相关生产路径：

- 固定配置：`/opt/heyi/ops/compose/base.json`、`production.json`；后端引用 `/opt/heyi/ops/releases/v2026.11.7.7/images.json`，未重建的前端仍引用 v4 的 `images.json`，各三份，不再加载 252/240 份覆盖。不能删除前端仍引用的旧配置。
- 源码与文档：`/opt/heyi/source/current`；与历史 `/opt/heyi/current` 分开使用。
- 运维状态：`/opt/heyi/ops/active.json`、`baseline.json`、`operations/`、`requests/`；仅 root 维护，不手工改 active/version 绕过校验。
- 首次接管及备份/TLS/权限证据：`/opt/heyi/operations/devops-phase1-20261107-946c8f18/`，GitHub Release 附带脱敏摘要。
- 私有广告 properties：`/opt/heyi/shared/finance-ads/`，继续只读挂载；正式代理接入由独立维护完成。

首次接管确认 schema、配置和租约恢复，但健康检查遗漏了 Spring 周期任务再次排队的缺陷。v7 已用真实定时器回归及原有上传任务推进验证修复；11 月 7 日收尾复核 schema 仍为 V147、配置与基线一致、维护关闭且没有未完成运维。不得再把 v4/v5 的健康验收视为上传业务正常。此前[广告发布](../releases/finance/finance-advertising-20261106.md)、[冷查询发布](../releases/finance/finance-cold-queries-20261106.md)和[性能发布](../releases/finance/finance-bi-performance-20261106.md)保留其历史业务事实，广告真实账户接入仍需独立验收。

## 接管后的只读基线

先通过公司交接渠道取得服务器和 Docker 权限，确认当前主机、Docker context、容器、镜像 ID、Compose 文件列表、挂载及回执。不要把完整 `docker inspect` 或 Compose 展开配置发到公开记录，其中可能包含凭据。

在正确服务器可选择性读取非敏感字段：

```bash
docker ps --format '{{.Names}}\t{{.Image}}\t{{.Status}}'
docker inspect --format '{{.Config.Image}} {{.Image}} {{.HostConfig.LogConfig.Type}}' ai-commerce-app
docker inspect --format '{{index .Config.Labels "com.docker.compose.project.config_files"}}' ai-commerce-app
docker inspect --format '{{range .Mounts}}{{println .Type .Destination}}{{end}}' ai-commerce-app
```

这是现场核对步骤，不是部署命令。用同样方式核对前端和其他服务；把镜像 ID、配置引用、挂载目的地及健康结果记录成小型回执，不保存环境变量值。主应用通常为 `ai-commerce-app`、`ai-commerce-frontend`、`ai-commerce-mysql`、`ai-commerce-redis`；实际名称仍以现场为准。

检查主站 HTTP、后端健康、匿名财务接口 401、必要静态文件及 gzip 哈希；完成登录后再核对权限和关键页面。财务公司数据需使用已有授权账号做只读抽样，不向真实店铺导入测试文件。

## 发布准备与激活

1. 按 Gitflow 在支持分支开发，完成 PR 必需检查。release/hotfix 的 Candidate artifacts 必须成功生成合格候选和证明，再合入 `main-tree` 并创建正式标签。
2. Production deployment 校验标签/候选树、组件指纹、来源证明和版本关系。托管 Runner 传输源码和缺失的原始 OCI 镜像；服务器重新校验全部摘要及磁盘预算。
3. 服务器独立 systemd 执行器持双锁复核实际基线。配置漂移、新迁移、其他维护或无法确认的活动任务会阻止切换。
4. 关闭新业务入口及任务领取，保留健康检查；等待安全点后切换。503 包含 Retry-After，写请求不自动重放，成本积压保留。
5. 验证健康、鉴权、维护协议、配置、schema、静态资源/gzip，再恢复本次任务状态、同步源码并保存回执。SSH 或 Actions 中断后仍由服务器收尾。
6. 查看 GitHub Release 与 `heyi-ops doctor`。纯文档版本复用镜像、更新源码，跳过门控和容器切换。

数据库迁移、其他服务和运维程序变更必须走各自维护流程。控制面升级的核对与登记见[主机维护](cicd.md#主机维护与控制面升级)，不能仅改指纹让普通部署越过检查。

历史 `scripts/release/release-*.py` 仍可能是旧回执依赖，不能只改日期就重跑。明确需要历史叠加发布时，仍须先执行 `python3 scripts/compact-release-image.py --prepare SOURCE UNIQUE_TARGET`，使用返回的不可变 `baseImage`，并核对完整输入、停领恢复及回滚；正常 CI/CD 使用全量镜像构建。

## 回滚

优先使用 GitHub Operations → `rollback`，选择仍保留且已验收的受管版本；或由授权身份调用 `heyi-ops rollback VERSION EXPECTED_VERSION OPERATION_ID`。操作 ID 为 32 位小写十六进制，必须记录本次目标和当前基线，不能复用于其他请求。

- **v2026.11.7.3 / v2026.11.7.4 / v2026.11.7.5 已禁止回滚**：三者复用存在定时器缺陷的后端。原始 `verified.json` 已移到 `/opt/heyi/ops/quarantine/20261107-upload-scheduler/<版本>/`，受限部署身份的真实回滚请求均在入队前被拒绝。原证明、发布清单、镜像、源码和回执保留，禁止复制验证文件重新启用。
- 当前健康受管应用组合只有 v7 一组；v8 复用它，并不会增加第二组独立应用回滚镜像。保留列表含历史证据不代表仍有发布资格。旧体系两组镜像继续保留，使用前另行审查兼容性，不为凑足数量重新启用缺陷版本。
- 先确认数据库历史、迁移输入及运行配置兼容。自动回滚恢复应用/源码，数据库、业务卷、密钥、同步进度和已发出的官方写入不会随之撤销。
- 不通过删除数据或修改迁移校验和来回滚。数据库恢复另走隔离验证后的独立维护流程。
- 回滚后复核 `status`、`doctor` 和操作回执。清理临时上传目录后仍可诊断、恢复和回滚；保留版本的源包、配置及镜像不得被删除。
- 旧体系镜像不在新入口的自动回滚集合中，必要时依据持久化的旧配置和兼容性证据安排人工维护，不直接整套 Compose 重建。

## 存储与发布后清理

沿用[2026-11-02 存储规则](../storage-cleanup-20261102.md)：

- 不新增持久应用日志或诊断日志。已记录的生产核心容器日志驱动为 `none`；不能为排障长期改成文件日志。优先健康、状态表、限量只读查询和短期输出。
- 保留活动镜像和两个已验证近期回滚版本，以及所有被容器引用的镜像及无关服务镜像。只在验收后删除明确无引用的旧应用/前端标签。
- 不手动删除 Docker/containerd 目录，不做 volume prune，不导出运行中容器或挂有业务卷的容器。
- 清理本次暂存 JAR、类文件、前端副本、上传包、测试库/账号及私有测试环境文件。保留运行中 Compose、必要发布 helpers、小型回执、业务文件与配置。
- 自动成本重算只更新当前结果，不再追加完整前后 JSON；人工配置和明确导入审计继续保留。原始财务账本不能作为存储优化对象删除。

## 备份与恢复

当前保留 11 月 6 日、11 月 7 日（北京时间）两份归档。最新已有归档已完成全量 gzip 与 SHA-256 校验，回执仍按归档真实时间判断新鲜度；没有触发新导出、补备份或数据库恢复。数量/体积/时长边界与未验证范围见[备份现场交接](server-handoff.md#9-备份恢复与容量边界)。

仓库服务配置在 `ops/admin-console/heyi-mysql-backup.service` / `.timer`，脚本为 `scripts/heyi-mysql-backup.py`。定时基准为北京时间每天 03:20，随机延迟最多 15 分钟；`Persistent=false`，超时边界 3900 秒，标准输出和错误为 null。

接手时核对现场安装版本、最近成功备份、保留数量/体积/执行时长边界、保存位置、访问权及恢复记录。保留现有上限，不因部署触发漏跑补备份。文档更新不触发备份或恢复。

MySQL 之外还需分别交接持久卷、OSS 对象、招聘 SQLite/私有资料、独立 ERP/家务数据库及必需密钥。恢复演练在隔离环境检查：数据库可加载、加密字段可解密、素材引用可访问、关键只读页面/导出可用。恢复前关闭测试环境的真实官方写入、自动发消息及生产同步凭据。

## 常见排障入口

| 现象 | 优先检查 |
| --- | --- |
| 新机器前端无法启动 | 是否误用生产 Nginx/证书挂载；使用 `compose.local.yaml` |
| 本地 App 构建缺少 JAR | `Dockerfile.local` 需要先生成 `app-local.jar` |
| 页面脚本 404 / 菜单空白 | COPY 清单、`index.html` 引用、静态资源/gzip 与缓存版本；详见交接缺口 |
| 登录成功后立即 401 | HTTP 的 Secure Cookie、主机名、Redis 会话、账号是否在其他设备重新登录 |
| 招聘接口 404/无法连接 | 独立 Python 服务、本地/生产 Nginx 路由、设备授权及助手版本 |
| 财务列表查不到已知订单 | 全部/已结算视图、下单/首次结算日期、精确编号跨日期规则及公司/分组/店铺筛选 |
| 同步排队/长期未完成 | 通道进度、租约、冷却、手动优先及 ORDER/SETTLEMENT 完整月证明；“最近成功”不等于整店完成 |
| 服务健康但上传长期 PENDING | 查实际后端摘要、维护状态、领取计数/锁和定时器回归版本；v7 修复记录见[上传调度恢复](../releases/commerce/upload-scheduler-recovery-20261107.md)。保留原流程，不重复生图或重建未知外部发布 |
| 千易单据不进收货合计 | 真实 receiveTime、完成状态、非删除、对应退回卖家签收证据；未知状态不补猜 |
| 导出与人民币看板不同币种 | 费用矩阵始终导出原币分表；不是汇率转换失败 |
| 模型失败或视频不完整 | 提供方状态、Runtime/模型凭据、限量任务事件、素材权限及已保存恢复状态，避免重复付费提交 |

排障仍需遵守 [AGENTS.md](../../AGENTS.md) 的数据、限流和存储约束。

## 上游历史验收补录（2026-11-06）

11 月 5 日的现场检查及源证据保留原样。随后同步的[GIFT 发布记录](../releases/finance/finance-gift-zero-20261105.md)描述该日 18:53—18:55 的最终验收与当时回滚清理，属于历史发布事实；当前镜像与回滚保留以上表及最新运维回执为准。

新增日期修复、GIFT 一次性扫描、原生压缩和索引维护约束见[当前财务规则](../business/finance/current-rules.md)。不要重新启动已完成的 GIFT 扫描；历史日期修复是否结束以最新凭据为准，不在本次拉取合并中执行维护程序。
