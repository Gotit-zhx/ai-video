# 文档分类与清理记录

整理日期：2026-11-05。范围为项目自有 Markdown 与两份旧 Word 手册；依赖清单、提示词、测试 PDF、业务 XLSX、运行时技能、第三方许可及 README 继续保留在其所属模块。

## 分类规则

- `docs/guides/`：开发接手、架构、配置、接口、运维及文档维护。
- `docs/business/`：按财务、电商、视频、招聘、知识库与存储归集的业务说明。
- `docs/releases/`：按领域保存仍有用的历史发布、故障与验收证据。
- `docs/research/`：来源调查、实验、限制及风险研究，明确不代表生产验收。
- `docs/design/`：设计依据及视觉参考，去除重复副本。
- `docs/history/`：源码快照、指纹清单与分支整合背景。
- 模块 README、安装/功能说明及运行时技能留在代码附近，由文档中心统一导航。
- 文档整理时由旧版 [AGENTS.md](../../AGENTS.md) 固定引用的十一份生产规则/验收文档保留原路径；后续通过文档中心导航，规则文件不再固定发布快照。

移动 214 个文件（213 份 Markdown 与 1 份源码指纹 JSON），删除下表 16 份文档（14 份 Markdown、2 份 Word），合并补齐 3 份业务说明并新增 5 份分类索引。所有旧文档路径引用同步调整；源码指纹 JSON 保持原字节。

历史发布有独立验收价值时归类保留；已被明确替代的方案、重复说明、失效续接和旧截图手册移除。不是以日期早晚一律删除。

## 已删除与替代

删除文件已从当前工作树移除，原始版本仍可通过 Git 历史追溯。表中旧路径仅作清理记录，不是有效文件链接；不保留重定向空壳。

| 原路径 | 删除依据 | 保留或整合位置 |
| --- | --- | --- |
| `DESIGN.md` | 与 experiments/ui/apple/DESIGN.md 字节完全一致，删除重复副本，保留一份视觉参考 | [Apple 界面设计参考](../design/apple.md) |
| `docs/batch-execution-next.md` | 2026-09-07 阶段待办已被后续执行实现替代，稳定约束整合到当前工作流说明 | [商品采集与创作发布工作流](../business/commerce/workflow.md) |
| `docs/product-capture-extension.md` | 以手动绑定码和初版 Dry Run 为主的旧设计，当前扩展登录与发布入口已有完整说明 | [赫逸商品一键采集（Manifest V3）](../../apps/browser-extension/README.md) |
| `docs/finance-label-brush-design-20261026.md` | 固定国家/分组贴单单价方案已停用，当前月度账单和刷单费规则集中在成本说明 | [SKU 成本与订单核算](../business/finance/costs.md) |
| `docs/finance-charge-period-performance-20261027.md` | 旧单价配置方案已停用；仍有效的有界队列和独立处理状态整合到成本说明 | [SKU 成本与订单核算](../business/finance/costs.md) |
| `docs/finance-reporting-months-20261028.md` | 旧手工月份规则已由 2026-10-29 报表日期规则替代，旧证据仍保留在既有数据字段 | [财务 BI 与母婴 TK 月报口径对齐](../releases/finance/finance-excel-basis-20261029.md) |
| `docs/finance-brush-template-20261105.md` | 同日四列模板被用户原始十一列模板明确替代；回滚镜像及发布 helper 不删除 | [Original brush-cost template — 2026-11-05](../finance-brush-original-template-20261105.md) |
| `docs/project-continuation-20260908.md` | 过期对话续接清单，含旧分支、临时授权和多次相互覆盖的阶段状态；现有接手指南已替代 | [架构与代码导航](architecture.md) |
| `docs/project-startup-cpolar-20260915.md` | 临时域名和当时登录/部署状态已失效，当前回调安全步骤保留在集成与开发说明 | [Shopee / TikTok Shop 官方授权与商品发布基线](../business/commerce/official-shop-integration.md) |
| `docs/main-image-progress-20261019.md` | 进度微说明整合到当前主图指南，过时的超时与失败任务操作由后续记录替代 | [主图风格与逐图进度](../business/commerce/main-images.md) |
| `docs/main-image-detail-progress-20261019.md` | 产品和批量页的逐图进度说明整合，移除旧会话失效与当时待处理任务状态 | [主图风格与逐图进度](../business/commerce/main-images.md) |
| `docs/general-video-reference-clip-20260917.md` | 素材截取操作已整合到视频使用说明，不再单独维护早期小版本文档 | [视频脚本生成与任务处理](../business/video/workflow.md) |
| `docs/general-video-materials-20260917.md` | 素材数量与引用规则已整合到视频使用说明，删除重复入口 | [视频脚本生成与任务处理](../business/video/workflow.md) |
| `docs/tiktok-order-completion-support-draft-20261027.md` | 未提交的对外支持草稿；来源调查结论、验证及未知边界保留在研究记录，不发送消息 | [Order completion: additional official API investigation](../research/finance/finance-completion-api-followup-20261027.md) |
| `docs/赫逸智能化平台三大功能使用手册.docx` | 2026-08-17 V1.0 旧截图手册，操作范围及状态表已由当前视频指南替代 | [视频脚本生成与任务处理](../business/video/workflow.md) |
| `docs/赫逸AI视频生产平台_三大模块使用说明书.docx` | 2026-08-20 V1.0 重复视频手册，含旧状态和预计耗时，当前以统一视频指南维护 | [视频脚本生成与任务处理](../business/video/workflow.md) |

