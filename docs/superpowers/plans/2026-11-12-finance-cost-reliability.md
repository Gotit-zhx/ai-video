# 人工成本输入防错 Implementation Plan

> **已撤销。** 用户已取消本功能；下列完成项描述撤销前的历史实现，不代表当前仍启用。恢复流程见[撤销计划](2026-11-12-finance-cost-input-rollback.md)，不要据此重新执行或恢复安全核对功能。

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 仅阻止人工成本表格与人工配置误操作直接影响有效成本。

**Architecture:** 人工入口持久暂存、纯校验、差异/真实影响范围预览、确认或独立复核、沿用原有保存与重算。自动数据和计算不变。

**Tech Stack:** Java 21、Spring/JDBC、MySQL、Flyway、原生JS、JSDOM，无新依赖。

**Spec:** `docs/superpowers/specs/2026-11-12-finance-cost-reliability-design.md`（已按用户最新范围重写）

## Global Constraints

- 不改平台同步/ERP/自动匹配/公式/成本worker/报表导出，数据缺失不伪造零。
- 只新增 V153 人工输入暂存表，不改旧迁移，不执行生产操作。
- 原外部人工HTTP入口必须暂存，不能绕过；公司/manage/同源和独立复核服务端校验。
- 不新增计价期间规则；显示当前保存真实影响的历史及后续范围，要求明确确认。
- 仅记录显式人工修改的审计；自动结果不增加快照。

### Task 1: 纯校验策略与结构化异常

**Files:** Create `FinanceCostValidation.java`, `FinanceCostIssues.java` in `src/main/java/com/yingdao/aicommerce/finance/`; test `FinanceCostValidationTest.java`, `FinanceCostIssuesTest.java` in matching test package. Do not edit integration/controller/worker/UI files.

**Interfaces:** Public static `FinanceCostValidation.analyze(List<Map<String,Object>> incoming,List<Map<String,Object>> current,Map<String,Object> metadata,Map<String,Object> policy)` returns a map with `issues` (code/severity/message/source/action/sku/warehouse/month/row), `semanticHash`, `changedCells`, `unchangedCells`, `requiresReview`, `blocked`, `risk` and `normalizedCosts`. Incoming uses existing `{sku,warehouse,prices:{YYYY-MM:BigDecimal}}` plus optional row/unit/identifierType/warehouseId. Metadata contains sourceKey, sourceVerified (server only), unit, identifierType SKU/SPU/MIXED, effectiveFrom, effectiveTo, revision, spuUniform, warehouseAliases and nowMonth. Policy defaults priceChangePercent=20,priceChangeCny=1,impactChangeCny=1000,impactChangePercent=5; values nonnegative and validated. Stable semantic hash disregards row ordering, file names and numeric scale but retains SKU/SPU digits, case, unit, warehouse, month. `FinanceCostIssues.describe(String issue)` maps current human-readable pending issues to `{code,message,source,action}` with no guessed data. `FinanceCostIssues.of(String code)` exposes known metadata. Pure functions, no DB or new dependencies.

- [x] Write failing behavioral tests first, including numeric scientific ID, long text IDs, reordered equal import, conflicting duplicate, zero, unit missing, typed SPU uniform evidence, price conflict without revision, verified safe supplement, future/out-of-scope month and stable current issue mapping.

```java
@Test void unknownUnitsCannotBecomeActive(){
    var result=FinanceCostValidation.analyze(List.of(Map.of("sku","A","warehouse","菲律宾","prices",Map.of("2026-11",new BigDecimal("2")))),List.of(),Map.of("nowMonth","2026-11"),Map.of());
    assertEquals(true,result.get("requiresReview"));
}
```

- [x] Run focused Maven tests and capture RED before implementation; implement exact interfaces and run GREEN plus existing 41-cost-unit baseline; commit only these classes/tests and write task report.

### Task 2: 人工输入暂存、预览和确认后应用

**Files:** Create `V153__finance_cost_input_safety.sql`, `FinanceCostInputSafety.java` 及必要的预览/变更应用/控制器focused helper；modify `FinanceCostController.java`, `FinanceCostWorkspace.java`, `FinanceWorkbook.java`, related test fixture and new `FinanceCostInputSafetyIntegrationTest.java`. Do not edit automatic worker/repository/platform/ERP/report/export/calculation files.

