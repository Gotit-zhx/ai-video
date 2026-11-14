# 首次结算事实隔离原型 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. 本计划沿用已授权的五步目标；执行组织由 root 负责，子代理不可自行启动 Maven、服务器维护或新增委派。

**Goal:** 在可销毁数据库中实现并证明首次有效结算事实的事务登记、初始化升级、RR/CAS 发布和真实贴单 workspace 消费，再由独立审查及有界影子证据决定读路径激活。

**Architecture:** 以 `(organization_id,shop_id,order_id)` 保存首笔身份和单调修订，独立源表触发器在源事务中登记旧、新 owner。工作器在 RR 中计算、短事务 CAS 发布；贴单由完整活动 totals 驱动，在整个 workspace 的同一只读 RR 内合并 clean 和 dirty/missing 的精确结果，候选局部预算不足且事务仍有效时整条原生回退；整个 RR 过期或事务/连接失效时整次 workspace 失败。先隔离、后 shadow、最后限定激活，保留既有原生关系及财务公式。

**Tech Stack:** 仓库现有 Java 21、Spring Boot、JdbcTemplate、MySQL 8.4、Flyway、JUnit；本切片不新增依赖，不引入 Redis 整页缓存。

**Spec:** `docs/superpowers/specs/2026-11-14-finance-first-settlement-facts-design.md`；实施前同时阅读 `.superpowers/sdd/2026-11-14-finance-query-facts-stage1/query-facts-audit.md` 和 `first-fact-design-rereview.md`。限定复审仅批准隔离原型契约，未验收生产正确性或性能。

## Global Constraints

- 身份是 `(organization_id,shop_id,order_id)`，不按月份、币种或平台先拆分。
- 候选完全沿用 `FinanceCostReport.confirmed` 和 TikTok ORDER 类别条件，从全部历史按 `(record_date,id)` 选最早有效行。日期、币种、平台、权限、营销排除和归属月条件在确定首笔后应用。
- 同时保存首笔日期和首笔 ID。`firstSettlement(...,true)` 是最早单行；`false` 是首日的全部有效行，两者不能替换成同一种关系。
- 第一版固定复制 platform/currency，减少宽原始行读取；两字段与 date/id 一起原子发布，两字段变化必须标脏。
- `seed_complete` 仅证明有效结算候选 owner 覆盖及之后候选变动的事务登记闭合；ORDER/REFUND、活动 totals 预热不是完成门。
- 正常写路径不删除状态行。任何显式清理/重建必须先使公司覆盖门失效，重新登记/回填后才恢复。
- 首个实际消费者仅为整个 RR 下的贴单期间汇总；不直接改动所有静态 firstSettlement 调用。
- 派生行缺失、模式不符、复制字段依赖不完整、脏域枚举不完整或局部预算超限时，仅在当前 RR 仍有效的条件下使用同 S 原生完整读取；整个 RR 已过期、rollbackOnly 或连接失效则整次 workspace 失败，不能只给 period 换事务，也不能截断脏身份再宣称完整。
- 全局 Mode 不代表获验范围；ACTIVE/SHADOW 均须匹配默认空的公司/日期 allowlist。新公司发现和 seed 完成不自动获准。
- 本阶段不能仅以首次事实版本缓存整页。
- 当前 worktree 为 `feature/finance-bi-performance`；尊重其他会话改动，执行前由 root 检查工作区/暂存状态，只提交本任务内容。
- 2026-11-14 编写本计划期间 root 回报 V159 已独立 accepted，生产 schema159/context `bdf70ffe`；本计划作者未接触服务器，也未独立复测。V158 是其他会话材料，必须保留。这是交接记录，不能代替下一次现场核对。
- 当前文档任务不执行任何 SSH、数据库、测试、Maven、Git、生产操作或源码修改。下面未勾选步骤均是后续执行动作。
- 集成测试仅由 root 明确授权的单一 Maven/target writer 运行 `python3 /tmp/finance-bi-performance-qa/run-db-tests.py ClassName...`，只连接 runner 创建的可销毁库，禁止业务库。
- 原型 SQL 先放 `src/test/resources/finance/first-settlement-facts-prototype.sql`。不预占 V160 或其他生产迁移号；定向迁移前现场复核下一空闲号并重新审查。已有 Flyway 文件和校验和不改。
- 新源表触发器不得在当前阶段安装到业务库；影子迁移/回填/生产激活各自必须有独立 review、维护工具、实际锁/门控和恢复证据。
- 不改变成本作业优先级、GIFT、退货不冲减规则、未知金额、真实零、活动贴单代次、广告重叠、历史 FX、原有权限和原始明细当前性。

## 已读代码与交付文件边界

首次计划审计时 `FinanceLabelCosts.workspace:210` 先 sourcePage、再 files/period/state，没有统一事务；Task4 是对此基线的独立修复计划，不将共享源码的并行修改状态当作本作者的实施成果；`FinanceRepository.readSnapshot:37` 已提供只读 RR；`repo.query` 已调用 `FinanceReportingMonths.constrain`。`FinanceCostReport.firstSettlementIdentity:15` 是独立原生候选对照，`FinanceLabelCosts` 的当前 LATERAL 是性能基线。V117 的源 `order_id VARCHAR(128)` 与 V137 的 totals `order_id VARCHAR(255)` 不同：事实保持源 128/原比较规则，长 totals ID 不能截断、不能预热插入事实、不能自动当 known-empty，必须计入 missing/native 预算并用完整字符串原生查询。

以下新增文件均位于现有 finance 包，不抽出通用事实框架：

| 文件 | 单一职责 |
| --- | --- |
| `src/test/resources/finance/first-settlement-facts-prototype.sql` | 隔离表/索引、三个独立触发器定义；与生产迁移路径分开 |
| `src/main/java/com/yingdao/aicommerce/finance/FinanceFirstSettlementFacts.java` | 本切片记录类型、候选 SQL、clean 判定、claim/snapshot/publish 存储接口 |
| `src/main/java/com/yingdao/aicommerce/finance/FinanceFirstSettlementInitialization.java` | 公司发现、稳定游标、模式升级和完成 CAS |
| `src/main/java/com/yingdao/aicommerce/finance/FinanceFirstSettlementWorker.java` | 单链有界调度、维护门、租约及公平轮转 |
| `src/main/java/com/yingdao/aicommerce/finance/FinanceLabelFirstSettlementFacts.java` | 完整 totals 分类、预算和八列 true adapter、期间汇总 |
| `src/main/java/com/yingdao/aicommerce/finance/FinanceLabelCosts.java` | 包裹实际 workspace RR、保留原生期间查询并路由 |
| `src/test/java/com/yingdao/aicommerce/finance/FinanceFirstSettlementTestSupport.java` | 仅新测试共用的隔离 schema、候选及两连接 latch 工具 |
| `src/test/java/com/yingdao/aicommerce/finance/FinanceFirstSettlementSchemaIntegrationTest.java` | schema/触发器事务、old/new、比较规则 |
| `src/test/java/com/yingdao/aicommerce/finance/FinanceFirstSettlementInitializationIntegrationTest.java` | 初始化/升级/游标/缺公司门 |
| `src/test/java/com/yingdao/aicommerce/finance/FinanceFirstSettlementWorkerIntegrationTest.java` | 实际 RR→CAS、租约和竞争时序 |
| `src/test/java/com/yingdao/aicommerce/finance/FinanceLabelFirstSettlementFactsIntegrationTest.java` | 消费、原生独立对照、预算、true/false 语义边界 |
| `src/test/java/com/yingdao/aicommerce/finance/FinanceLabelWorkspaceSnapshotIntegrationTest.java` | 实际 workspace/ detailsOnly 两连接一致性 |
| `src/test/java/com/yingdao/aicommerce/finance/FinanceFirstSettlementLoadIntegrationTest.java` | 有界重复负载和 EXPLAIN/读取量证据 |
| `docs/guides/finance-performance.md`、`docs/releases/finance/finance-workspace-performance-20261113.md` | 后续实施时由 root 更新当前行为、实际验收及回退入口 |

`FinanceFirstSettlementTestSupport` 在父类每次清理源表后（父类 DELETE 会运行新触发器），再清理本测试的新事实/init/registration 表并重建本例安装 proof 与 guard；只在隔离库允许这种测试 reset，不能写入生产清理路径。安装原型一次，测试之间不反复创建触发器。`FinanceFirstSettlementTestSupport` 继承现有 `FinanceConfigurationIntegrationTest` 以使用隔离 DB、scope/service 与现有迁移 fixture；不要继承带本切片测试方法的测试类来膨胀新增测试数。runner inventory 必须识别这些类为 `FINANCE_QA_JDBC`，缺失时显式添加类上的既有环境注解。统计新增声明测试与继承回归分别列出，零执行/环境 skip 不是 GREEN。

