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

- [x] 重读正式基线、租约/工作器、schema/runtime/source/staging 哈希，确认本会话维护窗口与其他会话只读。
- [x] 按 Task2 演练通过的固定工具，在同一维护锁内门控、等安全点、执行唯一157、新源码 CAS、成功编译/新上下文/健康恢复；保存小型回执。
- [x] 复核旧迁移前缀、配置/容器/无关源码/业务数据摘要、匿名财务401、TLS及登录，恢复本次门控。
- [ ] 在正常同步运行下检查 hidden baseline 推进及 READY；原始/派生完整月、部分月、跨月、平台、组/店铺、精确页与顺序只读比较，输出哈希。
- [ ] 失败只定位当前差异，保持真实未知和任务状态；不清队列、重置业务检查点或把 PENDING 当通过。

## Task 5: 前端切换、5秒验收与交接

**Files:** `frontend/finance-workspace.js`、`frontend/finance-costs.js`；更新常驻说明和 `docs/releases/finance/finance-workspace-performance-20261113.md`。

- [x] 重新核对两前端当前源码字节；与已验证私有三方候选及本地文件核对哈希，保留 V156、最新成本策略和 Shopee 资料。
- [x] 41 项财务 UI 回归在最终候选运行通过，再在维护窗口 CAS 两文件，核对实际 HTTP hash/no-store、前端 revision 与原后端上下文。
- [ ] 真实浏览器核心页面/子页每类至少30个主表与关键汇总完成样本；覆盖首次/重复、9/10/本月、币种、全部/组/店、分页和正常同步。记录 P50/P95/P99/max、错误、范围与金额版本，不以空壳或均值替代。
- [ ] 超过5秒继续按实际阶段定位；完成后更新测试/上线/未验证边界，独立整体评审。
- [ ] 通过 feature PR65 整合，保留历史；告知现代前端会话最终 context、schema/runtime、两JS/Java/dev.py 哈希、回执和窗口交接。

## Task 6: 真实收敛验收发现的广告发现无变化失效

Task 4 正常同步取证捕获到 `finance_ad_stores` 八条 ALL；实际同批阈值探测为零、全量更新十二个分区且提交成功。`FinanceAdAutomation.discover` 先全部停用再逐店写回，相同最终资料也触发 V155 的全量失效。此修正先于 Task 4、5 最终验收完成，属于已授权性能优化，不改财务算法或迁移历史。

- [x] 用真实触发器写隔离库 RED：相同发现不改变完整投影修订；缺失店铺只停用一次，真实元信息、可用性或匹配变化保持失效。
- [x] 源码只写入最终状态确有变化的店铺，保留租约、事务、校验、同步计划、检查点、流创建及重匹配；不变店铺保留 observed_at，账户成功状态及下一次发现计划继续推进。
- [x] 运行相关实际 MySQL 回归并独立审查，再根据 V158 交回后的现场基线 CAS 激活；不改 V155/157，不抢占其他维护。
- [ ] 持续同步下再次取证收敛，再继续真实等价与五秒验收；不以内部基线、PENDING、清队列或暂停同步代替。

## Task 7: 防止活动月份在构建队列中饥饿

无变化店铺发现修复后，全量失效版本保持稳定；16:20 实际队列仍因按最后请求时间排序，让最近已保存的 8 月优先于数小时前保存的 10、11 月。新变更越频繁，请求时间越新，反而越晚构建。

- [x] 用真实构建选择写 RED，验证新请求不能把陈旧分区一直推后，且成功保存后仍脏的分区必须给其他旧分区让出一轮。
- [x] 仅调整已有单工作器领取顺序：无内部基线优先，其次最久未保存的快照，请求时间仅作为并列条件，最后按完整分区身份稳定排序；租约、回退、积压保护、失效和完整性规则保持。
- [x] 通过投影、基线及默认预热相关隔离数据库回归与独立审查后，按现场基线 CAS 生效；不增加并发或 DDL。
- [ ] 继续在正常同步下验证公平推进及 READY，再完成 Task 4、5 的真实等价和五秒验收。

## Task 8: 优化可见店铺的索引访问路径

真实同快照对照中，9 月 TikTok 精确费用计数均为 922,406 条；当前可见店铺 IN 子查询使用公司分组索引、估算 87 行，耗时 25.841 秒。仅改为带相同组织与移除条件的主键 EXISTS 查找后，计划变为 PRIMARY/eq_ref/1 行，耗时 3.801 秒。单换外层窄索引仍需约 17 秒，不混入本次修复。

- [x] 用实际 MySQL 计划与隔离矩阵写失败回归，覆盖公司、活跃/已移除店铺、未分配记录及别名、分组、币种、平台、日期条件。
- [x] 只改 query() 中同一可见性条件的访问方式，保持条件、参数顺序、单语句权限边界和所有财务规则；不预取或缓存店铺 ID，不增加 DDL。
- [x] 运行中央查询相关回归并独立审查，受控 CAS 生效后核对当前配置、其他源码及实际响应。
- [ ] 根据真实加载与查询样本继续验收，不能将单条计数改善等同全页五秒通过。

## Task 9: 单事务分段保存大投影

10 月全平台逐日补算约 84 秒已成功算完，但三份 JSON 规范化后总计 68,882,987 字节，超过 64 MiB 单包上限；只读等价参数绑定复现 `PacketTooBigException/S1000`，Spring 译为现场相同的 `TransientDataAccessResourceException`。不因此改全局包大小、内存、财务口径或重建策略。

- [x] 用可销毁库和每连接包预算建立真正驱动失败 RED，三个独立正文均低于预算、合并高于预算；不修改全局数据库参数。
- [x] 在同一既有事务、原租约/修订 CAS 和行锁下分段写三份正文，最后按原 R 清理脏日；任一后续写入失败必须回滚全部正文和元数据。
- [x] 验证完整/增量回写、读取往返、无变化正文、跨连接不可见中间态、并发晚变更、失效租约和失败回滚，运行相关回归并独立审查。
- [ ] 按最新共享基线定向 CAS 生效，保持全局配置，再验证构建成功、真实已发布等价和全页五秒目标。

## Task 10: 未筛选账单来源计数使用已有窄索引

成本默认页的完整 SQL 计时中，来源总数第一段曾为 8.611 秒；重复计数热样本为原 PRIMARY 135 毫秒、既有 `uk_label_file_event` 107 毫秒，数量一致，不宣称这是成对冷查询加速。该既有索引叶子页 9,879，原 PRIMARY 为 52,976，未筛选计数无需遍历宽聚簇行。

- [x] 用实际隔离库计划 RED，未筛选的最终行条件为 `1=1` 时要求既有窄索引，核对精确数量及跨文件分页顺序。
- [x] 只对该未筛选 COUNT 指定 `uk_label_file_event`；有状态、分组、店铺、平台、编号或已移除店铺条件时保持原查询。授权、日期口径、来源顺序及期间汇总不变。
- [ ] 运行来源及贴单相关回归并独立审查；受控生效后复测真实页面和筛选，不将索引页规模缩小视为五秒达标。