**Interfaces:** Consume Task1 FinanceCostValidation/Issues. Produce the exact input-changes API from the latest spec; workspace read adds inputSafety:{enabled:true,pendingCount}. Route all original human cost writes through stage; legacy direct apply methods may be package-private for tested activation/fixtures. All public new service methods own transaction via repo.transaction or managed TransactionTemplate, not unproxied @Transactional assumptions. Each draft includes immutable normalized payload, original bytes once, semantic hash, current configuration fingerprint, actual scope counts and version. Low-risk confirm can be submitter; price overwrite/existing relationship replacement/warehouse or basis/factor change/destructive broad effect must have another authorized user. BLOCK cannot be confirmed. No trusted flag from client or unimplemented source profile.

- [x] Write RED integration tests before implementation: staging conflicting import preserves old source/book/cost, unconfirmed zero and unsafe long numeric XLSX blocked, exact/semantic repeat no change, confirm actual scope acknowledgements required, cross-company and independent reviewer enforced, stale confirmation rejected, repeat confirm idempotent, error rolls back, undo rejects changed target.

```java
assertEquals("REVIEW",draft.get("status"));
assertEquals(oldPrice,currentPrice());
assertThrows(RuntimeException.class,()->safety.confirm(owner,owner.userId(),id,ack));
```

- [x] Implement draft migration and workflow, pure diff + bounded impact description (counts full scope; no invented complete amount), parse safety before numeric type information lost, metadata revise, confirm/reject/undo APIs. Undo creates guarded reverse draft, never deletes other work.
- [x] Route original HTTP writes and enable flag. Preserve old calc and private fixture methods. Use existing group/target locks and optimistic versions, baseline signatures checked in applying transaction. Retain source bytes/evidence on withdrawal. No new source/profile/period or result tables.
- [x] Run GREEN in disposable MySQL using `/tmp/finance-cost-governance-20261112/run-isolated.py FinanceCostInputSafetyIntegrationTest`; run existing units; commit only backend/migration/tests and report in task-2-report.md.

### Task 3: 人工输入核对界面

**Files:** Create `frontend/finance-cost-input-safety.js`, `scripts/qa/test-finance-cost-input-safety-ui.mjs`; modify `frontend/finance-costs.js`, `frontend/finance-costs.css`, `frontend/index.html`, related cost QA fixtures. No automatic-sync/report UI changes.

**Interfaces:** `window.FinanceCostInputSafety.mount(root,{request,groupId,shopId,canManage,onChanged})` → {dispose}; relative request prefix `/costs/input-changes`. Spec API map and status values exact. `preview.inputSafety.enabled` turns staged semantics on. Human metadata contains unit/identifierType/sourceKey/spuUniform/revision/reason/evidenceRef as needed; do not offer date scopes not enforced by current calc. Original delete API may accept optional JSON body or metadata query as backend contract.

- [x] Write RED JSDOM tests: upload staged not active; form sends unit/type/reason/reference; confirmation requires actual-impact/history ack; high-risk owner/read-only no confirm button; revise/confirm/reject/undo sends version; only GET polling; failure preserves last result; metadata text not raw HTML.

```javascript
await click('导入与操作核对');
assert(document.body.textContent.includes('等待其他财务人员复核'));
assert(!document.body.textContent.includes('上传成功，已生效'));
```

- [x] Implement new核对tab and guarded upload/manual UI; bounded diff/issue display, current source names and real scope wording, contextual confirm actions. Read-only can inspect; no fake trusted-source toggle.
- [x] Run GREEN and existing source/cost/SPU/live UI suites; commit only frontend/tests; report task-3-report.md.

### Task 4: 定向集成、说明和独立审查

**Files:** Update `docs/business/finance/costs.md`, add `docs/releases/finance/finance-cost-input-safety-20261112.md`, focused regressions only.

- [x] Run necessary units, isolated MySQL original/new cost tests, all finance UI and syntax checks. Record passed/failed/skipped boundaries; no production access.
- [x] Confirm diff excludes automatic sync/ERP/calculation/worker/report/export changes. Document high-risk independent review, metadata correction, stale preview/undo behavior and initial historical data untouched.
- [x] Independent review against cf76ea0, fix important issues with scoped tests/re-review; feature commit/push and attached PR to develop. Do not deploy migration.
