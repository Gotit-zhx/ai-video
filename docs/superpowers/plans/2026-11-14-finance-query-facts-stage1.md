# 财务查询事实路线：第一阶段实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** 完成用户五步持续目标中的索引优化与分段观测，并以实际证据确定后续查询事实和精准增量的粒度；本阶段完成不等于整体目标完成。

**Architecture:** 继续复用 MySQL、现有事件/事实表及 Redis。隔离证据否定了仅重排旧索引的初始假设；第一步改为保留旧索引，增加首笔结算专用覆盖索引，并在同一次关联调用内显式列出等值排序前缀，保持原日期/id 首笔语义。对最慢专页做有界只读分段观测，再根据工作量与依赖证据固化查询事实协议。

**Tech Stack:** Java 21、Spring Boot、MySQL 8.4、Flyway、既有 Redis、原生 JavaScript。

**Spec:** 用户于 2026-11-14 设置的 active goal 五项原文，以及 `docs/research/finance/bi-query-performance-options-20261114.md` 的 B 路线。用户已明确选择五步继续实施，先前单索引待确认状态由本次目标授权替代。

## Global Constraints

- 保留查询快照内已提交变化；未覆盖的内部基线不能标为最新。第 4 项的精确尾差补齐仍需研究和证据，不预先假定必须采用。
- 原始金额、首次结算、人工归属月、GIFT、刷单排除、广告重叠、历史 FX、精度、未知值和公司权限不变。
- 保留业务队列、检查点、租约、限流和用户继续导入；不通过暂停业务取得性能结果。
- 当前工作区 `feature/finance-bi-performance` 已是隔离 worktree，保留未提交的研究报告、索引及精准补算草案。root 负责 Git、服务器、文档及浏览器；子代理不得提交或操作生产。
- 当前新捕获现场 context `462670e3-bee2-4c1d-9c71-72e6fc7008e2`、schema158。V158 是其他会话的 Shopee 资料，主机已有、本地未整合；只能定向添加本次文件，不上传整个迁移目录。
- 第一个新增迁移预留 V159，执行前再核对服务器和跨会话占用。旧迁移不修改、不修复校验和。
- 集成测试只用可销毁隔离库：`python3 /tmp/finance-bi-performance-qa/run-db-tests.py ClassName...`。同一时间只授权一个本地 Maven/target 写入者。
- 日常源码运行不构建 JAR/OCI/静态包。生产迁移和重载在共享锁内按安全点及成功编译流程执行，保留其他源码和运行配置。
- 只读观察有超时、内存及输出边界，不打印凭据、业务标识、财务正文或完整 SQL 参数。

## 完整目标追踪（本计划不得缩小）

| 用户目标 | 本阶段交付 | 后续完成证据 |
| --- | --- | --- |
| 1 索引及分段观测 | Task1、Task2 | 真实计划、等价结果、前后时间及专页瓶颈 |
| 2 查询事实 | Task3 给出已验证依赖和粒度设计 | 后续实际事实维护、回填、同快照权威对照和受益页面 |
| 3 受影响范围更新 | Task3 计算服务能力证据和协议边界 | 修改、删除、跨月、维度变化、晚事件和幂等更新的实现及真实负载收敛 |
| 4 覆盖完整后 Redis 复用 | Task3 明确完整版本与尾差候选 | 缓存隔离/硬失效/完整性验证、冷查询；如需要补差则另外证明其同快照正确性 |
| 5 逐页验收 | Task2 先成本页并补齐慢页观测 | 所有目标页面默认/筛选/分页、冷缓存和持续同步/导入真实验收 |

### Task 1: 首笔结算索引顺序与隔离回归

**Files:**
- Create `src/main/resources/db/migration/V159__finance_first_settlement_order.sql`。
- Create `src/test/java/com/yingdao/aicommerce/finance/FinanceFirstSettlementIndexIntegrationTest.java`，可复用既有 first-row fixture。
- Modify `src/main/java/com/yingdao/aicommerce/finance/FinanceLabelCosts.java`，仅 r0 索引提示与等值排序前缀。
- Modify `src/test/java/com/yingdao/aicommerce/finance/FinanceLabelFirstSettlementRowIntegrationTest.java`，既有索引名预期随新的实际访问路径更新，业务断言不变。

**Interfaces:** 保留原 `idx_finance_settled_order` 及其全部字段；新增 `idx_finance_first_settlement`。查询不新增过滤、不改变首笔选择。

- [x] 先写真正 RED：执行实际 workspace 汇总并解析 `EXPLAIN FORMAT=JSON` 中 r0 的排序路径，旧索引出现 filesort；固定四项汇总及同日多币种/有效类别首笔语义保持。
- [x] 运行新类，保留实际旧生产源码 1 测试/1 预期失败输出和 XML，非环境错误。
- [x] 新增迁移，不修改 V136；SQL 定稿如下：