## 固定接口：所有任务共同使用

类型集中定义在 `FinanceFirstSettlementFacts`，以下是需落地的 Java 形状（嵌套类型统一以此类限定或 static import）：

```java
record Owner(String organizationId, String shopId, String orderId) {}
record Registration(int schemaVersion, int registrationVersion,
                    String definitionSha, String boundary, boolean enabled) {}
record Guard(String organizationId, long generation, int targetSchemaVersion,
             int registrationVersion, boolean seedComplete) {}
record MaintenanceCompanyState(String organizationId, long generation,
                               int targetSchemaVersion, int registrationVersion) {}
enum AdvanceResult { ADVANCED, ALREADY_TARGET, STALE, REJECTED }
record Lease(Owner owner, Guard guard, String token) {}
record Candidate(String id, java.time.LocalDate date, String platform, String currency) {}
record Computed(Lease lease, long revision, java.util.Optional<Candidate> first) {}
record Tick(int claimed, int published, int rejected) {}
record Batch(int visited, boolean complete) {}
record Period(java.math.BigDecimal amountCny, long orders, long conflicts, long shipments) {}
enum Mode { OFF, SHADOW, ACTIVE }
record ApprovedRange(String organizationId, java.time.LocalDate start, java.time.LocalDate end,
                     Mode mode, int schemaVersion, int registrationVersion, String evidenceId) {}
record CandidateBudget(long candidateMillis, long nativeReserveMillis, long completionReserveMillis) {}
enum Route { NATIVE, FACTS }
record Resolution(Route route, Period value, String fallbackReason) {}
```

`Owner` 保存完整输入；只有持久登记接受能与源列一一对应的 128 字符身份。不能在 Java 中 lower-case、trim 或用 Java 排序模拟 MySQL collation。长 totals 仅作读时 Owner，不进入持久表；owner 集合相等、去重和锁顺序以源列比较规则处理。

```java
// FinanceFirstSettlementFacts(FinanceRepository repo)
java.util.Optional<Registration> registration(); // 只读 singleton 安装证明
java.util.Optional<Guard> guard(String organizationId); // 同时验证全局 proof 与公司模式
java.util.Optional<Lease> claim(String organizationId, String token, int leaseSeconds);
Computed calculate(Lease lease);              // 自有只读 RR，实读 requested=R
boolean publish(Computed result);            // 自有短事务，守卫/lease/R CAS
static String eligible(String alias);        // confirmed(alias) + TikTok ORDER 谓词

// FinanceFirstSettlementInitialization(FinanceRepository repo)
Batch discoverCompanies(int limit);           // 有有效全局 proof 才建 generation=1；否则只建 0 待办
Batch seed(Guard expected, int ownerLimit);    // 最多推进一 shop/order 批，事务推进游标
Batch requeue(Guard expected, int ownerLimit);// 现有 owner 按主键分页，queued_generation 幂等
boolean complete(Guard expected);            // 所有必需游标完成 + 精确 guard CAS
java.util.Optional<MaintenanceCompanyState> maintenanceState(String organizationId);
// 维护专用：读持久旧行，不要求它已匹配新 singleton；普通 reader/worker 不调用。
AdvanceResult advance(MaintenanceCompanyState expected, Registration target);
// 验证目标 proof 后对持久旧状态 CAS；已到 target 无操作，不依赖安装前内存。

// FinanceFirstSettlementWorker(facts, initialization, int batchSize, int leaseSeconds)
Tick tick();                                 // 一次有界工作，维护门控制是否进入

// FinanceLabelFirstSettlementFacts(FinanceRepository repo, int dirtyCap,
//     List<ApprovedRange> allowedRanges, CandidateBudget budget)
boolean permitted(Mode mode, Scope scope, Filter filter, Registration registration);
Resolution period(Mode mode, Scope scope, Filter filter); // Task5：先检查 allowlist/预算；不另起事务/写状态
// 嵌套 ReadBudget 持有同一 workspace 的 Spring ConnectionHolder 与单调时钟：
// boolean canStartCandidate(Mode mode); void requireUsable();
// canStartCandidate=false 是候选局部早退；requireUsable 失败抛异常，不返回 NATIVE。

// FinanceLabelCosts：公开签名保持不变
Map<String,Object> workspace(Scope scope, Filter filter, Map<String,String> query);
Map<String,Object> workspaceSnapshot(Scope scope, Filter filter, Map<String,String> query);
Map<String,Object> nativePeriod(Scope scope, Filter filter); // 仅 Task5 新增；Task4 原 SQL 留 workspaceSnapshot
```

`Resolution` 明确区分完整事实结果与需要原生执行的路由：为避免循环依赖，reader 的 `period` 在不满足门槛时返回 `Resolution(NATIVE,null,reason)`，调用方必须先确认整个 RR 仍有效再执行 nativePeriod；此处 null 只表示未计算路由结果，不是金额未知或零。FACTS 时 value 非空；shadow 比较 FACTS 和 native 的四项规范化值，HTTP 返回 native。实现可在 record 构造器明确强制 `(route==FACTS)==(value!=null)`。所有 fallback reason 为固定枚举式短码，不包含业务标识或 SQL 参数。

## Task 1：隔离 schema 与源事务登记

**Files:** 新建原型 SQL、Facts 类型与 `eligible`，TestSupport、SchemaIntegrationTest。此任务不改生产迁移目录。

**Interfaces:** 消费原生 `FinanceCostReport.confirmed` 与源列 collation；产出持久表和三个触发器 `fin_fsf_records_ai/au/ad`，以及共同类型。既有 V155/V156 触发器继续存在。

- [ ] **1. 写 schema 与登记 RED。** TestSupport 的新方法 `installPrototype()` 仅在 JDBC URL 匹配现有 `finance_qa_[a-f0-9]+` 且连接目标等于 runner 目标时加载 test resource；用支持 compound trigger 的现有 Flyway SQL 解析路径在临时 location 测试，编号仅在隔离目录按已用号选下一空闲值，测试结束移除本任务临时文件。不得直接在普通 Spring 启动中加载 test SQL。RED 首轮尚无 SQL 时应是明确缺少原型表/触发器断言，不以连接失败代替。

```java
@Test void sourceRollbackAlsoRollsBackOwnerRegistration() {
    String shop = groupedShop(owner, "fsf-schema", null);
    new org.springframework.transaction.support.TransactionTemplate(tx).execute(s -> {
        db.update("""
          INSERT INTO finance_records(id,organization_id,user_id,shop_id,platform,kind,
          external_id,order_id,category,status,record_date,currency,source,raw_json)
          VALUES('fsf-a','org','u',?,'SHOPEE','SETTLEMENT','fsf-a','a',
          'settlement','SETTLED','2026-10-02','PHP','API','{}')
          """, shop);
        assertEquals(1L, db.queryForObject("SELECT requested_revision FROM finance_first_settlement_facts WHERE organization_id='org' AND shop_id=? AND order_id='a'", Long.class, shop));
        s.setRollbackOnly(); return null;
    });
    assertEquals(0L, db.queryForObject("SELECT COUNT(*) FROM finance_first_settlement_facts WHERE organization_id='org' AND shop_id=?", Long.class, shop));
    assertEquals(0L, db.queryForObject("SELECT COUNT(*) FROM finance_records WHERE id='fsf-a'", Long.class));
}
```

- [ ] **2. root 授权单 writer 执行 RED。** `python3 /tmp/finance-bi-performance-qa/run-db-tests.py FinanceFirstSettlementSchemaIntegrationTest`。保存测试名/失败断言和脱敏 XML；环境失败先修测试环境。
- [ ] **3. 实现专用 schema。** 表 `finance_first_settlement_facts` 使用源列精确类型、`utf8mb4_0900_ai_ci`，部署前 information_schema 校对实际源列；不附带源外键以免源删除重置状态。DDL 核心：

