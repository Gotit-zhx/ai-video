# Shopee 店铺广告消耗 Implementation Plan

> 执行方式：本会话逐步实现并测试；用户已明确要求补充既有三表，无需重新设计报表。

**Goal:** 接入 Shopee 官方店铺 CPC 日消耗，自动归属店铺/分组并复用三表与历史汇率。

**Architecture:** 独立应用设置/授权/令牌与独立费用事实；后台 worker 保留租约和失败窗口。官方店铺日总额仅进入投放分析，不进入利润。新授权只依赖官方管理员操作。

**Tech Stack:** Java 21, Spring Boot/JDBC, MySQL/Flyway, 原生 JavaScript。

**Spec:** `docs/superpowers/specs/2026-11-11-shopee-ad-spend.md`

## 验证步骤

- [x] 先以 `FinanceShopeeAdsApiTest` 覆盖官方日期、数组 response、warning、重复日、缺值、0/退款与精度，观察失败后实现适配。
- [x] 新增 V154 的 grants/daily 表（原未应用 V153 候选改号）及独立服务端配置。通过 `FinanceShopeeAdsIntegrationTest` 验证公司隔离、独立授权/刷新、稳定费用身份、租约撤销、历史保留与错误退避。
- [x] 以 `FinanceAdSpendReportTest` 验证 Shopee 官方日总额和导入不双计、店铺/分组同源、未知分项及业务日汇率；接入独立 sources，不写利润事实。
- [x] 通过独立 Shopee UI 模块展示授权与同步状态；新增 JSDOM 用例验证只读、公司切换及导出三表来源。更新业务说明和配置示例。
- [x] 运行相关 Java/隔离数据库/UI/资源检查，独立代码审查；只提交本次支持分支并创建 develop PR。主机操作须重新协调、双锁并显式处理新迁移，申请未通过时不声称真实数据已补齐。

2026-11-12 已完成正式配置、V154 和共享服务器源码激活；真实金额补齐仍待店铺管理员完成新应用授权。首次回补优先上个完整月，再本月和更早日期。历史开发验证见 `docs/releases/finance/finance-shopee-ads-20261111.md`，当前激活与待办见 `docs/releases/finance/finance-shopee-ads-activation-20261112.md`。
