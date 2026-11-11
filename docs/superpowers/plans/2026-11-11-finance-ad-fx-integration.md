# 财务广告与历史汇率集成实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development task-by-task. Steps use checkbox syntax for tracking.

**Goal:** 完成公开资金补充、卡片去重/统一名称、当前行情显示及逐业务日历史换算。

**Architecture:** BC 费用作为独立账户日资金来源，旧利润与媒体事实保持原分类。历史汇率先逐日转换后聚合；行情卡独立请求，源文档、数据库、页面、导出共用明确口径。

**Tech Stack:** Java 21 / Spring Boot / JdbcTemplate / MySQL / Flyway；原生 JavaScript / Node / JSDOM。

**Spec:** `docs/superpowers/specs/2026-11-11-finance-ad-fx-integration.md`

## Global Constraints

- 只用公开官方接口、正确授权和真实返回；未知与零值分开。
- 仅新增 V151/V152，不改已应用迁移；DB 测试用可销毁隔离库。
- 本地 e8b0 已为独立 worktree，分支 `feature/finance-ad-history-fx`；各工作流独占文件，不并行运行 Maven。
- 共享源码生效前重新核对现场，保留其他会话的未提交内容及运行任务。

## 工作流 1：历史汇率后端

拥有 `FinanceFx`、历史源/存储辅助类、V151、`FinanceService/Repository/ProfitReport/CostReport/AdvertisingReport/Controller/Fields/ReadCache` 及对应测试。广告专项 Report/Service/Controller 由根会话集成。

- [x] 写 `FinanceHistoricalFxTest` 并确认缺日期接口的编译红证据。
- [x] 实现 `historical(id,start,end)` / `historical(id,dates)`，`Quote.convert(amount,currency,businessDate)` / `fromCny`，固定 ECB，记录真实公布日与缺口。
- [x] 修复日报与下钻丢失业务日、整段成本单率换算、对账跨日先合计、缓存与导出口径。
- [x] 以两日 10 USD、分别 7/8 CNY 的结果必须为 150 CNY 验证；周末用过去公布日，未来公布日拒绝，缺价不变零。
- [x] 隔离 MySQL 验证冻结快照、跨页及历史导出复现。

## 工作流 2：公开 BC 资金

拥有 `FinanceAdBcApi/Funding`、`FinanceAds/AdAutomation/AdFunding`、V152 及对应测试。根会话把静态 reader 接入账户核对，禁止追加到经营三主表。

- [x] 确认 BC 参数、权限码、scope150/152、财务角色、资金与授信余额的区别。
- [x] `FinanceAdBcApiTest` 六项真实红测（隔离 class 输出，不干扰共享编译）。
- [x] provider 规范化负扣费/退款符号、原币主单位、可空分项与独立税额；分页及身份验证完整。
- [x] 按实际授权去重，独立租约/检查点/退避；暂停/重授使旧请求失效；权限失败不阻塞现有广告流。
- [x] 静态 `sources(repo,scope,filter)` 与账户 `funding` 状态字段供根会话/前端读取。
- [x] 真实只读权限探测与隔离库幂等、冲突、共享 BC、空窗口和晚返回验证。

## 工作流 3：前端与文案

拥有 `finance-workspace.js/finance-ad-spend.js`、相关 CSS/UI 测试；index token 由根会话更新。

- [x] 复现每币种平铺五卡及辅助旧四卡，未假定异步双提交。
- [x] 仅一份主工作台，卡片按币种分组，辅助旧大卡改核对表；统一原 Spec 文字。
- [x] 总览独立行情卡读取 `GET /fx/market`，刷新不动 `fxSnapshot`；跨公司晚响应不能写入新公司页面。
- [x] 使用成本接口 `currency/display_amount/known_display_amount`，停止前端总额除单率；历史 metadata 显示业务/公布日。
- [x] 运行 Node/JSDOM 及总览广告跳转、多币种、竞态、失败/过期行情回归。

## 根会话集成与生效

- [x] 对广告来源按报表日应用历史 Quote；BC 仅进独立账户核对，分来源保留金额与缺失原因，导出保留汇率明细。
- [x] 串行跑必要单元与隔离数据库，再完整前端及静态引用检查，独立审查关键财务/权限/恢复边界。
- [x] 真实只读核对 API 能力、历史行情可达性、现有已入库数据；不输出业务原件或凭据。
- [x] 更新常驻业务说明和小型验收回执。
- [ ] 通过 PR 合入 develop（最终协作收尾）。
- [x] 在已协调主机窗口内核对完整运行链、WIP 和配置；独立新增迁移，再定向保存源码，由共享 watcher 安全点生效。
- [x] 正式页面验证卡片唯一、正确文案、当前行情与历史金额分离、三表/导出一致，交接实际回执及窗口释放。
