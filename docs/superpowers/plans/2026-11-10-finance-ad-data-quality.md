# 财务广告数据质量修复实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 按用户提供的财务BI业务说明，纠正广告空报表与授权范围的误导展示，保留暂停语义并阻止重复计费和空刷新擦除已知费用。

**Architecture:** 保留现有平台事实、账户独立授权、店铺匹配和财务聚合链。前端根据既有coverage.records区分空返回、合计零、未知和部分覆盖；后端加强账户生命周期、导入及原子发布校验，不改利润或GMV叠加规则。

**Tech Stack:** Java 21 / Spring / MySQL 8.4 / JUnit / JSDOM。

**Spec:** 用户2026-11-10提供的财务BI功能说明中所有广告条款；`docs/business/finance/advertising.md`。

## Global Constraints

- 以正式v2026.11.10.1的生产合并22e659f为基线，保留两项千易修复。
- 保护f6fc工作区未提交文档；在已有干净附属工作区的hotfix分支实施，不混入文档审计变更。
- Shop与Marketing独立授权，公司隔离、凭据密文和现有AAD保持。
- 不按可访问店铺猜分普通账户费用；不以当前GMV可用状态删除真实历史。
- 广告充值不是消耗；GMV归因收入不是净利润。GMV与账单/普通报表重叠未证实时继续单列。
- 不制造费用记录或将缺失补0；保护历史事实、同步检查点、租约fencing及既有限流。
- 生产检查/发布/补抓持两把共享锁；等待已协调的容量维护结束。
- 测试数据库仅使用CI脚本创建的可销毁空库；正式验证只保留脱敏、有界回执。

## Task 1：界面状态与授权范围

**Files:** `frontend/finance-workspace.js`；`scripts/qa/test-finance-ad-automation-ui.mjs`。
**Interfaces:** 使用现有`account.coverage[].records/covered_days/amount`与`account.stores`，不改变接口或财务事实。

- [x] 先在真实DOM夹具增加records=0、records>0且amount=0、records未知、部分日期、历史GMV和暂停用例。
- [x] 跑旧码见预期失败，日志`ui-red.log`、`ui-pause-red.log`、`ui-multi-account-red.log`保留在本次临时验收目录。
- [x] 最小呈现：空记录为“接口未返回记录”和金额—；净额0为“报表合计为0”；增加记录数与筛选下的TikTok店铺去重范围。
- [x] BASIC说明不含部分Shop创建GMV；发现成功后未关联店铺显示当前关系范围未覆盖；发现待完成/失败保持未知；保留当前不可投放账户的历史GMV。
- [x] 运行广告、授权及两项千易DOM回归，确认绿。

## Task 2：暂停、重授权和导入保护

**Files:** `FinanceAds.java`、`FinanceImport.java`、`FinanceAdAutomationIntegrationTest.java`。
**Interfaces:** 保留`connectVerified`和`pause`签名；新账户默认启用，已有暂停账户更新凭据后仍暂停。

- [x] 写出并运行失败用例：`automationReauthorizationKeepsAnExplicitlyPausedAccountPaused`、`automationPauseAndResumeRevokesAnEarlierInFlightResponse`、`automationPausedDedicatedAccountCannotImportASecondCopyOfItsReport`、`automationResumeRefusesAnExistingConflictingManualSource`。
- [x] 更新已有账户时使用`status=IF(enabled,'READY','PAUSED')`，不无条件改enabled；保存原断点。
- [x] 显式暂停/恢复撤销旧lease token；恢复前复用专属店铺导入冲突检查，避免旧HTTP响应回写。
- [x] 广告导入对已关联接口账户包含暂停状态执行保守防混录；对明确充值/资金划转类别拒绝当AD消耗。
- [x] 用相同隔离MySQL夹具运行红绿对照，验证账户/导入和金额未重复。

## Task 3：空刷新与分页完整性

**Files:** `FinanceAdAutomation.java`、`FinanceAdAutomationTest.java`、`FinanceAdAutomationIntegrationTest.java`。
**Interfaces:** 成功但空报表是“未返回记录”；只有明确返回的0指标才能覆盖已有费用为0。初次空窗口继续记录请求覆盖，不捏造财务记录。

- [x] 新增旧码失败用例`automationEmptyRefreshPreservesPreviouslyPublishedFinancialRecords`，以及空响应页码/计数格式校验用例。
- [x] 显式零刷新用例`automationAnExplicitZeroReportCanReplaceEarlierSpend`确认不会禁止真实零值。
- [x] 仅在本来源窗口无旧事实时接受空发布；有旧事实时保留原事实/coverage，标错误待核对并按原退避重试。
- [x] 先核对响应页码和存在的total_number格式，再处理total_page=0。
- [x] 更新旧“空返回删除非零”测试的期望为保留并显示错误；导入及其他店铺始终保留。

## Task 4：数据核对、发布与接手

**Files:** `docs/business/finance/advertising.md`；本次发布/验收记录。
**Interfaces:** 普通AD和GMV事实及利润公式保持；以官方源、账户与查询日期解释结果。

- [x] 只读对照已可用账户同一天：现有查询、PRODUCT+LIVE拆分及官方overview金额一致。
- [x] 只读对照现有两店10月完整官方overview与入库合计一致，不将38家未在授权发现范围内的店铺补成0。
- [x] 在不扩大授权的前提下核查既有token可见账户与本地已接入账户，明确剩余外部授权边界。
- [x] 运行全部广告相关Java/隔离数据库/UI回归；核对利润、重复计费、授权隔离、历史GMV和千易修复。
- [ ] 按Gitflow通过hotfix候选、no-ff生产合并、版本标签和标准CD发布，再合回develop。
- [ ] 正式页面及有界官方请求验收；仅对识别出的真实缺口启动保留原断点的补抓，无缺口时不重写已对齐金额。

执行方式已由用户“按照内容处理所有广告逻辑”授权：本会话继续实施与核验，无需另行选择执行模式。

## 独立审查补充

- [x] 部分日期缺失保护：旧日期没有明确记录时整窗保留；新增用例先红后绿，隔离集成83项通过。
- [x] 发现PENDING/ERROR/缺失/无相关账户保持未知；新增UI用例先红后绿，15组DOM通过。
- [x] OAuth发现token改为Access-Token header，契约先红后绿，46项单元通过；同一token实测code0返回28账户，与已接入完全一致。
- [ ] 完成两项Important修复后的独立复核及正式发布验收。