```sql
CREATE TABLE finance_first_settlement_facts (
 organization_id CHAR(36) NOT NULL, shop_id CHAR(36) NOT NULL,
 order_id VARCHAR(128) NOT NULL,
 requested_revision BIGINT NOT NULL DEFAULT 1,
 applied_revision BIGINT NOT NULL DEFAULT 0,
 has_settlement BOOLEAN NOT NULL DEFAULT FALSE,
 first_record_id CHAR(64) NULL, first_record_date DATE NULL,
 first_platform VARCHAR(24) NULL, first_currency VARCHAR(8) NULL,
 schema_version INT NOT NULL DEFAULT 0,
 applied_generation BIGINT NOT NULL DEFAULT 0,
 applied_registration_version INT NOT NULL DEFAULT 0,
 queued_generation BIGINT NOT NULL DEFAULT 0,
 lease_token CHAR(36) NULL, lease_until DATETIME(6) NULL,
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 pending BOOLEAN GENERATED ALWAYS AS (requested_revision>applied_revision) STORED,
 PRIMARY KEY(organization_id,shop_id,order_id),
 KEY idx_fsf_pending(organization_id,pending,shop_id,order_id),
 KEY idx_fsf_date(organization_id,first_record_date,shop_id,order_id),
 CHECK(requested_revision>0 AND applied_revision>=0 AND applied_revision<=requested_revision),
 CHECK((has_settlement AND first_record_id IS NOT NULL AND first_record_date IS NOT NULL
        AND first_platform IS NOT NULL AND first_currency IS NOT NULL)
    OR (NOT has_settlement AND first_record_id IS NULL AND first_record_date IS NULL
        AND first_platform IS NULL AND first_currency IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
```

增设仅属于本切片的安装证明表，解决新公司自动发现后的登记边界来源；它不是通用任务注册表。此决定已由 root 接受，作为相对 spec 的实现具体化，在执行前独立复审：

```sql
CREATE TABLE finance_first_settlement_registration (
 id TINYINT NOT NULL PRIMARY KEY CHECK(id=1),
 schema_version INT NOT NULL,
 registration_version INT NOT NULL,
 definition_sha CHAR(64) NOT NULL,
 installation_boundary VARCHAR(128) NOT NULL,
 enabled BOOLEAN NOT NULL DEFAULT FALSE,
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
```

默认无行/disabled，不伪造初始化证明。只有受控维护完成三个实际 trigger 的定义校验、安装前在途事务排空/覆盖后，才写 `id=1,enabled=true` 和已审查 schema/registration/hash/boundary。`registration()` 校验 supported schema/registration/definition hash 和 enabled；任一不匹配或缺行，全 native。源触发器始终登记 owner，不因 reader/worker 开关关闭而遗漏源事务。修改全局证明必须在维护锁/门控内；现有登记的改造先停领并排空、禁用 singleton proof，再安装任何新 trigger；中途失败保留禁用及本次恢复门，不以旧 enabled proof 放行。每次重新发布安装 boundary、定义或模式均单调推进 registration_version，不能以相同版本替换证明；所有既有公司在匹配新 registration/新 generation 前 native。worker publication 使用全局 proof 共享锁，维护修改使用排他锁，锁顺序统一为 singleton→公司→owner。新公司从有效 singleton 复制的是安装证明，而不是旧公司 seed_complete；自身新建 generation=1、所有游标未完成、seed_complete=false。没有 proof 可先创建 generation=0 待办，后续工作器只有在有效 proof 建立后原子升级该未初始化待办；不能重置已开始的公司代次或 revision。

初始化表 `finance_first_settlement_initialization` 字段精确定义为：`organization_id CHAR(36) PK`；`generation BIGINT DEFAULT 0`、`target_schema_version INT DEFAULT 0`、`registration_version INT DEFAULT 0`；`registration_definition_sha CHAR(64) NULL`、`registration_boundary VARCHAR(128) NULL`（只存维护操作/证据 ID，无凭据）；`seed_complete BOOLEAN DEFAULT FALSE`；`shop_after CHAR(36) NULL`、`current_shop CHAR(36) NULL`、`order_after VARCHAR(128) NULL`、`shops_complete BOOLEAN DEFAULT FALSE`；`requeue_shop_after CHAR(36) NULL`、`requeue_order_after VARCHAR(128) NULL`、`requeue_complete BOOLEAN DEFAULT FALSE`；`updated_at DATETIME(6)`。同 collation。代次 0 无登记证明，reader 必须 native；generation>=1 必须来自受控维护验证后发布的 singleton 安装证明或隔离测试 fixture。可选预热本原型不实现，因此没有阻塞 seed 的预热游标。

- [ ] **4. 实现三个独立触发器。** `eligible(a)` 具体为 `FinanceCostReport.confirmed(a)+" AND ("+a+".platform<>'TIKTOK_SHOP' OR "+a+".category='ORDER')"`。INSERT 登记 NEW eligible owner；DELETE 登记 OLD eligible owner；UPDATE 仅在下列 null-safe 字段比较变化且 OLD/NEW 至少一侧 eligible 时登记两侧仍/原来 eligible 的 distinct owner：

```sql
NOT (OLD.organization_id <=> NEW.organization_id AND OLD.shop_id <=> NEW.shop_id
 AND OLD.order_id <=> NEW.order_id AND OLD.kind <=> NEW.kind
 AND OLD.source <=> NEW.source AND OLD.status <=> NEW.status
 AND OLD.platform <=> NEW.platform AND OLD.category <=> NEW.category
 AND OLD.record_date <=> NEW.record_date AND OLD.id <=> NEW.id
 AND OLD.currency <=> NEW.currency)
```

对于两 eligible owner，先用源 collation 判等；相同身份只递增一次，否则按 SQL `(organization_id,shop_id,order_id)` 顺序执行两个 upsert。每次 upsert 只写 owner/default revision，重复时 `requested_revision=requested_revision+1,updated_at=UTC_TIMESTAMP(6)`；保留 applied、已发布事实和 lease。禁止 `REPLACE`、删除重建、事件 ID 高水位和 `applied=requested`。金额/raw_json/sync-only 更新不增加此身份修订，但原触发器保持自己的金额/明细失效。同一事务多行交叉更新仍可能死锁，不能假定单行排序消除全部死锁。

- [ ] **5. 补齐具体登记断言并 GREEN。** SQL 直写、repo.save/upsert、删除和 rollback 都覆盖；winner 不变时 platform-only/currency-only 更新必须 +1；eligible↔非 API、取消、TikTok 非 ORDER、空订单、日期/ID 变化逐项覆盖；同 owner 双候选混合 TikTok 类别必须登记有效候选；旧新公司/店铺/订单各迁移一次，两行都 dirty；同一事务失败无半个 owner；大小写/重音/尾空格按实际源 collation 对照，不能产生两个 Java 分支身份。两个写者交换 owner 时允许整事务失败并由调用者有界重试，但成功提交必须两端原子登记；核对真实受支持写路径的事务重试/错误反馈，禁止只在触发器吞 deadlock。
- [ ] **6. 验证索引/保留旧协议并独立提交。** INFORMATION_SCHEMA 比对新列/索引、源列以及既有全部事件触发器定义 hash；EXPLAIN `WHERE organization_id=? AND pending=1 ORDER BY shop_id,order_id LIMIT ?` 必须走 pending 索引且领取不是全表表达式扫描。root 复核 diff 后按上述本任务文件单独 stage/commit，记录 `feat: prototype transactional first settlement registration`。

## Task 2：公司发现、稳定种子游标和模式升级

**Files:** 新建 Initialization 与 InitializationIntegrationTest；Task1 表字段不重命名。必要 TestSupport fixture 只追加本任务方法。

**Interfaces:** 消费 Guard/Batch/MaintenanceCompanyState/AdvanceResult 和事实表；实现共同接口 `discoverCompanies/seed/requeue/complete/maintenanceState/advance`。普通 seed/requeue/complete/claim/publish 必须先验证 singleton 与公司当前模式匹配；普通 `guard(org)` 不匹配时返回 empty。`maintenanceState(org)` 是维护专用持久旧状态读取，不调用普通 guard、不依赖旧行匹配新 proof；`advance` 是唯一可从旧登记版本推进已有公司代次的维护 CAS。discoverCompanies 只创建缺失/从未初始化任务，不修改已有活动代次。

- [ ] **1. 写 RED：中断重放不重置 revision。** 建 org/shop 两个 owner；将 A requested=7/applied=6，执行一批 seed 后 A 保持 7/6，新 B 是 1/0。强制在 owner insert 后、游标 update 前抛异常，事务全部回滚；再次运行只登记未覆盖批次。最终 `complete` 在游标未结束返回 false。

