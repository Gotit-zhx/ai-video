# 发布、回滚与运维交接

核对日期：2026-11-07。服务器 `8.163.34.81` 于 **09:18 激活财务广告版本 `2026.11.6.3`**，报表和运行验收通过，真实广告授权仍未通过，见[广告发布记录](../releases/finance/finance-advertising-20261106.md)。11 月 6 日的[冷查询发布](../releases/finance/finance-cold-queries-20261106.md)、[第一轮性能发布](../releases/finance/finance-bi-performance-20261106.md)及[项目同步记录](../releases/infrastructure/project-sync-20261106.md)保留历史证据。主机和通用入口见[服务器运维交接](server-handoff.md)。生产操作先读 [AGENTS.md](../../AGENTS.md)，并重新读取实际运行基线。

统一 CI/CD 正在按[GitHub Actions 与统一运维](cicd.md)实施，是否已安装、启用及验收以[一期实施回执](../releases/infrastructure/devops-phase1-20261106.md)为准。

## 最近现场核实的版本

2026-11-07 09:28（北京时间）复核，后端和前端均为 `finance-advertising-20261106`，版本 `2026.11.6.3`。广告口径、授权字段修正及此前冷查询优化已在制品中；实际新 OAuth 回调仍为 `TIMEOUT`，不能将运行验收通过解释为广告账户已接入。

| 服务 | 现场活动镜像 / ID 前缀 | 两个近期有发布回执的保留回滚候选 |
| --- | --- | --- |
| Java 后端 | `ai-commerce-platform:finance-advertising-20261106` / `705cc4678025` | `finance-cold-queries-20261106`、`finance-bi-performance-20261106`（同仓库前缀） |
| Web 前端 | `ai-commerce-frontend:finance-advertising-20261106` / `34dccb8abc37` | `brush-source-summary-20261106`、`finance-gift-zero-20261105`（同仓库前缀） |

标签、完整镜像 ID、制品源提交 `86903734810c9396538ac4f962466e9c90630bc7`、配置保留和 V147 已核对。发布标签的源码另包含后续 helper 修正与验收文档，制品映射见发布回执。仓库已补齐 V147；11 月 5 日记录的源码缺口按当时语境阅读。本轮没有执行回滚，操作前仍须验证届时的数据库与制品兼容性。后续发布更新本表及文档中心，并重新核对容器引用；通用保护规则变化时再更新 AGENTS。

相关生产路径：

- 当前后端和前端回执：`/opt/heyi/releases/finance-advertising-20261106/`。
- 当前源码与文档：`/opt/heyi/source/current`，版本目录 `/opt/heyi/source/releases/v2026.11.6.3/`；与历史 Compose 的 `/opt/heyi/current` 分别使用。逐文件源码核验清单位于 `/opt/heyi/source/manifests/v2026.11.6.3.json`。
- 回滚回执：`/opt/heyi/releases/finance-cold-20261106-38d2/`、`finance-bi-performance-20261106-38d2/`、`project-sync-20261106/` 与 `finance-gift-zero-20261105/`。旧 helper 目录继续按运行引用保留。
- 共享广告凭据：`/opt/heyi/shared/finance-ads/`，通过只读挂载提供，文档只记录位置。
- 其他旧发布目录可能仍提供 Compose、辅助脚本或配置依赖，不能按日期批量删除。

本次 app 和 frontend 分别引用 252、240 份 Compose 文件，均在上一版本各自链尾追加本次发布的 `compose.<容器名>.json` 与 `compose.<容器名>.ports.yaml`；操作前仍读取真实有序标签。已恢复 291 项同步计划，检查点未重置；09:31 检查本次计划标记、贴单与千易预留残留均为 0。共享发布锁为 `/run/lock/heyi-application-release.lock`。证书续期、Aegis、HR 路由及备份恢复能力等仍按[接手事项](server-handoff.md)跟进。

广告连接待办：新回调已记录 `FAILED / TIMEOUT`。无凭据探测显示默认解析连接超时，可信 DNS 地址的 TLS 握手在主机和应用容器中均被重置，具体阻断位置待核实。先恢复官方接口的可验证 HTTPS 通道，再完成新授权；不能通过关闭证书校验、盲目重放授权码或宣称已接入处理。

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

本项目近期采用核对线上基线后，定向替换 JAR 类族或前端文件的发布方式。历史局部编译参数不完全统一，不能假定全量构建包与当前线上镜像逐字节相同。