## 失效产物引用

原有 11 个相对链接指向未随源码入库的截图/JSON/图片，已改为明确的历史路径说明，避免显示成可访问附件。没有伪造图片或重建验收结果；需要实物证据时应从原验收归档取得。

- [商品套版准确性：现状核查与改造设计](../design/mockup-accuracy-20260914.md)
- [真实创作流程实测 · 2026-09-08](../releases/commerce/creative-flow-real-test-20260908.md)
- [TikTok Shop 跨境商品发布：官方依据与测试边界](../releases/commerce/tiktok-cross-border-20260910.md)
- [GPU 实际拟合接入与胸前凹带修复](../research/garment/garment-gpu-chest-repair-20260911.md)
- [服装建模提速与精度：可落地技术核查](../research/garment/garment-gpu-method-research-20260911.md)

## 保留边界与维护

本次未修改应用代码、运行配置、发布 helpers、Compose、业务卷、账务数据、迁移、备份计划或模型/店铺凭据。最新活动与回滚版本的记录保持原有依据；被删除的是过时模板说明，不是原始十一列 XLSX、回滚镜像或服务器发布依赖。

后续新增文档应先选对应目录，在分类索引增加入口；修改行为时先改常驻说明，再补有日期及验证边界的记录。删除/移动前查调用方、Markdown 链接和纯文本路径；运行时技能与第三方资料不按普通文档迁移。

## 验证范围

检查全部保留文档的相对文件链接、生产固定路径、源码指纹 JSON、运行时技能及非文档文件边界；复核接手说明中的 Shell 示例和 Compose 静态合并。这里的检查只验证整理后的文档与静态配置，未重新执行业务测试、外部授权或生产发布。

静态检查结果：263 份项目自有 Markdown 均可从项目首页导航到达；844 个相对文件/锚点链接全部有效，代码块闭合与 Shell 语法通过。十一份生产固定路径、AGENTS、运行时技能和原始模板保留，源码指纹 JSON 与 HEAD 中原文件 SHA-256 一致；Compose 示例静态合并通过。

以上数量和检查结果对应文档分类清理阶段。其后的源码、客户端、服务及工程脚本目录重排另见[项目组织结构](project-structure.md)，不要将该阶段的“仅文档变更”扩展为整个工作区的变更范围。

## 服务器运维交接补录

2026-11-05 18:09—18:19 对项目服务器做只读现场核查，随后按现有分类补充：

- 常驻[服务器运维交接](server-handoff.md)：按主机、服务、部署与回滚、数据目录、网络证书、任务、备份和巡检分类。
- [基础设施验收记录](../releases/infrastructure/server-deployment-audit-20261105.md)及[脱敏 JSON](../releases/infrastructure/server-deployment-audit-20261105.json)：保存本次时间、方法、通过/失败/未验证边界、483 个配置路径及选定现场元数据，不覆盖历史回执。
- 首页、文档中心、发布索引、运维指南及开发交接清单：补充新入口，将上午旧版本与晚间现场版本区分，明确源码/迁移、备份、证书及正在运行的维护任务缺口。

本轮只新增/增补文档及脱敏回执，没有改动业务实现、生产配置、服务状态或数据，没有触发发布、备份、恢复或续期。原有未提交的目录整理全部保留，未执行 Git 提交、合并、打标签或推送。

检查了本轮涉及 Markdown 的相对文件链接与锚点、代码块闭合、Shell 示例语法、JSON 可解析性、容器/配置路径计数及空白格式，结果通过。Shell 仅做语法解析；没有把示例中的隧道、发布或恢复命令当作本次已执行操作。生产读取的具体结果和未验证范围以上述基础设施记录为准。

## 2026-11-06 项目同步与应用发布

通过 Gitflow 发布 `2026.11.6` 后，更新首页、文档中心、运维/开发/交接指南及[服务器现状](server-handoff.md)，新增[发布验收](../releases/infrastructure/project-sync-20261106.md)和[脱敏 JSON](../releases/infrastructure/project-sync-20261106.json)。旧 11 月 5 日快照与只读核查事实保留，避免将它们的镜像、源码缺口和备份失败继续当作当前状态。

本阶段包含实际应用激活、配置保留验证、隔离数据库测试及清理，具体通过、跳过、首次停领恢复和未验证范围见当日回执；不能套用前述文档整理阶段“未修改生产”的范围说明。生产私有环境及业务目录没有随源码重排，完整源码快照通过独立入口与发布标签关联。
