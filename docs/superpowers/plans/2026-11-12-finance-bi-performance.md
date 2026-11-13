# 财务 BI 响应速度实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** 在保持所有财务口径的前提下，把非总览/大屏页面的主加载与条件查询控制在已定义的 5 秒目标内。

**Architecture:** 按页读取、真实分页与精确计数，配合原币增量查询投影。复用现有财务算法及来源身份，保留旧接口与同筛选/同版本提交；后台状态和汇率刷新不阻塞无关页面。

**Tech Stack:** Java 21、Spring Boot、MySQL 8.4、Redis、原有 JavaScript/JSDOM。

**Spec:** `docs/superpowers/specs/2026-11-12-finance-bi-performance-design.md`（用户已批准对应分析方案）。

## Global Constraints

- 原始金额、首次结算、报表归属月、GIFT、未知、公司权限、历史 FX 和广告重叠边界不变。
- 日常源码运行不构建 JAR、OCI 或发布包；共享数据只做明确授权的维护。
- 使用独立 feature/finance-bi-performance；不暂存其他会话文件；不改已应用迁移。
- V154 属于 Shopee；本任务预留 V155，实际执行前再次检查。
- 缺失/回填中不是已完成；冷响应缓存也须验收。性能预算不能靠延长超时或快速错误达成。

## Task 1: 独立基线与明细读取

**Files:** `FinanceRepository.java`；新增 `FinancePageReadTest.java`、`FinancePageReadIntegrationTest.java`。
**Interfaces:** 保留所有 `details`/`reconciliationPage` 签名及 `{items,total,page,pageSize}` 返回；新游标是可选字段，旧调用继续可用。

- [x] 从最新 develop 建独立工作区，保留原工作区 WIP；已选 36 项 Java 基线通过，0 跳过。
- [x] 写入真实分页、跨公司/店铺、同日 ID 稳定顺序与精确总数回归，确认旧实现的高工作量行为可复现。
- [x] 在隔离 MySQL 对同一查询比较计划，找出已有混合排序索引未用于 LIMIT 的原因；不先猜测增加重复索引。
- [x] 实现正确的页 ID 读取/计数复用；`assertEquals(List.of("a","b"), ids(firstPage))`、后一页无重复、其他公司不出现。
- [x] 运行单元与隔离集成检查，记录 RED/GREEN、计划与结果哈希；仅提交 Task 1 文件。

## Task 2: 成本管理按当前子页加载

**Files:** `FinanceCostWorkspace.java`、`FinanceCostController.java`、`frontend/finance-costs.js`；新增成本上下文/UI 行为回归。
**Interfaces:** 新增 `GET /api/v1/finance/workspace/costs/context?shopId&groupId` 返回轻量 live/shop/group/settings 元数据；原 `/costs` 保持完整目录兼容。向父加载器暴露是否需要完整目录的明确方法。

- [x] 先写失败回归：月度贴单子页不得等待订单 JSON/完整成本目录；切换到商品映射时仍加载完整所需资料。
- [x] 实现轻上下文与按需目录读取，保留配置权限、当前分组和返回上下文；遵从其他会话中用户最新撤销成本核对流程的要求，保留当前直接写入方式。
- [x] 验证切店、切组、失败后保留正确范围、缺价和 GIFT，以及已有前端回归；父 workspace 接线由协调者串行集成。
- [x] 独立检查并提交该任务文件，保留服务器当前直接人工写入、SKU→SPU 和国家/仓库行为；V153 表和原数据继续保留。

## Task 3: 权威投影、失效与后台回填

**Files:** 新增 `FinanceQueryProjections.java`、投影仓储/工作器及 `V155__finance_query_projections.sql`；按最小范围接入已有提交后的刷新入口。
**Interfaces:** 读取键至少包含公司、原币事实范围、完整/部分归属月策略及实际版本；返回 `{data,generatedAt,dataRevision,coverage}`。租约和有界回填由该工作器拥有，不占用上游 API 配额。

