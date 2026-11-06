# 项目文档中心

更新日期：2026-11-06。文档按用途和业务归类，当前说明、历史验收、设计和实验分别阅读。代码与配置是实现依据，历史测试或旧镜像名称不能直接解释为今天的部署状态。

## 开发接手

| 目的 | 入口 |
| --- | --- |
| 项目范围与第一次启动 | [项目首页](../README.md) |
| 交接资产、已知缺口及待确认事项 | [开发交接清单](guides/handoff.md) |
| 目录归类及模块组织图 | [项目组织结构](guides/project-structure.md) |
| 语言、框架及依赖版本 | [技术栈清单](guides/technology-stack.md) |
| 服务与数据流、代码位置 | [架构说明](guides/architecture.md) |
| 本地环境、配置、调试与测试 | [开发指南](guides/development.md) |
| 登录、权限及接口查找 | [接口导航](guides/api.md) |
| 发布、回滚、备份和排障 | [运维指南](guides/operations.md) |
| 服务器现状、目录、证书、备份、维护任务及接管待办 | [服务器运维交接](guides/server-handoff.md)（含 2026-11-06 上线更新） |
| 桌面构建与备份约定 | [桌面构建说明](guides/desktop-build-backup.md) |

## 按用途查找

| 分类 | 内容 |
| --- | --- |
| [业务说明](business/README.md) | 财务、电商、视频、招聘、知识库、文件存储 |
| [发布与验收](releases/README.md) | 按财务、电商、视频、招聘、基础设施分类的历史记录 |
| [研究与实验](research/README.md) | 服装重建、水印、财务时间来源、招聘风险与视频实验 |
| [设计参考](design/README.md) | 视觉参考及工作流设计依据 |
| [来源与整合历史](history/README.md) | 源码快照、SHA-256 清单、分支与迁移来源 |
| [文档清理记录](guides/documentation-maintenance.md) | 目录约定、删除依据、替代资料和维护规则 |

## 当前生产规则与验收

以下十一份业务规则/验收文件保留根目录原路径，由本索引统一导航；它们描述各自日期的业务和验收，不固定当前部署。**2026-11-06 14:30 已发布后端 `2026.11.6.1`：后端为 `finance-bi-performance-20261106`，前端保持 `brush-source-summary-20261106`**；源码、测试、回滚和清理见[性能发布验收](releases/finance/finance-bi-performance-20261106.md)，运行入口及遗留事项见[服务器运维交接](guides/server-handoff.md)。生产操作先读[运维指南](guides/operations.md)和 [AGENTS.md](../AGENTS.md)。

| 主题 | 固定入口 |
| --- | --- |
| 原始十一列刷单费模板 / 当日上午验收 | [finance-brush-original-template-20261105.md](finance-brush-original-template-20261105.md) |
| 全部源订单与已结算视图 | [finance-source-orders-20261105.md](finance-source-orders-20261105.md) |
| 分组/平台/店铺筛选级联 | [finance-group-shops-20261104.md](finance-group-shops-20261104.md) |
| 相同成本去重与冲突拒绝 | [finance-cost-dedupe-20261104.md](finance-cost-dedupe-20261104.md) |
| 费用矩阵原币分表及异步导出 | [finance-fee-export-20261104.md](finance-fee-export-20261104.md) |
| 千易退回卖家签收证据 | [qianyi-reverse-phrases-20261104.md](qianyi-reverse-phrases-20261104.md) |
| 退回商品参考成本合计 | [qianyi-return-total-20261104.md](qianyi-return-total-20261104.md) |
| 共享图案 SKU 与筛选成本合计 | [sku-shared-cost-total-20261104.md](sku-shared-cost-total-20261104.md) |
| SKU 命名与早期验收边界 | [product-sku-naming-20261104.md](product-sku-naming-20261104.md) |
| 实际收货月份及同步恢复 | [finance-qianyi-receipt-20261103.md](finance-qianyi-receipt-20261103.md) |
| 存储、日志、镜像及备份约束 | [storage-cleanup-20261102.md](storage-cleanup-20261102.md) |