```java
@Test void missingCompanyIsNotReadyAndReadDoesNotCreateIt() {
    var facts = new FinanceFirstSettlementFacts(repo);
    assertTrue(facts.guard("new-company").isEmpty());
    assertEquals(0L, db.queryForObject("SELECT COUNT(*) FROM finance_first_settlement_initialization WHERE organization_id='new-company'", Long.class));
}
```

其余 fixture 用 TestSupport 的 `installPrototype()` 和 SQL 明确插入初始化行，registration hash/boundary 仅用测试值；不伪造生产维护通过。
- [ ] **2. root 执行 RED。** `python3 /tmp/finance-bi-performance-qa/run-db-tests.py FinanceFirstSettlementInitializationIntegrationTest`，期望失败于缺少初始化能力或游标/CAS 断言。
- [ ] **3. 实现稳定 shop→order 枚举。** `discoverCompanies(limit)` 按源财务 `finance_records.organization_id` 和 `finance_connections.organization_id` 的去重公司键分页，若 singleton 安装证明有效则创建独立 generation=1 任务并复制该 proof 的 target/registration/hash/boundary，seed_complete=false；否则创建 generation=0 待办。后续有 proof 时仅将从未初始化的 generation=0 待办升级为 1，旧公司不得借新公司发现降级/重置状态。发现游标保存在工作器内存并循环，重启可重扫，不写请求线程。seed 必须 guard.registrationVersion>0 且全局 proof 一致。取下一个 shop 时用源 collation `shop_id>shop_after ORDER BY shop_id LIMIT 1`，遍历该公司已有源记录，不受当前月份/平台或连接是否可见限制；持久化 current_shop。该 shop 的候选按以下固定谓词分页：

```sql
SELECT DISTINCT r.order_id
FROM finance_records r FORCE INDEX(idx_finance_first_settlement)
WHERE r.organization_id=? AND r.shop_id=? AND r.kind='SETTLEMENT'
 AND r.source='API' AND r.status='SETTLED' AND r.order_id<>''
 AND (r.platform<>'TIKTOK_SHOP' OR r.category='ORDER')
 AND r.order_id>?
ORDER BY r.order_id LIMIT ?;
```

首次 order cursor 为 NULL 时省略 `order_id>?` 而非猜测最小字符串。资格在所有行上 WHERE 后 DISTINCT，不从随机一行类别决定 owner。每批对结果执行 `INSERT ... ON DUPLICATE KEY UPDATE order_id=order_id`（只确保 missing），随后同事务推进 order_after；该 shop 空批后更新 shop_after/current_shop/order_after。最终无 shop 后设置 shops_complete。登记安装后的新增 shop、游标前后新订单及源删除由触发器覆盖，MAX ID 不是完成证据。
- [ ] **4. 实现维护恢复 CAS 与重新入队。** 新 singleton proof 发布后，旧公司普通 `guard(org)` 正确返回 empty；维护进程即使此时重启，也须调用 `maintenanceState(org)` 从初始化表直接 SELECT organization/generation/target/registration 重建 expected，而非借用安装前 Java 对象或放松 reader guard。`advance(expected,target)` 在维护授权入口的短事务中按 singleton FOR SHARE→公司 FOR UPDATE 锁序执行以下固定分支：

```text
1. 目标 target 必须与持久 singleton 的 enabled/schema/registration/hash/boundary 全部一致，
   且受本维护程序支持；否则 REJECTED，不写状态。
2. 直接读取持久公司行；缺行则 REJECTED（新公司由 discoverCompanies 处理）。
3. 当前公司 schema/registration 已等于 target 时返回 ALREADY_TARGET，不递增 generation、
   不清 seed、不重置游标；安装后重复调用或重启重放都遵守此分支。
4. 当前 company 四字段与 expected 不同则 STALE；任何 target schema/registration 降级
   或相同 registration 却变更模式均 REJECTED。
5. UPDATE ... WHERE organization_id=? AND generation=? AND target_schema_version=?
   AND registration_version=?，原子 generation+1、target/proof 更新、seed_complete=false、
   清新代次游标；受影响一行返回 ADVANCED，否则 STALE。
```

此维护 CAS 允许旧公司 registration 与新 singleton 不同；不能先要求普通 guard 成功。fact revision、已发布值、lease 不清空。`requeue` 按事实主键 `(shop_id,order_id)>cursor` 有界读取所有 owner；对于 `queued_generation<newGeneration` 且应用 schema/generation/registration 不匹配者 `requested_revision=requested_revision+1,queued_generation=newGeneration`；匹配者也可标记 queued_generation，但不得反复加 revision。新 owner applied=0 自然 pending。游标写入同一事务；旧 worker 使用旧 guard 发布失败。
- [ ] **5. 完成 CAS 及 GREEN。** `complete(expected)` 仅在 guard 所有字段精确一致、registration proof 非空、shops_complete=true、requeue_complete=true 时设置 seed_complete。首次 generation 的 requeue 也扫描现有 owner；不得以“没有 pending”代替两游标完成，seed_complete 不要求全部应用追平。回归：新公司无 init；global proof 缺失/disabled/模式或定义 hash 不匹配；有效 proof 下新公司独立 generation=1 且不能借旧公司的 seed；空公司；游标前/后插入与删除；跨公司/店铺/订单迁入；批次前后崩溃；旧 seeder 不能完成新 generation；旧模式 requested=applied、queued_generation 已标记但尚未应用都不能 clean；旧版本进程不能降级 target；长 totals ID 不插入 128 列。
- [ ] **5a. Q1 中断恢复 GREEN。** 建公司 `generation=g,registration=1,seed_complete=true` 并保留旧 worker；发布有效 proof2 后模拟丢弃全部维护实例/expected 内存，新建维护实例。断言 reader native、旧 worker publish=false；从 maintenanceState 读旧持久行并 advance 为 `g+1/proof2`；重复旧 expected 调用返回 ALREADY_TARGET 且 generation/游标不动。再从重启后的新状态重放相同 target 仍不动；proof3 已推进时旧 target2 被 REJECTED，不降级。requeue/seed 完成后通过普通 guard，是否消费仍由 Task5 allowlist 控制。
- [ ] **6. 独立审查/提交。** reviewer 按 B1/B2 对照每个完成门与 SQL WHERE，确认升级后未应用 owner 都在 pending 索引，未扫描旧模式 owner 仍可被 reader 分类为 native。root 只提交本任务文件，记录 `feat: add bounded first settlement initialization`。

## Task 3：有界租约工作器与 RR→CAS 发布

**Files:** 完成 Facts 存储方法、新建 Worker、WorkerIntegrationTest。

**Interfaces:** 实现 claim/calculate/publish/tick；消费 Task2 Guard，公司 target 未登记或 generation=0 时不能领取/发布。应用 schema 常量初值 1；只能计算本进程支持的 target。

- [ ] **1. 写 RED：计算读取 R 后发生变更不可发布。** 测试实际 calculate 返回 Computed，另一个连接更早插入候选并提交，publish 返回 false，applied 不推进且 requested>applied。至少以下断言固定：

```java
var lease = facts.claim("org", java.util.UUID.randomUUID().toString(), 30).orElseThrow();
var old = facts.calculate(lease);
try (var connection = dataSource.getConnection()) {
    connection.setAutoCommit(false);
    try (var statement = connection.prepareStatement("""
        INSERT INTO finance_records(id,organization_id,user_id,shop_id,platform,kind,
        external_id,order_id,category,status,record_date,currency,source,raw_json)
        VALUES(?,?,'u',?,'SHOPEE','SETTLEMENT',?,?,'settlement','SETTLED',?,'PHP','API','{}')
        """)) {
        String id = java.util.UUID.randomUUID().toString();
        statement.setString(1,id); statement.setString(2,lease.owner().organizationId());
        statement.setString(3,lease.owner().shopId()); statement.setString(4,id);
        statement.setString(5,lease.owner().orderId());
        statement.setDate(6,java.sql.Date.valueOf(old.first().orElseThrow().date().minusDays(1)));
        statement.executeUpdate(); connection.commit();
    } catch (Exception failure) { connection.rollback(); throw failure; }
}
assertFalse(facts.publish(old));
assertTrue(db.queryForObject("SELECT requested_revision>applied_revision FROM finance_first_settlement_facts WHERE organization_id=? AND shop_id=? AND order_id=?", Boolean.class,
    old.lease().owner().organizationId(), old.lease().owner().shopId(), old.lease().owner().orderId()));
```

