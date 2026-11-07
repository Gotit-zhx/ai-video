# 历史发布与验收记录

更新日期：2026-11-07。按业务领域保存仍有追溯价值的发布、故障修复和验收证据。记录中的活动镜像、数量、测试与未完成项都适用于该次时间，不代表今天的部署。当前版本及发布规则见[运维指南](../guides/operations.md)，业务入口见[业务说明](../business/README.md)。

生产固定引用的最新十一份规则/验收文档仍位于 `docs/` 根目录，见[当前入口](../README.md#当前生产规则与验收)。历史 helper 可能仍被线上 Compose 链依赖，本次只移动文档。

## 财务

| 文档 | 内容 |
| --- | --- |
| [finance-ad-vmess-validation-20261107.md](finance/finance-ad-vmess-validation-20261107.md) | 单进程代理候选；第一条节点失败，第二条节点通过本机与 ECS HTTPS 验证，永久接入待发布 |
| [finance-ad-connection-repair-20261107.md](finance/finance-ad-connection-repair-20261107.md) | 广告连接候选、63 项回归、临时真实令牌交换及正式环境恢复；长期代理待配置 |
| [finance-advertising-20261106.md](finance/finance-advertising-20261106.md) | v2026.11.6.3 广告统计上线、运行验收及真实授权连接待办（11 月 7 日激活） |
| [finance-bi-performance-20261106.md](finance/finance-bi-performance-20261106.md) | BI 两级缓存、成本队列优化与上线验收 |
| [finance-cold-queries-20261106.md](finance/finance-cold-queries-20261106.md) | 冷查询分段聚合、后台提交刷新及 v2026.11.6.2 验收 |
| [finance-atc-date-audit-20261105.md](finance/finance-atc-date-audit-20261105.md) | ATC October date-basis audit — 2026-11-05 |
| [finance-gift-zero-20261105.md](finance/finance-gift-zero-20261105.md) | GIFT 固定零成本与金额说明（2026-11-05） |
| [finance-local-month-release-20261105.md](finance/finance-local-month-release-20261105.md) | Local-calendar queries and storage compression — 2026-11-05 |
| [finance-report-basis-20261105.md](finance/finance-report-basis-20261105.md) | 官方账单日期与返程状态修复（2026-11-05） |
| [qianyi-receipt-display-20261105.md](finance/qianyi-receipt-display-20261105.md) | Qianyi missing receipt display — 2026-11-05 |

| 文档 | 内容 |
| --- | --- |
| [finance-yuxi-ph-cost-reconciliation-20261029.md](finance/finance-yuxi-ph-cost-reconciliation-20261029.md) | YUXI-PH 2026 年 9 月成本差异核对 |
| [finance-sync-queue-20261030.md](finance/finance-sync-queue-20261030.md) | 店铺同步与贴单汇总解耦 |
| [finance-sync-priority-20261103.md](finance/finance-sync-priority-20261103.md) | Finance sync priority and throughput — 2026-11-03 |
| [finance-sync-locks-20261030.md](finance/finance-sync-locks-20261030.md) | 订单同步死锁修复 |
| [finance-sku-identities-20261026.md](finance/finance-sku-identities-20261026.md) | 待补齐订单商品识别与成本核对 |
| [finance-shop-month-20261103.md](finance/finance-shop-month-20261103.md) | Shop month status and compact controls — 2026-11-03 |
| [finance-settlement-export-20261029.md](finance/finance-settlement-export-20261029.md) | 结算中心导出与成本日期筛选 |
| [finance-scope-currency-20261029.md](finance/finance-scope-currency-20261029.md) | 财务切换范围后金额不显示修复 |
| [finance-recovery-qianyi-alias-20261102.md](finance/finance-recovery-qianyi-alias-20261102.md) | 财务恢复、千易退款与店铺别名（2026-11-02） |
| [finance-recovery-operating-costs-20261027.md](finance/finance-recovery-operating-costs-20261027.md) | Finance recovery and operating-cost reporting — 2026-10-27 |
| [finance-query-reuse-20261103.md](finance/finance-query-reuse-20261103.md) | Finance query reuse and detail pagination — 2026-11-03 |
| [finance-query-performance-20261024.md](finance/finance-query-performance-20261024.md) | 财务查询提速验收 · 2026-10-24 |
| [finance-qianyi-company-and-switching-20261030.md](finance/finance-qianyi-company-and-switching-20261030.md) | Finance scope switching, Qianyi source views and performance — 2026-10-30 |
| [finance-qianyi-auth-20261030.md](finance/finance-qianyi-auth-20261030.md) | 千易鉴权核对（2026-10-30） |
| [finance-pending-costs-20261026.md](finance/finance-pending-costs-20261026.md) | 财务订单成本待补齐筛选 |
| [finance-order-costs-20261023.md](finance/finance-order-costs-20261023.md) | 商家 SKU 商品标准成本 |
| [finance-metric-defaults-20261024.md](finance/finance-metric-defaults-20261024.md) | 财务首屏币种与卡片说明 · 2026-10-24 |
| [finance-latency-20261030.md](finance/finance-latency-20261030.md) | Finance latency investigation and refresh repair — 2026-10-30 |
| [finance-label-zero-20261030.md](finance/finance-label-zero-20261030.md) | 贴单明确零金额及状态简化 |
| [finance-label-pages-20261030.md](finance/finance-label-pages-20261030.md) | 月度贴单账单明细切换与筛选 |
| [finance-label-invoices-20261030.md](finance/finance-label-invoices-20261030.md) | 月度贴单账单：异步导入与订单自动绑定 |
| [finance-excel-basis-20261029.md](finance/finance-excel-basis-20261029.md) | 财务 BI 与母婴 TK 月报口径对齐 |
| [finance-default-all-20261029.md](finance/finance-default-all-20261029.md) | 财务默认全部币种修复 |
| [finance-date-search-20261027.md](finance/finance-date-search-20261027.md) | Order placement visibility and exact-number lookup — 2026-10-27 |
| [finance-cost-sources-20261026.md](finance/finance-cost-sources-20261026.md) | 分组成本文件与独立手动成本 |
| [finance-calendar-month-20261103.md](finance/finance-calendar-month-20261103.md) | Complete previous-calendar-month synchronization — 2026-11-03 |
| [finance-cache-query-performance-20261102.md](finance/finance-cache-query-performance-20261102.md) | Finance query caching and completion — 2026-11-02 |
| [finance-auto-sync-and-size-chart-20261029.md](finance/finance-auto-sync-and-size-chart-20261029.md) | 财务自动同步与商品尺码表修复 |
| [2026-10-26-finance-settlement-basis-and-speed.md](finance/2026-10-26-finance-settlement-basis-and-speed.md) | 财务结算口径、计算状态与查询优化 |
| [2026-10-24-finance-shop-deletion.md](finance/2026-10-24-finance-shop-deletion.md) | 财务店铺移除与数据清理 |
| [2026-10-23-finance-settled-cost-export.md](finance/2026-10-23-finance-settled-cost-export.md) | 财务已结算订单成本与详细 Excel 导出 |
| [2026-10-23-finance-profit-and-sample-orders.md](finance/2026-10-23-finance-profit-and-sample-orders.md) | 财务利润、取消成本及免费样品单说明 |
| [2026-10-23-finance-product-skus.md](finance/2026-10-23-finance-product-skus.md) | 财务商品 SKU 列与成本跳转 |
| [2026-10-23-finance-cost-sku-format.md](finance/2026-10-23-finance-cost-sku-format.md) | 成本币种、SKU 格式识别与 SPU 四列卡片 |
| [2026-10-23-finance-cost-orders-proxy.md](finance/2026-10-23-finance-cost-orders-proxy.md) | 订单成本接口空指针修复 |
| [2026-10-22-finance-record-presentation.md](finance/2026-10-22-finance-record-presentation.md) | 财务明细日期、状态和平台标识 |
| [2026-10-22-finance-record-compact.md](finance/2026-10-22-finance-record-compact.md) | 财务表格紧凑显示与真实事件时间 |
| [2026-10-22-finance-groups-sharing-and-refunds.md](finance/2026-10-22-finance-groups-sharing-and-refunds.md) | 2026-10-22 财务分组、共享权限与退款金额修复 |
| [2026-10-22-finance-cost-workspace.md](finance/2026-10-22-finance-cost-workspace.md) | 成本管理与费用台账 |
| [2026-10-22-finance-cost-shops.md](finance/2026-10-22-finance-cost-shops.md) | 成本表与店铺商品关联简化 |
| [2026-10-22-finance-ad-authorization-links.md](finance/2026-10-22-finance-ad-authorization-links.md) | 2026-10-22 广告授权改为复制链接 |
| [2026-10-21-finance-performance-and-image-recovery.md](finance/2026-10-21-finance-performance-and-image-recovery.md) | 2026-10-21 财务读取与商品图片结果恢复 |
| [2026-10-21-finance-parallel-sync-and-first-load.md](finance/2026-10-21-finance-parallel-sync-and-first-load.md) | 2026-10-21 财务并发同步与首次加载 |

## 电商与图片

| 文档 | 内容 |
| --- | --- |
| [watermark-pre-detection-20260909.md](commerce/watermark-pre-detection-20260909.md) | 深灰商品图前置水印漏判修复 |
| [transparent-artwork-auto-publish-20260915.md](commerce/transparent-artwork-auto-publish-20260915.md) | 透明印花生成与自动草稿上传实测（2026-09-15） |
| [tiktok-upload-normal-20260910.md](commerce/tiktok-upload-normal-20260910.md) | TikTok Shop 上传补齐与验收（2026-09-10） |
| [tiktok-size-order-20261028.md](commerce/tiktok-size-order-20261028.md) | TikTok 上传尺码顺序 |
| [tiktok-size-chart-20260910.md](commerce/tiktok-size-chart-20260910.md) | TikTok 尺码表自动上传（2026-09-10） |
| [tiktok-product-video-upload-20260910.md](commerce/tiktok-product-video-upload-20260910.md) | TikTok 商品视频上传契约与实现 |
| [tiktok-description-images-20260910.md](commerce/tiktok-description-images-20260910.md) | TikTok 商品详情图片（2026-09-10） |
| [tiktok-default-warehouse-20261023.md](commerce/tiktok-default-warehouse-20261023.md) | TikTok 默认发货仓库修复（2026-10-23） |
| [tiktok-cross-border-20260910.md](commerce/tiktok-cross-border-20260910.md) | TikTok Shop 跨境商品发布：官方依据与测试边界 |
| [tiktok-core-authorization-20260910.md](commerce/tiktok-core-authorization-20260910.md) | TikTok Shop 核心账号授权接入（进行中） |
| [tiktok-collector-category-20260910.md](commerce/tiktok-collector-category-20260910.md) | TikTok 采集类目与详情图补全 0.2.4 |
| [template-shop-adaptation-20260914.md](commerce/template-shop-adaptation-20260914.md) | 模板按目标店铺自动适配 |
| [template-palette-and-artwork-thumbnails-20261020.md](commerce/template-palette-and-artwork-thumbnails-20261020.md) | 模板配色、可选主图风格与印花缩略图 |
| [template-batch-selection-20260914.md](commerce/template-batch-selection-20260914.md) | 模板与新建裂变的选择关系 |
| [strict-transparent-prints-20260915.md](commerce/strict-transparent-prints-20260915.md) | 裂变印花必须原生透明（2026-09-15） |
| [shopee-video-normalize-20260921.md](commerce/shopee-video-normalize-20260921.md) | Shopee 商品视频预处理（2026-09-21） |
| [shopee-title-recovery-20260921.md](commerce/shopee-title-recovery-20260921.md) | 标题失败后的旧发布任务恢复 |
| [shopee-title-limits-20260921.md](commerce/shopee-title-limits-20260921.md) | Shopee 标题按目标店铺适配（2026-09-21） |
| [shopee-size-chart-20260921.md](commerce/shopee-size-chart-20260921.md) | 去水印阶段识别尺码表，并提取上传（2026-09-21） |
| [shopee-product-video-upload-20260909.md](commerce/shopee-product-video-upload-20260909.md) | 采集视频随商品上传 Shopee |
| [shopee-original-price-20260921.md](commerce/shopee-original-price-20260921.md) | Shopee 原价换汇取值修复 |
| [shopee-logistics-retry-20260921.md](commerce/shopee-logistics-retry-20260921.md) | Shopee logistics read recovery |
| [shopee-live-switch-20260921.md](commerce/shopee-live-switch-20260921.md) | Shopee production access — 2026-09-21 |
| [shopee-cotton-default-20260921.md](commerce/shopee-cotton-default-20260921.md) | Shopee 默认材质（2026-09-21） |
| [publish-transport-retry-20260915.md](commerce/publish-transport-retry-20260915.md) | 草稿上传：创建前网络故障自动重试 |
| [product-watermark-upload-20260908.md](commerce/product-watermark-upload-20260908.md) | 批量商品去水印上传：用户要求与实施状态 |
| [product-watermark-queue-20260909.md](commerce/product-watermark-queue-20260909.md) | 当前生效规则（最新用户确认） |
| [product-sync-retry-20260921.md](commerce/product-sync-retry-20260921.md) | 上传成功后的详情同步恢复 |
| [product-publish-simple-20260909.md](commerce/product-publish-simple-20260909.md) | 一键上传表单简化（2026-09-09） |
| [product-publish-fx-20260909.md](commerce/product-publish-fx-20260909.md) | 自动换汇与直接上传 |
| [product-capture-listing-20261010.md](commerce/product-capture-listing-20261010.md) | 商品采集标题与刊登模板 |
| [product-bulk-timeout-20260910.md](commerce/product-bulk-timeout-20260910.md) | 批量上传等待修复（2026-09-10） |
| [product-bulk-shop-only-20260909.md](commerce/product-bulk-shop-only-20260909.md) | 批量上传只选择店铺（2026-09-09） |
| [printed-person-color-fix-20260915.md](commerce/printed-person-color-fix-20260915.md) | 印花人物导致误取色（2026-09-15） |
| [print-render-binding-precision-20260916.md](commerce/print-render-binding-precision-20260916.md) | 印花模板保存后被误判为已改动 |
| [matte-visibility-fix-20260915.md](commerce/matte-visibility-fix-20260915.md) | 套版母版丢失误报（2026-09-15） |
| [main-image-timeout-20261019.md](commerce/main-image-timeout-20261019.md) | Poster Analysis Startup Timeout |
| [main-image-styles-release-20261019.md](commerce/main-image-styles-release-20261019.md) | Optional Main Image Styles |
| [main-image-preserve-20261019.md](commerce/main-image-preserve-20261019.md) | Product-Free Template References |
| [main-image-direct-output-20261019.md](commerce/main-image-direct-output-20261019.md) | Direct Main Image Output |
| [main-image-colors-oss-20261019.md](commerce/main-image-colors-oss-20261019.md) | Style Colors, OSS Archives, and Final Covers |
| [listing-size-chart-template-20261013.md](commerce/listing-size-chart-template-20261013.md) | 采集商品尺码模板修复 |
| [img2-artwork-retries-color-motion-20260915.md](commerce/img2-artwork-retries-color-motion-20260915.md) | IMG2.0 透明印花重试与 SKU 取色动效（2026-09-15） |
| [image-operation-oss-20261020.md](commerce/image-operation-oss-20261020.md) | Image Recovery Storage and Server Cleanup |
| [image-generation-queue-20260909.md](commerce/image-generation-queue-20260909.md) | 生图连接等待超时与自动回队 |
| [grok-shared-scene-20260916.md](commerce/grok-shared-scene-20260916.md) | 提取商品图背景复用 |
| [grok-image-live-20260921.md](commerce/grok-image-live-20260921.md) | Grok image configuration synchronization |
| [grok-image-fallback-20260909.md](commerce/grok-image-fallback-20260909.md) | Grok 图片备用路线 |
| [grok-drape-tracking-fix-20260917.md](commerce/grok-drape-tracking-fix-20260917.md) | 搭放印花定位与套版动画修复 |
| [grok-drape-reuse-20260916.md](commerce/grok-drape-reuse-20260916.md) | 自然搭放母图复用 |
| [grok-drape-default-fix-20260917.md](commerce/grok-drape-default-fix-20260917.md) | 新建裂变未启用自然搭放：原因与修复 |
| [drape-stall-20260921.md](commerce/drape-stall-20260921.md) | 搭放停止排查 |
| [drape-resume-20260921.md](commerce/drape-resume-20260921.md) | 裂变任务恢复完成 |
| [drape-master-constraints-20260921.md](commerce/drape-master-constraints-20260921.md) | 模板优先的搭放母图输入 |
| [drape-color-template-20260921.md](commerce/drape-color-template-20260921.md) | 搭放彩色模板自动中性化 |
| [direct-publish-20260915.md](commerce/direct-publish-20260915.md) | 裂变直接发布（2026-09-15） |
| [cross-platform-publish-20260910.md](commerce/cross-platform-publish-20260910.md) | Shopee / TikTok Shop 双向搬运（2026-09-10） |
| [creative-motion-colors-retry-20260914.md](commerce/creative-motion-colors-retry-20260914.md) | 裂变动效、单图重试与原商品颜色（2026-09-14） |
| [creative-flow-real-test-20260908.md](commerce/creative-flow-real-test-20260908.md) | 真实创作流程实测 · 2026-09-08 |
| [commerce-release-20260920.md](commerce/commerce-release-20260920.md) | 商品工作台更新（2026-09-20） |
| [commerce-color-repair-20260916.md](commerce/commerce-color-repair-20260916.md) | 2026-09-16 换色与重复图片修复 |
| [color-foreground-fix-20260915.md](commerce/color-foreground-fix-20260915.md) | SKU 取色、已处理素材优先与动画区修复 |
| [collector-video-dedup-20260909.md](commerce/collector-video-dedup-20260909.md) | 商品视频重复采集修复（0.2.2） |
| [collector-login-video-20260909.md](commerce/collector-login-video-20260909.md) | 采集器账号登录与视频归档 |
| [collector-local-environment-20261027.md](commerce/collector-local-environment-20261027.md) | 采集插件本机异常修复 v0.2.8 |
| [collector-full-product-20260909.md](commerce/collector-full-product-20260909.md) | 完整商品采集实测（0.2.1） |
| [cloth-shadow-motion-20260915.md](commerce/cloth-shadow-motion-20260915.md) | 布色边缘与真实阶段动效 |
| [bulk-history-category-20260910.md](commerce/bulk-history-category-20260910.md) | 批量上传、分类匹配与详情操作调整（2026-09-10） |
| [batch-launch-20260914.md](commerce/batch-launch-20260914.md) | 一键裂变：创建并开始执行（2026-09-14） |
| [batch-explicit-rejection-20260914.md](commerce/batch-explicit-rejection-20260914.md) | 裂变批次明确拒绝原因（2026-09-14） |
| [batch-env-optional-20260921.md](commerce/batch-env-optional-20260921.md) | 裂变批次店铺人工环境标记改为可选 |
| [banana-default-20261019.md](commerce/banana-default-20261019.md) | Banana Default Image Generation |
| [2026-10-15-commerce-module-isolation.md](commerce/2026-10-15-commerce-module-isolation.md) | Commerce Module Isolation |

## 视频与通用生成

| 文档 | 内容 |
| --- | --- |
| [watch-parallel-analysis-20260917.md](video/watch-parallel-analysis-20260917.md) | Parallel watch video analysis |
| [watch-detailed-analysis-20260917.md](video/watch-detailed-analysis-20260917.md) | Watch 细致分析部署记录 |
| [watch-camera-motion-20260917.md](video/watch-camera-motion-20260917.md) | Watch 运镜连续帧修复 |
| [video-watch-output-20260917.md](video/video-watch-output-20260917.md) | 视频分析、模型输出与 watch 入口 |
| [video-recovery-verification-20261015.md](video/video-recovery-verification-20261015.md) | Video Recovery Verification, 2026-10-15 |
| [video-recovery-test-sync-20260916.md](video/video-recovery-test-sync-20260916.md) | 正式视频修复同步到本地测试版 |
| [video-brightness-20261020.md](video/video-brightness-20261020.md) | Natural Exposure and Fabric for Video Reference Images |
| [structure-analysis-recovery-20260917.md](video/structure-analysis-recovery-20260917.md) | 结构识别临时故障恢复 |
| [seedance-mini-20261027.md](video/seedance-mini-20261027.md) | Seedance 2.0 Mini release |
| [production-retry-storage-fix-20260917.md](video/production-retry-storage-fix-20260917.md) | 线上重试失败修复（2026-09-17） |
| [production-retry-image-audit-20260917.md](video/production-retry-image-audit-20260917.md) | 正式任务自动重试与图片加载核查（2026-09-17） |
| [preview-pipeline-continuation-20260917.md](video/preview-pipeline-continuation-20260917.md) | 图片预览及视频恢复衔接修复 |
| [image-socket-recovery-20260909.md](video/image-socket-recovery-20260909.md) | 图片断连恢复与商品视频上传（2026-09-09） |
| [image-download-retry-20260921.md](video/image-download-retry-20260921.md) | 生图结果下载恢复与自动重试（2026-09-21） |
| [image-download-failure-20260909.md](video/image-download-failure-20260909.md) | 商品图片下载失败诊断与修复（2026-09-09） |
| [generation-prompt-corrections-20260905.md](video/generation-prompt-corrections-20260905.md) | 生成提示词修正：第一批（本地，未部署） |
| [general-video-tls-20260917.md](video/general-video-tls-20260917.md) | 通用视频提交连接修复 |
| [general-video-replacement-diagnosis-20260917.md](video/general-video-replacement-diagnosis-20260917.md) | 通用生成换衣效果诊断 |
| [driver-backup-shot-deletion-20260917.md](video/driver-backup-shot-deletion-20260917.md) | 驱动备用线路与分镜删除 |
| [VIDEO-PREVIEW-INCIDENT-20261008.md](video/VIDEO-PREVIEW-INCIDENT-20261008.md) | 分镜预览与部署空间清理：2026-10-08 |

## 招聘与桌面

| 文档 | 内容 |
| --- | --- |
| [hr-windows-unicode-20261009.md](recruitment/hr-windows-unicode-20261009.md) | Windows 职位中文与旧助手授权提示 |
| [hr-visual-workspace-20260928.md](recruitment/hr-visual-workspace-20260928.md) | 招聘模块数据可视化 |
| [hr-user-access-20261009.md](recruitment/hr-user-access-20261009.md) | 人力资源账号授权、资料隔离与开放职位审批 |
| [hr-sourcing-response-20260930.md](recruitment/hr-sourcing-response-20260930.md) | 寻源匹配响应与零匹配停止 |
| [hr-rejection-template-20261008.md](recruitment/hr-rejection-template-20261008.md) | 简历审批默认婉拒话术配置修复 |
| [hr-receipt-pacing-20261014.md](recruitment/hr-receipt-pacing-20261014.md) | 招聘简历收集回归修复 |
| [hr-priority-send-20261012.md](recruitment/hr-priority-send-20261012.md) | 手动沟通优先发送 |
| [hr-managed-jobs-20260930.md](recruitment/hr-managed-jobs-20260930.md) | 正式职位列表同步 |
| [hr-login-sync-20260928.md](recruitment/hr-login-sync-20260928.md) | BOSS 登录同步与 Linux 中文显示修复 |
| [hr-jd-requirements-20261008.md](recruitment/hr-jd-requirements-20261008.md) | JD 建议自动带入与学历、年限核验 |
| [hr-greeting-score-20261008.md](recruitment/hr-greeting-score-20261008.md) | 主动邀约等待反馈、每日额度和岗位匹配度 |
| [hr-greeting-receipts-20261008.md](recruitment/hr-greeting-receipts-20261008.md) | 打招呼成功回执同步修复 |
| [hr-dingtalk-first-interview-20261008.md](recruitment/hr-dingtalk-first-interview-20261008.md) | 一面资料交接与钉钉设置 |
| [hr-desktop-installers-20261009.md](recruitment/hr-desktop-installers-20261009.md) | 招聘助手两端安装包更新 |
| [hr-boss-chat-resumes-20261012.md](recruitment/hr-boss-chat-resumes-20261012.md) | BOSS 会话附件统一入池 |
| [hr-analysis-progress-20261012.md](recruitment/hr-analysis-progress-20261012.md) | 招聘分析、发送进度与姓名补处理 |
| [desktop-download-runtime-20261030.md](recruitment/desktop-download-runtime-20261030.md) | Recruitment assistant download recovery — 2026-10-30 |
| [WINDOWS-STARTUP-20261019.md](recruitment/WINDOWS-STARTUP-20261019.md) | Windows 0.1.26 启动与窗口接管 |
| [WINDOWS-HANDOFF-20261019.md](recruitment/WINDOWS-HANDOFF-20261019.md) | Windows 启动依赖与交接核查 |
| [STORAGE-INCIDENT-20261008.md](recruitment/STORAGE-INCIDENT-20261008.md) | 招聘助手连接故障：2026-10-08 |
| [STARTUP-FOLLOWUP-20261016.md](recruitment/STARTUP-FOLLOWUP-20261016.md) | 2026-10-16：启动可靠性与 Windows 验证 |
| [PASSIVE-FOLLOWUP-20261016.md](recruitment/PASSIVE-FOLLOWUP-20261016.md) | 2026-10-16：新来信被动跟进修复 |
| [HR-SOURCING-PRECISION-20261008.md](recruitment/HR-SOURCING-PRECISION-20261008.md) | 寻源条件简化与原文核验 |
| [HR-RECOVERY-20261016.md](recruitment/HR-RECOVERY-20261016.md) | 2026-10-16 本机登录保持与招聘恢复 |
| [HR-PASSIVE-INCIDENT-20261008.md](recruitment/HR-PASSIVE-INCIDENT-20261008.md) | 被动邀约队列与职位状态：2026-10-08 |
| [HR-AUDIT-20261015.md](recruitment/HR-AUDIT-20261015.md) | 人事系统功能核查 · 2026-10-15 |
| [HR-AI-PI-20261008.md](recruitment/HR-AI-PI-20261008.md) | 招聘 AI 模型适配与生成进度 |
| [CROSS-DEVICE-20261019.md](recruitment/CROSS-DEVICE-20261019.md) | 跨设备重复收取修复 |

## 基础设施

| 文档 | 内容 |
| --- | --- |
| [devops-phase1-20261106.md](infrastructure/devops-phase1-20261106.md) | GitHub Actions 与自动化运维一期实施及验收状态 |
| [devops-phase1-acceptance-20261107.json](infrastructure/devops-phase1-acceptance-20261107.json) | 已完成的 v4 接管与 CI/通知摘要；后续闭环见 Release 附件 |
| [server-handoff-before-cicd-20261107.md](infrastructure/server-handoff-before-cicd-20261107.md) | CI/CD 接管前各次现场交接的历史快照 |
| [project-sync-20261106.md](infrastructure/project-sync-20261106.md) | 项目同步、刷单核对表跨页合计的候选验证、发布及交接记录 |
| [server-deployment-audit-20261105.md](infrastructure/server-deployment-audit-20261105.md) | 阿里云服务器只读现场核查、部署基线、证书/备份失败及运维接手边界；含脱敏 JSON 回执 |
| [ui-localization-20260921.md](infrastructure/ui-localization-20260921.md) | UI localization for marketplace operations |
| [production-operator-release-20260917.md](infrastructure/production-operator-release-20260917.md) | 测试版本发布到正式环境：角色页面与运营权限 |
| [2026-10-24-server-slimming.md](infrastructure/2026-10-24-server-slimming.md) | 服务器瘦身与旧 Linux 浏览器退役 |