## 模块就近文档

独立服务、桌面端与扩展的 README、安装和安全说明继续与源码放在一起；它们的运行范围和账户边界分别核对。

| 文档 | 内容 |
| --- | --- |
| [README.md](../apps/automation-agent/README.md) | 赫逸自动化助手 |
| [README.md](../apps/browser-extension/README.md) | 赫逸商品一键采集（Manifest V3） |
| [README.md](../apps/chrome-profile-manager/README.md) | Chrome Profile 管理器 |
| [README.md](../services/document-preview/README.md) | Private document preview converter |
| [README.md](../services/erp/README.md) | HOYEE ERP |
| [REVIEW.md](../services/erp/REVIEW.md) | HOYEE ERP 平台申请资料草稿 |
| [README.md](../frontend/assets/showcase/README.md) | Showcase assets |
| [README.md](../experiments/garment-reconstruction/README.md) | 服装重建方法验证原型 |
| [API.md](../services/household/API.md) | 拾间 · 家务系统 API contract |
| [README.md](../services/household/README.md) | 拾间 · 手机家务管理 |
| [README.md](../apps/hr-desktop/README.md) | 赫逸招聘助手 |
| [FEATURES.md](../apps/hr-desktop/client-workbench/FEATURES.md) | 原助手功能保留核对 |
| [INSTALL.md](../apps/hr-desktop/client-workbench/INSTALL.md) | Ai boss 安装与更新 |
| [README.md](../apps/hr-desktop/client-workbench/README.md) | 原客户端改造实验 |
| [README.md](../apps/hr-desktop/client-workbench/assets/README.md) | Ai boss |
| [README.md](../services/recruitment/README.md) | Linux CDP connector installation |
| [SESSION-SAFETY.md](../services/recruitment/SESSION-SAFETY.md) | Recruitment session investigation — 2026-09-28 |
| [README.md](../services/watermark-detector/README.md) | 本地水印检测（商品图原型） |

## 运行时技能与第三方资料

以下技能/参考由运行时使用，保留原目录及内部相对关系，不按历史说明清理。

| 文档 | 内容 |
| --- | --- |
| [SKILL.md](../apps/automation-agent/bundled-skills/yue-bill-parallel-10/SKILL.md) | Yue Bill Parallel |
| [architecture.md](../apps/automation-agent/bundled-skills/yue-bill-parallel-10/references/architecture.md) | Integrated architecture |
| [lazada-bill.md](../apps/automation-agent/bundled-skills/yue-bill-parallel-10/references/lazada-bill.md) | Lazada Bill |
| [parallel-architecture.md](../apps/automation-agent/bundled-skills/yue-bill-parallel-10/references/parallel-architecture.md) | Parallel architecture |
| [shopp-bill.md](../apps/automation-agent/bundled-skills/yue-bill-parallel-10/references/shopp-bill.md) | Shopp Bill |
| [status-contract.md](../apps/automation-agent/bundled-skills/yue-bill-parallel-10/references/status-contract.md) | Status and adapter contract |
| [tk-bill-cn.md](../apps/automation-agent/bundled-skills/yue-bill-parallel-10/references/tk-bill-cn.md) | TK Bill CN |
| [tk-bill.md](../apps/automation-agent/bundled-skills/yue-bill-parallel-10/references/tk-bill.md) | TK Bill |
| [SKILL.md](../services/pi-runtime/skills/watch/SKILL.md) | Watch video analysis |

`frontend/vendor/`、桌面 `vendor/` 中的第三方 README 与 LICENSE/NOTICE，以及测试夹具、requirements、约束文件和提示词，均作为依赖/运行输入保留。

新增业务规则同步修改对应说明与分类索引；归类、删除及验证结果见[清理记录](guides/documentation-maintenance.md)。
