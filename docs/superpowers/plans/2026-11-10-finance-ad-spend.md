# Finance ad spend implementation plan

> Execute inline using the executing-plans workflow, with test-first checkpoints. No parallel agents or production mutations.

**Goal:** Deliver three cross-platform shop spending tables with real funding fields and explicit unresolved ownership.

**Architecture:** A separate spending read model consumes existing AD/AD_GMV facts and canonical imported funding records. Versioned allocations operate on source fingerprints. Existing finance dashboard and profit formulas remain unchanged.

**Tech Stack:** Java 21, Spring MVC/JdbcTemplate, MySQL/Flyway, existing plain JavaScript UI and workbook IO.

**Spec:** `docs/superpowers/specs/2026-11-10-finance-ad-spend-design.md`.

## Constraints

Preserve company permissions, raw amounts, null/zero distinction, synchronization checkpoints and profit basis. No guessed attribution, mixed-currency summation, duplicate API/import costs, credential files or business workbook committed to Git. Work only on feature/finance-ad-spend-workspace.

## Tasks

- [x] Add failing `FinanceAdSpendReportTest` cases for debit signs, funding controls, mixed-platform grouping, API/import overlap, pending multi-shop allocations, residuals and invalidated fingerprints. Run the focused Maven test and retain red evidence.
- [x] Implement pure `FinanceAdSpendReport` and `FinanceAdSpendImport` parsers with BigDecimal/null funding semantics. Run the same cases green; validate the provided workbook privately without inserting it anywhere.
- [x] Add Flyway V150 import and allocation-history tables (V149 reserved for the concurrent export task); implement `FinanceAdSpendService` and separate `FinanceAdSpendController` endpoints for scoped report, preview/import, exact allocations and three-sheet export. Reuse existing permission/origin guard and `FinanceWorkbook.xlsx`; reject out-of-scope shops, source conflicts, overflow and excessive ranges explicitly. Add isolated-database tests and verify no changes to legacy finance records.
- [x] Add failing DOM regression for three primary tables, source fields, multi-platform rows, unknown funding, pending allocation, export/query pagination and late-scope response isolation. Implement dedicated frontend module and integrate it into the existing finance load lifecycle; keep legacy diagnostic view in a collapsed support section. Add the module to production/local asset packaging.
- [x] Run targeted Java and isolated DB tests, full frontend checks, migration/asset checks; update permanent advertising documentation and record exact validation boundaries. Review the final diff; deliver a local preview or reproducible test result. Do not import the real workbook, push or deploy without task scope supporting those actions.
