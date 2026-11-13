# 财务投影构建基线与版本化脏日 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 使原币投影在正常同步持续写入时逐步追平，随后完成真实等价、前端切换和财务专页 5 秒验收。

**Architecture:** 在当前 JSON 原币事实格式上保存内部构建基线 R，使用全量失效修订和逐日失效修订保留 R 之后的变化。租约身份与期限继续控制保存；查询继续严格要求完整已追平版本。沿用已有工作器、Redis、原生财务算法和 API。

**Tech Stack:** Java 21、Spring JDBC、MySQL 8.4、Flyway、现有 Redis、原生 JavaScript 财务前端。

**Spec:** [已确认设计](../specs/2026-11-13-finance-projection-baseline-design.md)。父任务：[财务性能实施计划](2026-11-12-finance-bi-performance.md)。

## Global Constraints

- 只修改当前任务文件，保留其他会话源码及 Git 暂存状态；沿用当前 `feature/finance-bi-performance`，PR65 → develop。
- 源码共享根 `/opt/heyi/dev/ai-video`；所有主机变更由协调者执行，子任务仅本地实现/验证。共享维护双锁、门控、安全点、编译成功、健康恢复完整执行。
- V157 已与 Shopee 会话协调占用，正式执行前仍重核；V155/V156 不改动、不重放。
- 新迁移只改两张派生元数据表；金额公式、原始财务数据、权限、凭据、同步计划、检查点、租约及上游限流沿用原规则。
- 数据库测试只运行 `/tmp/finance-bi-performance-qa/run-db-tests.py` 创建的可销毁本机 MySQL。所有 Maven 共享 `target/`，必须串行；Python 工具单测可独立运行。
- 金额/原始响应/凭据不进日志；探针只输出时间、计数、状态和哈希。
- 中间基线不作为 READY 返回，PENDING/空壳/错误不计作 5 秒成功。

## Task 1: 迁移、内部基线与带版本脏日

**Files:**
- Create `src/main/resources/db/migration/V157__finance_projection_baseline_revisions.sql`。
- Modify `src/main/java/com/yingdao/aicommerce/finance/FinanceQueryProjections.java`。
- Modify `src/test/java/com/yingdao/aicommerce/finance/FinanceConfigurationIntegrationTest.java`、`FinanceQueryProjectionIntegrationTest.java`。
- Create `src/test/java/com/yingdao/aicommerce/finance/FinanceProjectionBaselineMigrationIntegrationTest.java`、`FinanceProjectionBaselineIntegrationTest.java`。

**Interfaces:** 保持 `find/findForQuery/version/request/buildNext/drainEvents` 的签名、原币 JSON 格式及 API 状态。新增两列：

```sql
ALTER TABLE finance_query_partitions ADD COLUMN full_rebuild_revision BIGINT NOT NULL DEFAULT 1;
UPDATE finance_query_partitions
SET full_rebuild_revision=CASE WHEN full_rebuild THEN requested_revision ELSE built_revision END;
ALTER TABLE finance_query_dirty_days ADD COLUMN requested_revision BIGINT NOT NULL DEFAULT 0;
UPDATE finance_query_dirty_days d JOIN finance_query_partitions p
  ON p.organization_id=d.organization_id AND p.platform_filter=d.platform_filter AND p.partition_month=d.partition_month
SET d.requested_revision=p.requested_revision;
```

- [x] 写迁移失败测试：从真实 V156 派生状态开始，保存所有旧字段/载荷/源账本哨兵，调用缺失 V157 文件，验证测试先失败。
- [x] 创建迁移，验证旧字段/原始数据不变，现有 full/partial/READY 状态初始化正确；新分区默认全量修订 1、兼容旧写入的脏日默认修订 0。
- [x] 公共 fixture 支持并默认迁移到 157；显式 `migrateThrough(154/155/156)` 继续只安装指定版本。
- [x] 新增失败回归：计算中 DAY 改变后保留内部基线、同日较大修订保留、后续增量追平；计算中 ALL 改变后下一次仍全量；租约替换/过期不保存；失败回滚不删除脏日。
- [x] 记录领取的 R、构建快照开始时间 S；完成后在原保存事务中按原租约身份/期限及 `built_revision<=R` 保存基线。`built_revision=R`，`full_rebuild=(full_rebuild_revision>R)`；请求修订保留当前值。

```sql
-- SQL 中的 R 均以同一个领取值绑定。
UPDATE finance_query_partitions SET full_month_json=?,partial_first_day_json=?,spend_json=?,
 built_revision=?,schema_version=?,generated_at=?,full_rebuild=(full_rebuild_revision>?),
 lease_token=NULL,lease_until=NULL,failures=0,error_code=NULL
WHERE organization_id=? AND platform_filter=? AND partition_month=?
 AND lease_token=? AND lease_until>UTC_TIMESTAMP(6)
 AND requested_revision>=? AND built_revision<=?;
-- 只在本次租约成功保存后执行：
DELETE FROM finance_query_dirty_days WHERE organization_id=? AND platform_filter=? AND partition_month=?
 AND requested_revision<=?;
```

- [x] 全量失效同时设置 `full_rebuild_revision=requested_revision+1` 和 `requested_revision=requested_revision+1`；逐日失效从已加锁分区读取新修订写入脏日，重复日期取较大修订。
- [x] 对外查找仍要求 `built_revision=requested_revision`、原 schema/正文/事件/同快照复核；修改原 CAS 测试的内部保存预期，但保留未追平不返回的断言。
- [x] 运行迁移与 baseline 新测试、projection/query/defaultWarm/metadata/cost 相关回归。由独立 reviewer 检查 spec + quality，通过后协调者按文件提交本任务。