1. 明确本次修改范围和兼容性；读取实际活动镜像、JAR/静态文件哈希、运行环境与迁移状态。按改动运行单元、隔离数据库、前端及必要业务验证。
2. 准备候选制品，比较原始源码与线上基线。局部替换时验证所有无关 JAR 条目/静态文件仍一致；全量构建时明确差异并验证完整功能。
3. **构建叠加镜像之前**运行压缩保护：

   ```bash
   python3 scripts/compact-release-image.py --prepare SOURCE UNIQUE_TARGET
   ```

   `SOURCE` 替换成实际基线镜像，`UNIQUE_TARGET` 是本次唯一压缩候选标签。使用返回 JSON 的不可变 `baseImage` 作为 Dockerfile 的 `FROM`，不能再用可能变动的旧标签绕过检查。超过 8 层或 600 MiB 压缩体积时工具要求压缩验证，保留文件系统和镜像元数据。
4. 使用已验证的停领任务/等待租约释放流程。财务同步、成本、千易及贴单工作器分别核对，不用删除队列、重置游标、放宽限流来“清空任务”。保存必要的短期恢复状态，保留原计划及冷却。
5. 从实际 Compose 文件链激活候选，保持凭据、环境、网络、挂载、容量及日志配置；不能用仓库默认 Compose 覆盖线上运行配置。
6. 核对应用健康、迁移、制品哈希、普通/gzip 前端资源、主站与受保护接口、业务抽样，再恢复原同步计划，释放本次工作器预留。
7. 保存小型验证回执，完成清理并记录活动/回滚镜像。

`scripts/release/release-*.py` **不是通用一键发布工具**。例如原始模板 helper 从 `/opt/heyi/releases/finance-brush-template-20261105/` 导入另一份脚本，许多后续 helper 也复用早期发布目录。清理后的构建目录缺少 QA/基线输入，不能直接重跑 `deploy`。下一次修改应先准备完整可审阅候选和依赖，不能只更改历史脚本日期后执行。

## 回滚

- 按本次激活回执及实际 Compose 文件链，恢复该服务的已验证镜像；先确认数据库迁移与旧镜像兼容。
- 数据库、业务卷、密钥、同步游标、已发送操作和官方写入不会随镜像回滚撤销。不能用旧数据库覆盖或删除业务记录来让页面“回到以前”。
- 前端单独发布优先使用其独立回滚镜像，确认静态资源/缓存版本；不要顺带重启无关业务服务。
- 回滚后重复健康、鉴权、文件哈希和相关业务核对，恢复原任务计划，并更新小型回执。

## 存储与发布后清理

沿用[2026-11-02 存储规则](../storage-cleanup-20261102.md)：

- 不新增持久应用日志或诊断日志。已记录的生产核心容器日志驱动为 `none`；不能为排障长期改成文件日志。优先健康、状态表、限量只读查询和短期输出。
- 保留活动镜像和两个已验证近期回滚版本，以及所有被容器引用的镜像及无关服务镜像。只在验收后删除明确无引用的旧应用/前端标签。
- 不手动删除 Docker/containerd 目录，不做 volume prune，不导出运行中容器或挂有业务卷的容器。
- 清理本次暂存 JAR、类文件、前端副本、上传包、测试库/账号及私有测试环境文件。保留运行中 Compose、必要发布 helpers、小型回执、业务文件与配置。
- 自动成本重算只更新当前结果，不再追加完整前后 JSON；人工配置和明确导入审计继续保留。原始财务账本不能作为存储优化对象删除。

## 备份与恢复

11 月 5 日核查时备份失败且只见一份归档；11 月 6 日例行任务已于 03:51:10 成功退出，当前保留 11 月 3 日和 11 月 6 日两份归档。本次发布未触发备份或恢复，也未重测归档完整性、隔离恢复及异地副本。生效 drop-in、退出码含义与容量门槛见[备份现场交接](server-handoff.md#9-备份恢复与容量边界)。

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
| 千易单据不进收货合计 | 真实 receiveTime、完成状态、非删除、对应退回卖家签收证据；未知状态不补猜 |
| 导出与人民币看板不同币种 | 费用矩阵始终导出原币分表；不是汇率转换失败 |
| 模型失败或视频不完整 | 提供方状态、Runtime/模型凭据、限量任务事件、素材权限及已保存恢复状态，避免重复付费提交 |

排障仍需遵守 [AGENTS.md](../../AGENTS.md) 的数据、限流和存储约束。

## 上游历史验收补录（2026-11-06）

11 月 5 日的现场检查及源证据保留原样。随后同步的[GIFT 发布记录](../releases/finance/finance-gift-zero-20261105.md)描述该日 18:53—18:55 的最终验收与当时回滚清理，属于历史发布事实；11 月 6 日当前镜像与回滚保留以上表及本次发布回执为准。

新增日期修复、GIFT 一次性扫描、原生压缩和索引维护约束见[当前财务规则](../business/finance/current-rules.md)。不要重新启动已完成的 GIFT 扫描；历史日期修复是否结束以最新凭据为准，不在本次拉取合并中执行维护程序。