第二连接以 `dataSource.getConnection()` 创建并显式 commit/rollback；测试不能在同线程共享 Spring 绑定连接模拟并发。用 CountDownLatch（最多 10 秒）协调，finally 关闭连接/执行器；不用 sleep 猜顺序。
- [ ] **2. root 执行 RED。** `python3 /tmp/finance-bi-performance-qa/run-db-tests.py FinanceFirstSettlementWorkerIntegrationTest`。
- [ ] **3. 实现 claim。** 短事务以 singleton 共享锁验证安装 proof、当前读 Guard 后从 pending 索引 `FOR UPDATE SKIP LOCKED` 有界取一行，排除未到期 lease，设置 UUID token 与 DB `UTC_TIMESTAMP(6)+leaseSeconds`。禁止在 claimant 长计算期间锁源行。多个进程可争用同一链，但 token/CAS 保证不覆盖；失败租约到期可重新领取。公平轮转每个有任务公司最多 batchSize，再继续下个公司；Facts 内保存每公司的上次领取 owner 游标，按 pending 主键从其后领取、到末尾再绕回，避免持续变化的低位 owner 永久挤占队尾，重启可从头扫描且 lease/CAS 仍负责跨进程安全；批次、lease 与 tick deadline 为显式配置，默认调度关闭。
- [ ] **4. 实现 calculate 的独立 RR。** 用 REQUIRES_NEW + readOnly + ISOLATION_REPEATABLE_READ + 有界 timeout 新事务，普通一致性 SELECT 读取 Guard、fact requested=R、完整源历史；不先用 FOR UPDATE 产生错误快照边界。读取 lease/guard 不符直接拒绝本轮。查询只按完整 owner 和 eligible，排序同现有覆盖索引等值前缀后 `record_date,id LIMIT 1`；无行表示 Optional.empty。R 和候选必须来自同一快照，不能用 claim 时看到的 revision 代替。事务结束后才 publish。
- [ ] **5. 实现短事务 publish。** 统一锁顺序：singleton 安装 proof FOR SHARE→初始化公司行→owner 行（所有维护方法同顺序），均为当前读。global proof 必须 enabled 且 schema/registration/definition hash 与本次计算及进程支持值一致。SQL 条件必须同时包含 organization、target_schema、generation、registration_version、lease_token、`lease_until>UTC_TIMESTAMP(6)`、`requested_revision=Computed.revision` 和工作器支持模式。写 has_settlement、四个复制字段、`schema_version=guard.targetSchemaVersion`、`applied_generation=guard.generation`、`applied_registration_version=guard.registrationVersion`、`applied_revision=Computed.revision`，清本 token lease；失败零行不得推进 applied。无候选原子清空四字段，known-empty 仅在成功应用后成立。事务异常回滚事实/版本所有写入，重试后重新读 R 计算。

```java
// publish 的边界，不是“读取当前 requested 再覆盖”的算法。
var expected = computed.lease().guard();
if (!currentGuard.organizationId().equals(expected.organizationId())
    || currentGuard.generation()!=expected.generation()
    || currentGuard.targetSchemaVersion()!=expected.targetSchemaVersion()
    || currentGuard.registrationVersion()!=expected.registrationVersion()) return false;
// UPDATE ... WHERE requested_revision=? AND lease_token=? AND lease_until>UTC_TIMESTAMP(6)
// applied_revision 参数只能 computed.revision()；never current requested。
```

Guard 的 seedComplete 不是发布版本：实现比较 organization/generation/target/registration 四字段，允许同代次 seed_complete 变 true；不可直接用整个 record.equals 将 seed 完成误判成版本变化。
- [ ] **6. 补齐两连接 GREEN。** 低 ID 源事务先开始后提交；R 后 update/delete/日期跨月；租约被替换/过期；发布后源写者再提交使 requested 增加；publish 中途抛错保留旧事实；两个旧新 owner 交换写入 + worker；旧 schema/generation/registration/token/R 每一项不符均拒绝。发布无候选后重新插入能 dirty。deadlock 仅允许完整事务最多 3 次重试并报告仍失败，不隐去源写失败；测试验证每次成功源提交都有登记，不要求不可能的零死锁。
- [ ] **7. 独立提交。** root 查测试 XML 有真实两连接执行且超时测试无悬挂事务，记录 `feat: publish first settlement facts with revision CAS`。

## Task 4：真实 workspace 的完整 RR 边界

**Files:** 修改 `FinanceLabelCosts.java`，新建 WorkspaceSnapshotIntegrationTest；本任务仍 native 期间查询。

**Interfaces:** workspace 公开签名及原三参构造器不变；Task4 只新增 package-private workspaceSnapshot 和显式 `readSnapshot(30,...)`。原 period SQL 完整留在 workspaceSnapshot；nativePeriod 抽取、路由注入和所有新事实依赖均延至 Task5。

- [ ] **1. 写实际入口 RED。** 用代理 JdbcTemplate 在 sourcePage 首个一致性 SELECT 返回后触发 latch；第二连接同事务切 active_generation、更新其 totals、files/import 与 label state。A 继续执行实际 `labels.workspace(...)`，断言 source rows/count/period/files/state/processing 都属于旧 S；新请求看到新代次。当前没有 workspace RR 时此测试应产生明确混合结果。再对 detailsOnly 在 rows 与 count/enrichment 间做同类变更，不在测试外层调用 repo.readSnapshot。
- [ ] **2. root 执行 RED。** `python3 /tmp/finance-bi-performance-qa/run-db-tests.py FinanceLabelWorkspaceSnapshotIntegrationTest`。
- [ ] **3. 最小实现。** 把原 workspace 的全部主体移入 workspaceSnapshot，原 period SQL/参数构造完整保留在该方法，公共入口只做：

```java
public Map<String,Object> workspace(Scope scope, Filter filter, Map<String,String> query) {
    return repo.readSnapshot(30, () -> workspaceSnapshot(scope, filter, query));
}
```

workspaceSnapshot 开始断言当前连接只读 RR（沿用 Repository.withAuthoritativePageTotal 的同类检查）；不允许内部 RC/REQUIRES_NEW helper 或仅 period 包事务。sourcePage、files、原 period、当前校正/营销与 state 共用 repo 的 DataSource；Task4 不引入 init/facts 查询。detailsOnly 在此内部早返回，因此也受快照保护。已有 HTTP authorize 保持，visible/group 在 S 内执行。
- [ ] **4. GREEN 与兼容性。** root 运行 `python3 /tmp/finance-bi-performance-qa/run-db-tests.py FinanceLabelWorkspaceSnapshotIntegrationTest FinanceLabelFirstSettlementRowIntegrationTest FinanceLabelCostsIntegrationTest`。保留原四指标、files/50 行/total、授权、detailsOnly 响应字段与原排序；测试实际调用路径并断言使用显式 30 秒 RR deadline。30 秒由 root 指定为前端既有读取 deadline 与已观测 group 18.611 秒仍未完成的兼容边界，避免默认 15 秒提前截断已有请求；它不是五秒通过，也不保证端到端请求在 30 秒内成功。后续 Task5/6 保持该整体边界，只在其内选择候选局部预算/回退余量；整体 deadline 变更须另有当前证据与 review。
- [ ] **5. 独立提交。** root 记录 `fix: read label workspace in one repeatable read snapshot`，生产是否先启用该边界仍按 root 维护时序决定。

## Task 5：完整 totals 预算与首个事实消费者

**Files:** 新建 LabelFirstSettlementFacts、FactsIntegrationTest；修改 LabelCosts 接入 OFF/SHADOW/ACTIVE，默认 OFF；在现有 `src/test/java/com/yingdao/aicommerce/finance/FinanceLabelFirstSettlementRowIntegrationTest.java` 加 OFF 不依赖新表回归。

**Interfaces:** 消费 Guard/Resolution/Period/ApprovedRange/CandidateBudget；reader 不写初始化、revision、lease 或临时共享表，不另开事务。Task5 此时才把 workspaceSnapshot 内原 period SQL 原样抽为 nativePeriod。全局 Mode 与 allowlist 配置在 workspace 开始时冻结为同一份不可变快照；OFF 或范围未获准时不访问新事实表。

- [ ] **1. 写 RED：missing、known-empty、dirty 不可混为零。** 活动 totals 建三个 owner：clean known-empty、没有 facts、requested>applied 且早日期改变；同时加旧 schema requested=applied、未匹配 init/new company、255 长 ID 与其前 128 字符的另一订单。以 cap=2/3/4 做临界预算；未知项超限必须 native，cap 内每个完整 owner 都原生查询，长 ID 不匹配截断前缀。原生四指标与返回逐项一致。

