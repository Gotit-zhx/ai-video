# 商品成本资料可靠性 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将所有外部成本文件和人工变更纳入隔离、校验、影响预演、期间版本和风险复核，保护正式订单成本。

**Architecture:** 保留现有财务模块，新增候选变更服务、纯校验策略、有界准备工作器、活动结果视图及前端治理页。复用现有权限、事务、MaintenanceGate、只读报表和导出，所有外部旧写入口转为候选。

**Tech Stack:** Java 21、Spring Boot、JdbcTemplate、MySQL 8、Flyway、原生 JavaScript、JSDOM。

**Spec:** `docs/superpowers/specs/2026-11-12-finance-cost-reliability-design.md`

## Global Constraints

- 不连接生产库执行测试；不操作共享服务器、密钥、业务卷或其他会话任务。
- 新增 V153 迁移，不修改已应用迁移；保留 legacy 未核验资料和有效结果。
- 保留 ERP 优先、人工关系、SKU→SPU、GIFT、取消/退款和首次结算口径。
- 自动重算不追加完整前后 JSON；候选准备有界，每批最多 100 笔。
- 所有人工 HTTP 写入不能绕过候选；同公司 manage、同源、独立复核约束必须在服务端执行。
- 按已有 feature 分支提交，只暂存本任务内容；不自动部署有迁移的代码。

### Task 1: 纯校验策略与结构化异常

**Files:** Create `FinanceCostValidation.java`, `FinanceCostIssues.java` in `src/main/java/com/yingdao/aicommerce/finance/`; test `FinanceCostValidationTest.java`, `FinanceCostIssuesTest.java` in matching test package. Do not edit integration/controller/worker/UI files.

**Interfaces:** Public static `FinanceCostValidation.analyze(List<Map<String,Object>> incoming,List<Map<String,Object>> current,Map<String,Object> metadata,Map<String,Object> policy)` returns a map with `issues` (code/severity/message/source/action/sku/warehouse/month/row), `semanticHash`, `changedCells`, `unchangedCells`, `requiresReview`, `blocked`, `risk` and `normalizedCosts`. Incoming uses existing `{sku,warehouse,prices:{YYYY-MM:BigDecimal}}` plus optional row/unit/identifierType/warehouseId. Metadata contains sourceKey, sourceVerified (server only), unit, identifierType SKU/SPU/MIXED, effectiveFrom, effectiveTo, revision, spuUniform, warehouseAliases and nowMonth. Policy defaults priceChangePercent=20,priceChangeCny=1,impactChangeCny=1000,impactChangePercent=5; values nonnegative and validated. Stable semantic hash disregards row ordering, file names and numeric scale but retains SKU/SPU digits, case, unit, warehouse, month. `FinanceCostIssues.describe(String issue)` maps current human-readable pending issues to `{code,message,source,action}` with no guessed data. `FinanceCostIssues.of(String code)` exposes known metadata. Pure functions, no DB or new dependencies.

- [ ] Write failing behavioral tests first, including numeric scientific ID, long text IDs, reordered equal import, conflicting duplicate, zero, unit missing, typed SPU uniform evidence, price conflict without revision, verified safe supplement, future/out-of-scope month and stable current issue mapping.

```java
@Test void unknownUnitsCannotBecomeActive(){
    var result=FinanceCostValidation.analyze(List.of(Map.of("sku","A","warehouse","菲律宾","prices",Map.of("2026-11",new BigDecimal("2")))),List.of(),Map.of("nowMonth","2026-11"),Map.of());
    assertEquals(true,result.get("requiresReview"));
}
```

- [ ] Run focused Maven tests and capture RED before implementation; implement exact interfaces and run GREEN plus existing 41-cost-unit baseline; commit only these classes/tests and write task report.

### Task 2: 候选变更、期间配置、原子生效与旧接口接入

**Files:** Create `V153__finance_cost_governance.sql`, `FinanceCostGovernance.java`, focused helpers for persistence/preview/activation/periods/worker/controller as needed; modify `FinanceCostWorkspace.java`, `FinanceCostSources.java`, `FinanceOrderCosts.java`, `FinanceCostWorker.java`, `FinanceCostReport.java`, `FinanceCostExport.java`, `FinanceCostController.java`, related active-catalog readers and test fixture; add `FinanceCostGovernanceIntegrationTest.java` and related unit tests.