- [x] 先验证全年/月/部分月分割、月校正、首次结算、未知计数、删除/分组变更、失败不发布部分数据及跨租户隔离。
- [x] 实现增量脏范围、幂等更新和版本发布；事务提交后通知，回滚不失效；不复制每次自动成本 JSON 前后快照。
- [x] 加入账单核对、日成本和广告来源读取所需投影，复用原算法，禁止改变来源重叠/利润。
- [ ] 隔离验证新迁移、旧数据不变及中断恢复；真实只读对照只输出规范化哈希。

## Task 4: 页面专用接口与加载链路

**Files:** `FinanceController.java`、`FinanceService.java`、`FinanceAdSpendService.java`、`FinanceAdSpendController.java`、`frontend/finance-workspace.js`；新增 `FinanceWorkspaceQueryService.java` 和按页加载回归。
**Interfaces:** 提供按页的上下文/汇总/列表读取，保留旧 API；兼容原 fieldSettings/dateBasis/filters/fx/productCosts，给新版前端提供同一权威结果契约。

- [x] 写失败回归：费用/结算/广告不依赖无关 overview；切新范围不显示旧金额；明细分页不触发整期广告计算。
- [x] 将独立请求同版本并行，接入 Tasks 1–3，保留原卡片字段及 Shopee 新来源。
- [x] 用轻量修订/状态轮询代替整页重读，保留取消、同条件去重和代次保护。
- [x] 回归所有财务 UI、精确金额/未知及新旧接口契约，提供现代前端接手说明。

## Task 5: 汇率关键路径与查询保护

**Files:** `FinanceFxHistory.java`、`FinanceReadCache.java` 及相关测试；必要时新增独立刷新调度类。
**Interfaces:** `FinanceFx.Quote` 的日期映射、不可变已确认历史率和缺失值语义不变；后台提供有界去重，查询不持全局锁等待网络。

- [x] 写可控慢提供方回归，证明已确认历史率可在网络阻塞时读取，并且不同日期、公司/筛选缓存不串用。
- [x] 把缺失历史率补齐及行情更新移出共享请求锁；保留真实时间戳、15/60 秒新鲜度及 60/180 秒过期边界。
- [x] 验证回滚、异常、并发、冷缓存、限流队列；保护超时不能当作成功结果。

## Task 6: 真实验收、协调激活与归档

11 月 13 日现场补充：历史事件追赶仍受每个 2,000 条事务后固定等待 1 秒限制，且成本策略重算继续追加事件。保留每事务上限及具体身份确认，以最多 8 批/250 毫秒检查预算的短周期提高快速批次吞吐；每批后检查维护门与中断，慢批次不继续占用下个批次。先以真实隔离 MySQL 证明多批推进、预算让出、维护关闭和批次数上限，再独立审查和定向生效；不删除未处理事件或改动金额。

**Files:** 更新 `docs/guides/finance-performance.md`、新增本轮回执与只读性能探针；受控维护脚本只保存脱敏小型回执。

- [ ] 独立审查与财务等价检查通过，所有关键发现修复后再整合。
- [ ] 与 Shopee/新版前端确认最新主机基线、V154/V155、文件所有权及串行窗口；只定向应用本任务差异。
- [ ] 按既有双锁/门控/安全点执行必要新增迁移和源码编译/健康恢复；不碰备份和业务卷。
- [ ] 回填完成再切读取；七页及子页、冷/热、筛选/分页、正常同步并发至少 30 次/核心用例；保存 P50/P95/P99/最大值和错误。
- [ ] 不满足 5 秒的用例继续定位，不以均值、空壳或错误替代；明确全部未验证边界。
- [ ] 更新常驻说明和回执，通过 feature PR 合入 develop；保留所需恢复材料，清理仅归属本任务且无引用的测试资源。