```java
@Test void offRouteNeverNeedsFactTables() {
    fixture(); // 此方法已存在于 FinanceLabelFirstSettlementRowIntegrationTest，未安装新 schema。
    var result = labels.workspace(owner, filter(OCTOBER_1,OCTOBER_31,"","","",""), Map.of());
    assertNotNull(result.get("period"));
    assertNotNull(result.get("files"));
}
```

该短测试放在 Task5 Files 明确列出的既有 FirstSettlementRow 测试类，复用其 fixture/filter/constants；其断言之外扩展该类现有 TracedJdbc 的 SQL 记录，确认整次 workspace SQL 不包含 `finance_first_settlement_`。
- [ ] **2. root 执行 RED。** `python3 /tmp/finance-bi-performance-qa/run-db-tests.py FinanceLabelFirstSettlementFactsIntegrationTest`。
- [ ] **3. 实现完整分类/预算。** 先按步骤5检查默认空 allowlist；范围获准且预算允许才在 S 读公司 Guard；singleton 缺失/disabled/版本或定义 hash 不匹配、无登记、缺 init/不支持 target/定义不完整直接 native。seed 未完成时仍可从完整当前 `ls.active_generation` totals 分类，不能用 facts 表反推 totals 域。clean 必须满足 `applied_revision=requested_revision>0 AND schema_version=target AND applied_generation=generation AND applied_registration_version=registration`；queued_generation 不是 clean 条件。missing、applied=0、旧模式/代次/登记、pending 一律 unresolved。

先用公司 pending 索引读取至 cap+1，不加日期/币种/平台过滤；可先保守包含不可见 owner，额外回退合法，不能遗漏可见 owner。另从完整活动 totals LEFT JOIN facts/Guard 列出 missing/不兼容（包括 requested=applied）的 distinct owner 至 cap+1。以 MySQL 身份比较规则合并两个有界集合，达到 cap+1 立刻整条 native；不能分别都 cap 内却合计超 cap。若任一枚举不能证明完整或候选局部时间/读取量预算提前耗尽，仅在步骤5a确认整个 RR 仍有效后转同 S native；SQL/事务 timeout、rollbackOnly、连接失效一律抛出并使整次 workspace 失败。预算内公司 dirty 全部解析，再只把对应活动 totals 参与汇总；不先做无界 COUNT，也不生成无界 IN。新 totals missing 即使 seed_complete=true 也必须识别。
- [ ] **4. 实现八列 relation。** clean/known-empty 和 unresolved 两支由同一个完整 totals 域分类；known-empty 不输出 candidate，但保留其覆盖证明。每个 unresolved 用当前 S 内完整历史 eligible LATERAL/有界绑定 owner 查 earliest。两支 `UNION ALL` 的 r 列固定为：

```sql
id, organization_id, shop_id, order_id, record_date, platform, currency, status
-- clean: first_record_id, owner 三列, first_record_date,
--        first_platform, first_currency, 'SETTLED'
-- dirty/missing: 原全历史候选 r0 的对应八列
```

在 reader 内构建限定本调用方的 SQL derived relation，不向其他静态 firstSettlement 调用推广、不伪造 category/kind/source。clean 的 status=SETTLED 由 eligible 发布不变量保证。统一关系外仍调用原 `repo.query(scope,filter,"r")` 和 `FinanceOrderCharges.normalOrder("r")`；query 已追加 reporting constrain，不再重复追加。汇总保持原 COUNT、COALESCE SUM 与当前代次 totals，规范化 BigDecimal 数值比较不比较 JSON 字段顺序。权限/UNASSIGNED/removed/group 必须经原 query；校正按 winner ID 与 S 中 raw/correction 子查询，raw provenance 改变而复制字段不变仍即时反映。
- [ ] **5. 接入默认空的范围配置。** 三参旧构造器默认 OFF/空 allowlist；Spring 默认 `finance.first-settlement-facts.mode=OFF`、`finance.first-settlement-facts.allowed-ranges=[]`、worker disabled。allowed-ranges 是严格解析的 ApprovedRange 列表：每项需显式公司 ID、闭区间 start/end、SHADOW 或 ACTIVE、已验 schema/registration 和非空 evidenceId；不接受通配公司、无界日期、OFF 条目或 start>end，非法配置关闭事实路由并报告短错误。隔离测试显式注入 synthetic A/B/C grant；生产由受控维护根据验收回执配置，不从公司数据自动生成。

`permitted(mode,scope,filter,registration)` 仅当全局 mode 非 OFF，且**同一条** grant 精确匹配 mode/授权后的 company ID/schema/registration，且 `grant.start<=filter.start && filter.end<=grant.end` 时为 true。不得将分散 grant 拼接成未获验日期范围；跨出任何一端整条 native。每项 grant 表示该公司/日期内原接口支持的筛选组合均获验；如只测了部分筛选，不建立宽 grant。先仅用不可变配置检查 company/mode/日期是否有候选 grant，未命中不查任何新表；命中后才在 S 读取 Registration 并调用 permitted 做完整版本匹配，随后查询事实 Guard。自动发现新公司、初始化完成、seed_complete 或 clean 数量都不修改 allowlist。

```java
boolean permitted(Mode mode, Scope scope, Filter filter, Registration registration) {
    return mode != Mode.OFF && registration.enabled() && allowedRanges.stream().anyMatch(g ->
        g.mode() == mode && g.organizationId().equals(scope.organizationId())
        && g.schemaVersion() == registration.schemaVersion()
        && g.registrationVersion() == registration.registrationVersion()
        && !filter.start().isBefore(g.start()) && !filter.end().isAfter(g.end()));
}
```

SHADOW 只有对应 SHADOW grant 才在同一个 workspace RR 内先算 native、后按预算算 candidate，HTTP 只返回完整 native；ACTIVE 只有 ACTIVE grant 才尝试事实，按5a决定能否同 S native 回退。全局模式切换不会自动把 SHADOW grant 变为 ACTIVE。detailsOnly 不额外跑 period。受控 logger 仅记录 route/reason/耗时/行数和规范化哈希，禁止金额、订单/店铺标识和原 SQL 参数；持久日志沿用现有策略。

- [ ] **5a. 区分候选早退与 RR 失败。** workspace 整体仍是 Task4 的 `readSnapshot(30,...)`。CandidateBudget 三个正值满足 `candidateMillis+nativeReserveMillis+completionReserveMillis<30000`；生产 grant 启用前须有 Task6 实测选出的明确值，未配置时跳过 candidate。候选开始前使用当前 Spring ConnectionHolder 的真实剩余 deadline，而非从 period 再起30秒。ACTIVE 为完整 nativePeriod 与后续 state/响应装配预留 `nativeReserveMillis+completionReserveMillis`；SHADOW 已算 native，只预留 completionReserveMillis。候选局部截止为 `min(开始时间+candidateMillis,整体截止-相应余量)`，使用单调时钟在每个有界枚举/lookup 前检查。剩余量不足则不启动下一候选 SQL，返回 NATIVE 路由；候选 SQL 的逐 statement timeout 同时受候选剩余预算与整体剩余 deadline 上限约束（使用当前 statement 的设置，不修改共享 JdbcTemplate 的全局 timeout）；不足驱动支持的最小精度则提前跳过该 candidate SQL，不能无界执行来等待下次软预算检查。statement 超时按下面的整次失败规则处理，不冒充可安全 fallback 的主动早退。

嵌套 ReadBudget 的 `requireUsable()` 读取与 repo 相同 DataSource 绑定的 `ConnectionHolder`，要求实际事务活跃、只读 RR、holder 存在且 `hasTimeout()`、`getTimeToLiveInMillis()>0`、`!isRollbackOnly()`、连接未关闭；任何检查异常直接传播。数据库连接失败可能在下一 SQL 才暴露，该 SQL 异常也直接传播；不能因先前检查通过就吞掉失败。候选 SQL 的任何异常（包括 query timeout/cancel）都按整 workspace 失败处理，本版不猜测驱动还能否安全复用。只有主动的候选局部时间/行数/cap 早退而事务检查有效才允许同 S native。

```text
ACTIVE candidate 主动早退 → requireUsable → 在同连接/S 调 nativePeriod → state → 完整返回。
SHADOW candidate 主动早退 → requireUsable → 使用已在 S 算完的 native → state → 完整返回。
RR deadline 到期 / rollbackOnly / 连接失效 / SQL异常 → 传播异常，丢弃全部响应片段。
```

