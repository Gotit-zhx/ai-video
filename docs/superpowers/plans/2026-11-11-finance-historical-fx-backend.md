# Historical Finance FX Backend Implementation Plan

> **For agentic workers:** Execute the approved backend work inline in the current shared feature branch. Root alone runs Maven and integration checks; do not commit, switch branches, access production, or edit the advertising owner's files.

**Goal:** Convert each amount using its own business date and freeze the daily references used across reports, pages and exports; expose current market reference rates independently.

**Architecture:** Add V151 tables for immutable provider observations and append-only historical snapshot coverage. FinanceFx owns bulk loading, cutoff and conversion; report services convert daily facts before reducing dimensions. Preserve legacy finance_fx_snapshots and the three-argument synthetic Quote constructor.

**Tech Stack:** Java 21, Spring JDBC, Jackson 3, MySQL/Flyway, JUnit 5. Fixed official Frankfurter ECB source; no new dependencies.

**Spec:** Root's approved 2026-11-11 historical FX contract in this chat.

## Global constraints

- Order/PENDING use original order date; refunds original occurrence; settlements/bills/fees official settlement date; advertising report date; independent expenses own date. CNY product/label costs convert on first settlement day.
- Provider publication date must be no later than the business date and snapshot cutoff. Missing data stays unknown; native ledger, reconciliation classification, cost formulas and permissions remain unchanged.
- Only add V151; do not modify old migrations, old snapshot rows, frontend, AdSpendService or the four advertising specialist files.
- Root serializes all Maven and disposable-database tests. Tests use public synthetic rates and no actual network or business data.

## Task 1: frozen daily-rate service

Files: FinanceFx.java, new FinanceFxHistory.java, V151__finance_historical_fx.sql, FinanceHistoricalFxTest.java.

- [ ] Add tests distinguishing two business-day amounts, weekend fallback, missing/future rate rejection, immutable existing snapshot days and CNY identity.
- [ ] Ask root to run the focused red checks, implement daily persistence and cutoff-bound batches, and ask root for green checks.
- [ ] Produce `historical(String, LocalDate, LocalDate)`, `historical(String, Collection<LocalDate>)`, `Quote.convert(Object,String,LocalDate)`, `Quote.fromCny(Object,String,LocalDate)`, and `market()`.

## Task 2: conversion before aggregation

Files: FinanceService.java, FinanceRepository.java, FinanceAdvertisingReport.java, FinanceProfitReport.java, FinanceCostReport.java, FinanceCostWorkspace.java, FinanceCostController.java.

- [ ] Preserve independent fx_date alongside reporting date in ORDER/REFUND facts and details; normalize both across cached JSON.
- [ ] Preload all selected daily facts, convert before totals/rankings/categories/status reductions, and convert hourly orders using their selected business day.
- [ ] Project cost display amounts from daily first-settlement facts; return complete amount only when cost and FX coverage are complete.
- [ ] Keep native reconciliation status and aggregate converted transaction components by their own days.

## Task 3: boundary metadata, exports and cache

Files: FinanceController.java, FinanceFields.java, FinanceReadCache.java and relevant tests.

- [ ] Add authenticated GET /fx/market and historical row metadata (businessDate/rateDate/provider/status/error).
- [ ] Freeze and reuse one historical snapshot across paginated export; include actual day-rate evidence and missing dates.
- [ ] Version converted response keys so old single-rate results cannot be reused. Keep tenant and filter isolation.
- [ ] Root runs focused tests, then the affected finance regression and isolated DB suites; record passed/failed/unverified results without production claims.