```sql
ALTER TABLE finance_records
 ADD INDEX idx_finance_first_settlement (
  organization_id,shop_id,kind,order_id,source,status,record_date,id,
  platform,category,currency
 ),
 ALGORITHM=INPLACE, LOCK=NONE;
```

- [x] 生产 r0 使用新索引；排序写为以下等价形式，让优化器识别关联前缀。在同一订单调用内，前六列受 WHERE 等值限定，不改变任何候选之间的排序：

```sql
ORDER BY r0.organization_id,r0.shop_id,r0.kind,r0.order_id,r0.source,r0.status,
         r0.record_date,r0.id LIMIT 1
```

- [x] 隔离验证新索引 11 列、旧索引 15 列全部保留；实际生产汇总 r0 `using_index=true` 且无 filesort。真实 Flyway 154→157→159，数据与旧历史/校验和保持。
- [x] 新类 1 + first-row 66 +原贴单79，共146次通过，无失败/错误/跳过；不以继承执行数充当实际新增方法数。
- [x] 报告、五文件 SHA、真实 XML 均由 root 核对；独立 spec/质量通过，提交363a7bda。生产迁移/耗时仍在 Task2。

### Task 2: 定向生效和真实分段观测

**Files:** root 私有维护工具、`docs/guides/finance-performance.md`、`docs/releases/finance/finance-workspace-performance-20261113.md`。观察程序放本机 QA 目录，不作为应用组件。

**Interfaces:** 复用现有 Store、维护门、安全点和受控源码流程。接口返回及业务数据不改变。

- [x] 现场复核最新上下文、源码、迁移、运行配置、磁盘、现有操作和其他会话维护。只允许本次新增迁移目标；计算并校验 protected tables、旧 Flyway 前缀和其他源码清单。
- [x] 保留原索引，在迁移后有界同一只读 RR 中运行原/新实际 LATERAL，保存四字段哈希、执行计划及两轮反向次序耗时，不输出金额/身份；自己的重探针串行。采用同快照对照避免同步变化混入不同时间的前后比较，不声称跨 DDL 是同一快照。
- [x] 迁移维护工具复用已审查流程，持久记录操作身份、不可重放状态、精确目标、前后 schema/runtime 文件和恢复入口；17 项本地演练及限定复审通过后受控迁移/源码加载。成功编译后才重载。操作 4e0df0f30879，已独立 accepted。
- [x] 新增索引及原历史/配置保护、watcher ready、健康/401 已核对。真实计划新 r0 无 filesort且覆盖；旧 4.672/3.799s，新3.340/3.078s，四项同哈希。是两轮 SQL 样本，不是整体五秒通过。
- [x] 整站 reload、刷新、菜单进入完整计时；保留首刷9.640s和分组18.611s仍忙样本。后者被下一动作替代，下一页是完整重读，不作纯分页成绩；未证明持续导入活动/冷Redis，完整目标仍继续。
- [x] 四页真实数据库分段已取得并终止：订单/费用见原回执，结算2.128s（count1.095）、对账3.540s（count2.817）。贴单服装组另一次后台3.790s与UI慢等待未同负载对照，继续端到端诊断。

### Task 3: 固化查询事实与精准增量的下一阶段

**Files:** 只读代码/依赖审计报告；根据证据新增下一阶段 spec/plan，旧 scoped 草案原地保留历史状态。

**Interfaces:** 消费 Task2 的慢段证据，输出查询粒度、受影响身份、覆盖修订和回填规则，继续保持现有 HTTP 契约与财务口径。

- [x] 核对现有订单成本、贴单、广告事实和首次结算使用点，区分可复用与需要补齐的身份。审计修改/删除、同日多行、首次结算移月、FX、归属月、分组及任务状态的依赖。见 query-facts-audit 与新首次事实设计；具体原型计划独立复审修订中，尚非实现完成。
- [ ] 小范围只读实验比较候选订单事实维护与店铺日重算工作量，完整记录观测遗漏边界；计算一个公平轮转的服务预算与同期新增工作。不能从单个最快样本或事件数猜测可收敛。
- [ ] 选择能满足真实服务能力的粒度，写明幂等键、首次回填、源事务事件、基线覆盖和晚提交安全；独立审查后才实施新持久协议。
- [ ] 明确 Redis 只复用完整查询版本；审计通用 soft dataRevision 路径是否满足目标页面快照规则。若需要“基线＋精确补差”，先定义同快照替换/删除及跨月覆盖证明、资源预算与失败回源，再实现回归。
- [ ] 创建后续任务及真实页验收矩阵继续执行，保持完整 active goal。只有五项逐条有当前证据时才标记整个目标完成。