本版不自动重试整次请求；调用者若重新请求，必须从 workspace 入口重新执行全部 source/files/period/state。禁止只给 period 新开事务拼接旧 source/files，也禁止 SHADOW 用已算 native 掩盖失效事务后的 state 失败。余量是启动候选的门槛，不保证 native 在波动负载下必能完成；后续 native 超时仍整次失败。

- [ ] **5b. Q2/Q3 明确回归。** A/B 均有有效 Guard，仅 A 的指定日期有 ACTIVE grant：A 命中事实，B native；自动发现 C 并完成 seed 后 C 仍 native；A 越界日期/部分跨出区间 native，空 allowlist 全 native，切换 SHADOW 不沿用 ACTIVE grant；为 A 显式配置 SHADOW grant 后才执行双算。移除 grant 后确认事实/登记/revision 不变。

两类预算测试调用实际 workspace 入口：①注入可控单调时钟，使候选达到局部截止而 Spring holder 仍有效；另一连接此时提交变化，断言 native fallback 仍来自原 S，返回 source/files/period/state 完整旧视图，且所有 SQL 同一连接。②在候选前的测试 latch 将实际绑定 holder `setTimeoutInMillis(0)`（另测 `setRollbackOnly()`、关闭物理连接）后继续；断言整 workspace 抛异常、没有执行 nativePeriod fallback、没有返回部分 map、没有新建 period 事务；SHADOW 即使已算 native 也同样失败。对候选 SQL query-timeout 异常再做一例传播断言，不能算成局部预算耗尽。
- [ ] **6. 逐项等价 GREEN。** 独立 oracle 直接调用 `FinanceCostReport.firstSettlementIdentity("r",true)`，不引用新 eligible/adapter/clean predicate；并与保留的 native LATERAL 各对照。fixture 包括同日多行多币种、TikTok 非 ORDER + ORDER、取消/非 API、营销、真实零、负金额/六位精度、组织/移除/UNASSIGNED/组/UNGROUPED、部分归属月与跨月请求、首笔删除/移月、winner 不变只改 platform/currency、raw reporting provenance-only 更新。读时校正/营销改变不需要 pointer revision，但结果须变化。测试公司内 dirty 不在请求月份仍补齐，跨月旧结果正确移除；cap/cap+1 无丢行。
- [ ] **7. 两连接消费 GREEN。** actual workspace A 已读取 sourcePage 建立 S；B 在分类前后发布事实/dirty clean owner、切 label generation 或完成初始化。A 在 S 内分类/汇总一次，不重不漏；下一请求见新状态。重复 true 同日两币种、跨月第一日期移动。以新类加 Task4 类运行，超时或 fallback 比率不能当等价通过。
- [ ] **8. 明确 false 尚未激活并验证防串用。** 所有现有 false 调用继续原 SQL，回归 `FinanceCostReport.related` 的 DISTINCT 和 ORDER/REFUND 多源记录。新增测试仅在隔离 SQL 用 `r.record_date=f.first_record_date AND eligible(r)` 复接全部首日行，再逐行使用原 platform/currency/reporting-ID，证明该 relation 与原 `firstSettlement(...,false)` 等价；禁止使用 winner currency 或 `r.id=f.first_record_id` 替代。此测试提供未来接口证据，不增加本轮公开 false 路由。
- [ ] **9. 独立提交。** root 记录 `feat: add guarded label first settlement fact route`；审查确认默认 OFF、测试确实执行新 route 和实际入口，而非都在 fallback 下假通过。

## Task 6：隔离计划/服务能力实验与 shadow 准入

**Files:** 新建 LoadIntegrationTest；更新两份现有常驻性能文档由 root 负责，原始脱敏回执留本地 QA 目录。不得用本任务宣布整体五秒通过。

**Interfaces:** 使用实际 workspace、Worker.tick、Initialization 批次与原生 oracle；不另建影子业务公式。所有实验开关/参数记录到回执，生产值必须来自证据。

- [ ] **1. 建可重放、有界实验。** seed 固定随机种子 20261114，owner 集合规模 1k/10k/50k，每个 owner 候选 1/4/16 行；clean、1%、10%、持续变更三类负载，各运行最多 120 秒且每类不超过 200 请求。并发只 1/2/4，批次 16/64/256，dirty cap 0/16/64/256；整体 workspace RR 固定30秒，候选局部预算实验档为100/250/500/1000ms，native预留档为5000/10000/20000ms，completion预留档为1000/2000/5000ms，仅采用三者之和<30000ms的组合。以上仅是隔离实验输入，不是生产默认承诺；每次只改变一因素。单 Maven writer 串行运行，DB 仅隔离，硬截止后终止本测试创建的执行器/事务并确认退出。数据超边界时分批，不扩大为全业务导出。
- [ ] **2. root 运行隔离实验。** `python3 /tmp/finance-bi-performance-qa/run-db-tests.py FinanceFirstSettlementLoadIntegrationTest`。实验为测试公司/日期显式注入对应 mode 的 allowlist 和预算，必须预先调用真实 new route 并断言非 fallback 样本存在；每轮在同一真实 workspace RR 中 native 与 facts 做四值规范化哈希/数量对照，零差异为硬门。零差异只证明这批样本。
- [ ] **3. 获取实际执行计划。** 对 claim、seed per shop、clean totals、missing/old-mode 枚举、dirty 全历史 lookup、日期索引候选分别记录 EXPLAIN FORMAT=JSON/ANALYZE（后者仅隔离并有界），检查排序、实际 loops/rows、覆盖索引与临时表。不能因 index 列顺序看似正确就声称无 filesort。dates bulk 只采实验计划，不上线 facts-only 枚举。该实验只有 seed_complete/升级游标闭合时才允许 facts 日期枚举：合并区间内 clean owner 与公司全部可见 dirty owner，全部 dirty 从全历史重算后再按请求过滤；跨月移出旧区间和移入新区间必须同时正确。新/不完整公司 bulk 一律 native，不能把 label 完整 totals 域的许可推广到 bulk。
- [ ] **4. 选择参数的明确门槛。** 每档至少 30 个完成请求并报告 p50/p95/max、超时数、native fallback 比率、每个 owner 查询量/耗时、active RR 最长持续、rows examined、锁等待/死锁/重试、dirty 到达率 λ、成功发布率 μ、最老 dirty 年龄。选择满足零差异、无未清理事务、总 RR 在30秒边界内、主动预算早退有足够同S native及state余量、且 p95 明显优于相同负载 native 的最大 cap；没有满足档就保留 OFF，不凭猜测设置 64。worker 配置只接受连续三个 30 秒窗口 μ>λ、最老年龄不持续增长，并且前台 p95 未较同负载 native 基线退化超过 10%的档；这是准入实验标准，不承诺生产每秒能力。负载不足 30 样本/窗口时记录 insufficient，追加有界轮次，而非宣称收敛。
- [ ] **5. 区分无法收敛、正确回退与失败。** 事务有效时 cap/局部预算触发整 native 是正确性结果，仍可能超五秒；整体 RR/连接/rollbackOnly 失败单独计数，不计为成功 native 回退或等价样本；如普通持续导入大多 fallback 或 μ<=λ，报告实际瓶颈，继续下一事实粒度设计，不能用暂停同步取得通过。记录预算是否被公司非相关 pending 保守消耗；只有独立证明可见域筛选完整后才缩减此域。
- [ ] **5a. 保留最新观测的测量边界。** root 新提供 label group 只读 probe：整 workspace 3.790秒，三个来源计数186+285+200ms=0.671秒，period 3.037秒。实际 UI group 在18.611秒时尚未完成，是不同负载/测量阶段的结果；两者都不是本作者复测。不能据此声称 source count 就是瓶颈或端到端已达五秒。后续以同请求的端到端负载、连接池获取/等待、服务端各阶段及浏览器渲染时序对齐诊断，保留其测量口径和同期同步/导入情况。
- [ ] **6. 准入审查与提交。** root 核对全部新增测试的 GREEN 和失败时序，不用“已实现”代替运行证据；独立 spec/质量 review 检查 schema/init/worker/consumer 四接口闭合。更新常驻说明为“隔离通过/仍 OFF/影子待证据”及未验证边界，提交 `test: verify first settlement facts under bounded load`。

## Task 7：定向迁移、受控 shadow 与限定激活门

**Files:** root 后续创建专用迁移/维护工具及验收回执；具体迁移文件名在现场确认空闲号后写入回执，本计划不占号。维护工具先在隔离环境演练并单独 review；不复用 V159 操作身份，不修改 V158/V159。