**Interfaces:** Consume Task 1 validation and issue maps. Produce complete API/payload/state contract from the spec; cost workspace read includes `governance:{enabled:true,groupRevision,updating,pendingChanges}` to opt UI into staged semantics. Additive legacy internal fixture/apply methods may remain package-private for activation and existing tests; external HTTP always stages. All new service mutations must own a transaction, including direct service test callers. Candidate worker gets existing repository/service/transaction manager; at most 100 orders/tick, durable checkpoints, lease/single-owner and MaintenanceGate. Source input watermark and group revision checked before activation; an older worker may not write after a newer revision. Active candidate result view must be used consistently across reports, exports, saved issue reads and enqueue comparisons. Existing direct read/write tests must not be globally disabled.

- [ ] Add failing integration tests proving an unverified conflicting upload leaves the old active price and cost untouched, cross-company IDs rejected, self-review rejected, two distinct reviewers for closed period, temporal manual/mapping isolation, old task protection, rollback retains raw evidence.

```java
assertEquals("REVIEW",submitted.get("status"));
assertEquals(oldAmount,db.queryForObject("SELECT amount_cny FROM finance_current_order_costs WHERE order_record_id=?",BigDecimal.class,record));
assertThrows(RuntimeException.class,()->governance.review(owner,owner.userId(),change,Map.of("decision","APPROVE","version",version,"reason","同人审批")));
```

- [ ] Implement new migration and lifecycle using Task 1; preserve old records. Add source profile confirmation, stable warehouse identities/aliases, month-close APIs, scoped manual/mapping/settings versions, semantic dedupe, conflict handling, impact preview and cancellation/rollback.
- [ ] Stage every existing external cost write; preserve same-origin/manage checks; worker calculates candidate using modified contexts without mutating active inputs, publishes only complete validated target range, and resumes after interruption.
- [ ] Validate by isolated MySQL with fresh schema, meaningful targeted concurrent-version tests and existing cost/export regressions; commit and write task report with actual coverage and no unsupported connector claims.

### Task 3: 成本治理、异常工作台与受控编辑前端

**Files:** Create `frontend/finance-cost-governance.js` and `scripts/qa/test-finance-cost-governance-ui.mjs`; modify `frontend/finance-costs.js`, `frontend/finance-costs.css`, `frontend/index.html` and relevant QA fixtures only. Existing ungoverned preview/legacy fixture remains compatible; governed server advertises `preview.governance.enabled`.

**Interfaces:** `window.FinanceCostGovernance.mount(root,{request,groups,shops,groupId,shopId,canManage,onChanged})` returns `{dispose}`; `mountIssues` same contract. HTTP request path relative to workspace (`/cost-governance`), use the spec JSON exactly. Existing import form adds metadata FormData JSON; existing settings/mapping/manual/remove adds `governance:{effectiveFrom,effectiveTo,reason,evidenceRef,revision}` body (DELETE uses query fields or JSON body accepted by Task 2). All mutation responses may be staged; handle once without duplicate POST and show pending status instead of claiming activation. `preview.governance` selects new labels and required fields, preventing old tests from pretending to cover new path.

- [ ] Add RED real JSDOM tests: staged upload does not announce active success; source/unit/type/period fields submitted; review button absent for self/read-only; exception source/action visible; progress polling is GET-only; load failure retains prior content; review/second-review/rollback include expected version and reasons.

```javascript
await click('资料校验与版本');
assert(document.body.textContent.includes('等待独立复核'));
assert(![...document.querySelectorAll('button')].some(b=>b.textContent==='忽略全部异常'));
```

- [ ] Implement governance and issues pages, source profiles and financial month controls, candidate details/diffs/impact/reason actions and imports/manual controls; DOM textContent for untrusted data, no raw HTML injection or downloads.
- [ ] Run GREEN plus existing finance-cost UI regression suites; preserve order SPU column, filters and export behavior; commit and write task report.

### Task 4: 集成验收、文档与独立审查

**Files:** Update `docs/business/finance/costs.md`, add `docs/releases/finance/finance-cost-reliability-20261112.md`, necessary focused integration/UI regressions; no unrelated refactors.

**Interfaces:** Verify complete spec via actual endpoints/services, MySQL reads and rendered DOM; implementation claims are not evidence.

- [ ] Run all relevant Java unit + isolated MySQL cost suites (none skipped), all finance UI suites and syntax checks. Verify migration old-data retention and cleanup only this task's test DB/account/files.
- [ ] Record passed/failed/unverified boundaries, source trust limits, first-time legacy handling, review roles, period semantics, rollback and resource bounds. Do not claim server migration or real ERP capability without execution.
- [ ] Request broad independent review against baseline cf76ea0; fix blocking/important issues with regression tests and scoped re-review. Commit, push feature and create attached draft PR to develop; no server activation in this task.