## Task 2: 固定 V157 维护工具与演练

**Files:**
- Create `scripts/maintenance/finance_projection_baseline_activation.py`。
- Create `scripts/maintenance/test_finance_projection_baseline_activation.py`。
- Create `scripts/qa/java/FinanceProjectionBaselineSchemaMaintenance.java`。
- Create `src/test/java/com/yingdao/aicommerce/finance/FinanceProjectionBaselineSchemaMaintenanceIntegrationTest.java`。

**Consumes:** Task1 冻结的 SQL 文件及 SHA256；当前主机基线 156；已有 `finance_query_projection_activation.Activation` 与 V156 固定 helper 的令牌、连接登记、历史前缀保护协议。

**Produces:** `Activation.VERSION='157'`、`PREVIOUS_VERSION='156'`，固定 `SQL/SQL_SHA/HELPER`；显式 validate/migrate 模式，重复应用失败。

- [x] 先创建失败工具测试：错误基线/SQL hash、多条待迁移、失效令牌、源码或配置漂移、编译失败、重放、异常恢复均不能越过原保护。
- [x] 复用已有 Store/Host/Activation 协议，实现只接受固定 V157 的维护入口；保留原构建失败不重启、已存在维护不接管、只停本任务 helper JVM/连接的边界。
- [x] Java helper 在 stdin 读取私有配置，确认旧 156 历史有效、恰好一条待执行 157、前缀逐行保持，执行后确认新列和既有历史；凭据不进参数或输出。
- [x] 原始财务/授权/同步表采用固定 SQL 白名单、元数据及有界哨兵摘要保护；不对百万账本做无界 COUNT/整表哈希。迁移校验与原始数据保护只输出摘要。
- [x] Python 演练可独立执行：`python3 -m unittest scripts/maintenance/test_finance_projection_baseline_activation.py`。涉及 Maven 的真实 helper 集成必须等协调者串行安排。
- [x] helper 测试按历史 156 runtime 复制资源（仅到156），在单文件私有目录放157；避免把新 classpath157与filesystem157重复解析。
- [x] 独立审查 spec + quality，向协调者提供 changed files、测试结果、固定 SHA、入口和恢复协议；子任务不访问主机、不暂存/提交共享 Git 状态。

## Task 3: 查询的有界轻量等待

**Files:** `FinanceWorkspaceQueryService.java`、`FinanceWorkspaceQueryIntegrationTest.java`，必要的专用等待回归。

**Consumes:** Task1 中间基线保持 PENDING、完整增量转为 READY 的既有 `version` 契约。

- [x] 先写失败测试：PENDING 后后台完成增量，读取应在轻量等待后走专页；原实现立即重算权威全月而失败。
- [x] PENDING 时用单调时钟在总计最多 2 秒内每 100 毫秒重新读取轻量版本。中断立即保留中断语义，READY 后沿用原加载器与同快照修订复核。
- [x] 超时沿用原权威 fallback；已有 READY 暖缓存不额外等待，PENDING 不作为完整页面成功。
- [x] 用真实隔离 DB/Redis 验证成功、超时、中断、期间版本变化、字段偏好和心跳不缓存、不同公司/筛选隔离；独立审查后按目的提交。

## Task 4: 迁移与真实同快照核对

**Files:** 自有私有 operation manifest/receipt、`FinanceWorkspaceReadProbe.java` 及必要回归。

- [ ] 重读正式基线、租约/工作器、schema/runtime/source/staging 哈希，确认本会话维护窗口与其他会话只读。
- [ ] 按 Task2 演练通过的固定工具，在同一维护锁内门控、等安全点、执行唯一157、新源码 CAS、成功编译/新上下文/健康恢复；保存小型回执。
- [ ] 复核旧迁移前缀、配置/容器/无关源码/业务数据摘要、匿名财务401、TLS及登录，恢复本次门控。
- [ ] 在正常同步运行下检查 hidden baseline 推进及 READY；原始/派生完整月、部分月、跨月、平台、组/店铺、精确页与顺序只读比较，输出哈希。
- [ ] 失败只定位当前差异，保持真实未知和任务状态；不清队列、重置业务检查点或把 PENDING 当通过。

## Task 5: 前端切换、5秒验收与交接

**Files:** `frontend/finance-workspace.js`、`frontend/finance-costs.js`；更新常驻说明和 `docs/releases/finance/finance-workspace-performance-20261113.md`。

- [ ] 重新下载两前端当前源码；与已验证私有三方候选及本地文件逐字节核对，保留 V156、最新成本策略和 Shopee 资料。
- [ ] 41 项财务 UI 回归在最终候选运行通过，再在维护窗口 CAS 两文件，核对实际 HTTP hash/no-store、前端 revision 与原后端上下文。
- [ ] 真实浏览器核心页面/子页每类至少30个主表与关键汇总完成样本；覆盖首次/重复、9/10/本月、币种、全部/组/店、分页和正常同步。记录 P50/P95/P99/max、错误、范围与金额版本，不以空壳或均值替代。
- [ ] 超过5秒继续按实际阶段定位；完成后更新测试/上线/未验证边界，独立整体评审。
- [ ] 通过 feature PR65 整合，保留历史；告知现代前端会话最终 context、schema/runtime、两JS/Java/dev.py 哈希、回执和窗口交接。