**Interfaces:** 消费隔离通过的原型 SQL hash、Guard schema/registration 常量、支持配置和 actual workspace shadow 模式。生产共享资源操作仅 root/其明确授权操作者执行串行维护。

- [ ] **1. 现场复核与迁移定稿。** 确认 V159 操作完成/失败的实际回执、schema、源码、所有运行维护、DDL 元数据锁、正在写源表的事务、配置/挂载/worker。取得下一空闲 Flyway 号后将经过隔离验证的 schema/三个触发器编为新增定向迁移，保持老触发器不变；真实 Flyway 升级另跑隔离校验。不得只复制 test resource 到生产自动运行，也不得同步整个本地迁移目录。
- [ ] **2. 维护工具硬门。** 维护锁内停止领取新源写任务、等待现有写事务安全点/排空，检查所有受支持写入者边界；先将已有 singleton proof 禁用并持久记录本次恢复边界，再安装并校验三个独立 trigger 的实际定义/schema hash 后才发布 singleton 安装 proof，再建立各公司 registration_boundary/hash/version 和新 generation；新公司由 worker 使用同一 proof 自主发现和初始化，不能借旧公司 seed；已有公司使用 Task2 maintenanceState/advance 从持久旧状态可重启恢复，不能依赖普通 guard 或安装前内存。DDL autocommit 不是原子安装：中途失败保持 route OFF/覆盖门未开，工具记录已完成步骤和恢复入口，禁止安装一个就先开 gate。有外部未受控 writer 无法证明安装前在途事务覆盖时，不开放事实读路由。任何源码热重载均先成功编译、按同锁重启健康确认并恢复本次门控；不构建 JAR/OCI、不清队列/检查点。
- [ ] **3. shadow 回填。** read mode 保持 native 返回；工作器有界初始化/回填，仅在门开放后轮转，保留正常同步/导入与 API 限流。比较全必需 owner 覆盖、first date/ID/platform/currency/存在性的规范化 hash 和数量（按有界批次）；known-empty/missing 分开计数。仅 seed_complete 不足以允许 bulk，bulk 仍保持 native；shadow 首消费者仅 label workspace。
- [ ] **4. 实际同 RR shadow。** 维护工具仅给获准的公司/日期写明确 SHADOW grant（默认空），其 schema/registration/evidenceId 绑定本轮验收；未匹配范围完整 native，不执行 candidate。对匹配请求在同 S 计算 native/事实；采样首入、刷新、币种/平台/店铺/组/月份/部分月、source 分页、detailsOnly 和持续导入窗口。至少三个连续有界观察窗，每窗 30 个完成的非 fallback 对照、零差异、无超时/错误增长；达不到样本数或 timeout 就保持 shadow 并给出原因。采集 Task6 的 λ/μ、最老年龄、锁等待/回退、RR 生命周期，支持参数必须满足实际生产负载证据。
- [ ] **5. 激活判定。** 独立 review 认可源登记覆盖、two-connection 回归、升级/初始门、复制字段、实际 shadow 与服务能力证据后，由维护工具只激活已通过的 label 期间范围。全局配置 ACTIVE 必须同时匹配显式 ACTIVE grant、每公司 Guard/版本/预算条件；由维护工具将本轮获验的精确公司/日期范围写入 allowlist，SHADOW grant 不自动升级。记录 grant 的范围、schema/registration、evidenceId 及操作 ID；其余范围 native，新公司/新日期不因自动发现或 seed 完成获准。回退移除相应 grant或关 reader/worker 路由，保留事实与登记、revision 和原始业务数据；不删除表/触发器来“回滚”。出现不变量破坏先整公司 native、失效 seed_complete，再修覆盖，不继续 facts-only 日期扫描。
- [ ] **6. 常驻回执更新。** root 更新模式、迁移号、真实源码/定义 hash、维护/恢复入口、通过/失败/跳过和未验证项；标记本切片完成仅限已验证的首笔事实和贴单消费者。不会因触发器存在或单条 SQL 达标而标记整个五步目标完成。

## 完整五步目标与紧接本切片的实施队列

| 目标 | 本切片贡献 | 必须继续完成的独立证据 |
| --- | --- | --- |
| 1 索引/观测 | 复用 root V159 首笔覆盖索引与实际慢段证据；Task6 补事实计划 | root 当前维护/逐段实测回执，不能用本计划替代 |
| 2 查询事实 | Task1–7 实现首笔身份与实际 label 消费 | RECORD/SETTLED 精确计数、金额贡献及广告依赖各自实现/验收 |
| 3 受影响范围更新 | 源事务 old/new owner、单调 revision、RR/CAS | 贡献旧值减去/新值加入、删除/跨月/维度/未知计数、全依赖覆盖与服务能力 |
| 4 完整版本 Redis | 本切片明确禁止只用 pointer version 缓存整页 | 绑定 label/cost job/FX/权限/明细/广告/模式完整版本向量；不完整不能 soft-reuse |
| 5 全页真实负载 | 仅成本贴单 workspace 的限定验收 | 费用、订单、结算、对账、经营汇总等全部目标页面冷缓存/首入/筛选/分页及正常持续同步/导入 |

接续工作不是 pointer 的隐藏验收项，也不能被取消：

1. **RECORD 精确计数。** 按源 record ID 独立贡献，维度保持 company/shop/platform/report date/kind/currency/source/广告费标识与完整/部分 reporting-month 资格；DATE_PENDING 排除、API 创建订单限制、可见性不变，营销原始记录仍计数。搜索 numbers 非空仍权威搜索，不用日期计数代替搜索存在。计数不等待成本/广告全部重算。
2. **SETTLED 精确计数。** owner 为完整订单身份，按 true 首笔关联所有 API ORDER/REFUND 源记录，保留多记录 multiplicity 和结算币种（不等于展示订单币种）；保持现有 target status 语义与 `normal=false`，不能加新的取消订单过滤。首笔改变及关联 ORDER/REFUND 插删/身份/source/kind 改变登记完整依赖；独立初始门及覆盖版本。
3. **金额当前贡献及小汇总行。** 复用现有 cost/active label/ledger，将同 owner 旧贡献原子替换成新贡献；保存 known/unknown/pending/job priority、FX 日期/来源、月校正资格、零与缺失。公司依赖无法精确枚举时保留广域 fence；月 JSON 不支持逐订单安全减旧加新，不能简单追加 event_id 大于水位金额。
4. **广告与 Redis。** 广告按账户/日期/币种/重叠来源域，缺账户可扩大平台日，不统一裁成店铺日；完整版本证明后复用 immutable summary，detail 仍来自目标 S。只有 dirty owner 全量枚举和同快照旧新贡献成立才实现尾差替换。
5. **统一真实验收矩阵。** 继续 root 的五步目标：逐页冷缓存、首入/筛选/分页、正常同步/导入、完整结果与最终延迟；对未入库覆盖继续明确 missing/pending/unknown，不宣称全历史齐备。

## 实施前自查与完成记录规则

计划已按 spec 各节自查：Task1 覆盖身份/复制字段/源事务；Task2 覆盖 B1 初始化与 B2 升级；Task3 覆盖 R/lease/guard；Task4–5 覆盖 B3 八列 relation 与真实 workspace RR；Task6–7 覆盖隔离、shadow、维护和实际服务能力；上表保留完整五步目标。实施时每个勾选项须有实际 evidence ID；文档存在不构成测试通过。

三个设计收敛点已在本计划落实：必需种子域仅有效结算候选；第一版平台/币种复制为必选依赖；旧模式 requested=applied 在重入队扫描前仍归 unresolved 并计入 cap。源 128/totals 255 字符长度差异已获 root 接受并纳入 Task2/5。新公司自动发现所需 singleton 安装 proof 也获 root 接受，属于相对 spec 的新增实现决策；执行前必须独立复审其 global gate、创建新公司及发布 CAS 接口，root 已将 singleton、维护旧状态恢复、范围门、30秒RR及失败边界同步到 spec，修订后仍须限定复审。未发现需要扩大为整库通用事实框架的设计阻断。


## 限定 fix1 交接（2026-11-14）

已按 `task-3-first-fact-plan-review.md` 收敛 Q1 持久维护旧状态/CAS 恢复、Q2 默认空公司/日期范围 allowlist、Q3 候选主动早退与整个 RR 失败边界，并同步 root 对 Task4 的显式30秒和延后 nativePeriod 抽取裁定。以上为计划修订，未执行实现/测试/生产操作；由 root 对修订段限定复审后继续既有授权范围。
