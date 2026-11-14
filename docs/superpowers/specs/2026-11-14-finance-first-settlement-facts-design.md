# 首次有效结算查询事实：下一阶段设计

状态：用户五步目标内的分阶段技术设计；核心契约已通过限定审查，具体原型计划及下述实现收敛仍需独立复审、隔离/影子验证。首次事实数据库协议尚未实施。它不批准旧“统一店铺日”草案，不把首次结算指针当作全部财务查询提速的完成证明。

## 目标与阶段关系

复用现有订单成本、活动代次贴单和广告事实，优先补齐被重复关联的首次有效结算身份。第一阶段索引与观测继续独立完成；随后以本设计验证事实维护及同快照读取，再推进独立精确计数和经营贡献汇总。

完整目标仍为：索引/观测、查询事实、受影响范围更新、完整版本 Redis 复用、全部目标页面在正常同步/导入下验收。第一个事实切片只解决身份关系，不解决费用精确计数及全部账本聚合。

## 权威语义

- 身份是 `(organization_id,shop_id,order_id)`，不按月份、币种或平台先拆分。
- 候选完全沿用 `FinanceCostReport.confirmed` 和 TikTok ORDER 类别条件，从全部历史按 `(record_date,id)` 选最早有效行。日期、币种、平台、权限、营销排除和归属月条件在确定首笔后应用。
- 同时保存首笔日期和首笔 ID。`firstSettlement(...,true)` 是最早单行；`false` 是首日的全部有效行，两者不能替换成同一种关系。
- 现有成本表日期是源订单日期，不能代替首次结算归属。成本任务优先级、未知金额、贴单活动代次及真实零保持原有规则。
- 源订单身份为 VARCHAR(128)，活动贴单 totals 为 VARCHAR(255)。事实保持源类型/比较规则；长 totals 身份保留完整字符串作为 missing/native 并计入补齐预算，不截断、不写入 128 列，也不凭长度宣告 known-empty。

## 逻辑存储与版本

下一空闲迁移号在实施前复核；第一阶段 V159 已于 11 月 14 日受控生效并独立验收，本文不提前占用其后的编号。历史回执不能代替后续操作前现场核对。

`finance_first_settlement_facts` 是当前派生状态，不是审计快照：

- 主键：组织、店铺、订单，字符集、比较规则和源身份一致。
- `requested_revision` 正数单调推进，`applied_revision` 从 0 开始，始终不大于 requested；状态行不删除重建而重置版本。
- `has_settlement`，可空的 `first_record_id/first_record_date/first_platform/first_currency`，已知无候选与尚未算过严格区分。
- `schema_version`、租约身份/到期与更新时间。布尔 pending 可用确定性的生成表达式索引，不能依靠全表表达式扫描领取。
- 清洁条件是版本、代次/策略正确且 requested=applied>0。已知无候选可为清洁；missing、applied=0 或不兼容模式都不是零结果。
- 日期选择索引、pending 领取索引按隔离计划定稿；不能再次只根据逻辑列顺序推断排序已经消失。

小型初始化状态记录公司、模式版本、注册路径启用边界、种子进度及 seed_complete。新公司/缺失初始化行继续原权威读取，不生成伪零。

原型将全局安装边界具体化为专用 singleton `finance_first_settlement_registration`：保存已安装 schema/registration、三个独立触发器的定义 hash、维护 boundary 及 enabled。缺失、禁用、不支持的版本或 hash 均全 native。仅受控维护在触发器全部安装、定义校验及安装前在途源事务覆盖成功后发布有效 proof；每次重新发布安装定义/边界/模式均单调推进 registration，不能同版本替换。改变现有登记前先在维护门内停领并排空，将全局 proof 禁用；DDL 中途失败保持禁用和本次恢复门，不能依赖旧 enabled proof 恢复事实读取。

新公司由工作器使用有效 singleton 创建自己的 generation=1、未完成游标和 seed_complete=false，不能借旧公司种子状态。没有有效 proof 时可先记录 generation=0 待办，之后原子初始化，不能降级已开始的代次。发布、领取及维护更新遵循 singleton→company→owner 锁序；旧 worker 不得跨 proof 发布。普通读 guard 仍要求公司匹配当前 proof；维护升级另从持久公司行读取旧 guard，在锁定有效目标 proof 后按旧字段 CAS 到新代次，允许旧登记与目标不同。重启后的维护进程能恢复该入口，不依赖旧内存对象，旧 expected 不可重放推进。

第一版固定复制 platform/currency，减少宽原始行读取；两字段与 date/id 一起原子发布，两字段变化必须标脏。相关触发器、CAS 及同快照回归是启用门槛，不允许存在某路径复制但不登记的分支。

### 初始化覆盖的精确定义

seed_complete 仅证明“该公司所有当前有效结算候选所属 owner 都有状态，之后任何候选加入、退出或迁移均原子登记”。API ORDER/REFUND 和活动贴单身份是可选预热域，不属于此闭合承诺。贴单始终从完整活动 totals 域 LEFT JOIN，缺失继续原算法，不能由 seed_complete 推导为 known-empty。

维护锁及门控内排空应用源写事务，安装并校验三个独立触发器的模式/定义后，才记录 registration_version 与 generation。DDL 等待的元数据锁和现有事务状态须核对；安装前在途事务未覆盖时不得开放事实读路由。

公司初始化行存储 `target_schema_version/generation/registration_version/seed_complete` 和稳定游标。新公司由工作器从现有财务公司范围发现并创建初始化任务；请求若缺公司状态只读回源，不能在只读 workspace 中写共享初始化记录。

有效候选种子按以下确定流程执行：

1. 先有界枚举该公司已有源记录的 shop_id，按字符串比较规则保存 shop 游标；本轮 shop 枚举之后新建 shop 的候选由已启用触发器登记。
2. 每个 shop 固定 kind=SETTLEMENT、source=API、status=SETTLED，用既有等值前缀及 order_id 范围，按 order_id 分批枚举非空订单，再判断完整平台/类别资格。不能只枚举请求月份或连接当前平台。
3. 每批只 INSERT missing owner 为 requested=1/applied=0；已有 requested、applied、租约和事实不覆盖。owner 登记与游标推进在同一事务，崩溃可重复执行而不能越过未登记批次。
4. 删除/移入/在游标前后新插入候选均由源事务登记；源事务标脏不能依赖游标所在位置。
5. 最终 CAS 必须匹配公司、generation、target_schema_version、registration_version 以及所有必需游标完成状态。旧 seeder 不能给新 generation 完成。

订单/退款和 totals 预热拥有独立的可重试游标，不阻塞有效候选覆盖门；新 totals 身份没有源结算时允许保持 missing，但每次查询必须正确回源。

### 模式升级与 missing 状态

`schema_version` 表示最后应用模式，初始化行的 target_schema_version 表示当前目标；不能只修改 reader 常量。升级先在维护流程中验证新的源登记模式，单调推进公司 generation/target 并清除 seed_complete。版本较低的进程不得将 target 降回旧值。

在新 generation 下，有界枚举全部现有 owner，将旧应用模式重新入队：只递增 requested、不重置任何 revision；记录 queued_generation，防止同一代次重放不断加修订。升级期间 bulk 一律原生，贴单对旧模式逐身份回源。新增 owner 自身 applied=0，天然进入 pending。

公司升级游标完成后，每个旧模式 owner 都已变为 requested>applied，pending 索引可以完整枚举未完成项；已被工作器完成者必须是目标模式。发布 CAS 同时核对 target、generation、registration_version、租约 token/期限和快照实读 R，旧进程或旧 seeder 无法发布旧模式结果。

正常写路径不删除状态行。任何显式清理/重建必须先使公司覆盖门失效，重新登记/回填后才恢复。facts-only 日期扫描不声称能够自动发现被外部操作删除的状态；发生不变量破坏时整公司原生回源并恢复覆盖。

## 源事务登记

新增独立数据库触发器，不覆盖已有 V155/V156 的变更事件触发器。任何受支持写入方式都由数据库在同一源事务登记：

- 候选 insert/delete 及 kind/source/status/platform/category/date/id/组织/店铺/订单变动，覆盖旧、新身份。
- 第一版固定复制平台和币种，相应字段变化必须标脏。SQL NULL-safe 比较及原列比较规则与源查询一致。
- 普通订单创建的缺失登记仅属于可选预热；查询不能依赖它保证候选覆盖。订单成本作业、金额或同步标记不能被误当作首次身份变化，它们原有的金额/明细失效继续保留。
- 双身份按稳定顺序取得状态行锁；源事务失败时全部原始变动及登记一起回滚。必须有两个源写者、交叉身份及 worker 并发回归，不能靠忽略 deadlock 保持“成功”。
- 事务号、event 自增 ID 和墙钟均不作为覆盖证明。源提交和 requested 推进在同一事务中可见，晚提交不会被高水位跳过。

## 工作器与首次回填

沿用维护门、单条调度链、有界领取和租约；不与现有上游 API 配额共用请求或重置检查点。

1. 在一致性读快照中读取身份 requested=R 及其完整历史候选，计算首笔/已知无候选。
2. 在短发布事务中核对该行仍是 R、模式及租约有效，CAS 发布 date/id/复制字段和 applied=R。未覆盖的较晚提交保留 pending，不能写 applied=当前 requested。
3. 失败/租约丢失保留原事实和待处理状态；重试幂等，旧工作器不得覆盖新结果。
4. 安装登记路径时遵守共享门控和安全点，覆盖安装前已在途的源事务。回填按组织、店铺、类型及订单索引分批枚举，登记请求时不覆盖已由新事务推进的版本。
5. 必需种子域仅为全部当前有效结算候选的 owner；API ORDER/REFUND 和活动贴单身份作为独立预热域。枚举期间候选插入/移入/删除由事务登记覆盖；不能只凭扫到当时 MAX ID 宣布完成。
6. seed_complete 证明历史身份枚举与新事务登记闭合，不要求每个身份已经追平；读者仍准确区分每个 clean/dirty 状态。只有少量脏身份时才启用其完整补齐，超预算沿用完整原生路径。

批次、并发和时限以隔离与实际影子负载的服务能力定稿，不先承诺固定每秒处理量。

## 同一快照读取

代码核对发现：`FinanceCostController.labelCosts` 直接调用 `FinanceLabelCosts.workspace`，当前 workspace 的来源、文件、期间汇总和状态 SELECT 外没有统一 readSnapshot 或事务注解。因此新方案必须显式包住整个 workspace 的只读 RR，包含 detailsOnly，不假定调用者已经建立快照。

- 贴单期间汇总由活动代次 totals 驱动，所有身份都必须进入 clean 或精确原算法分支。clean 用事实，dirty/missing 用当前快照内的原全历史候选查询，两个分支不重不漏。
- 新初始化尚未完整时，贴单可在完整 totals 域逐身份分类；批量已结算订单关系继续原生读取，不能从不完整事实表枚举订单。
- 初始化完整后的日期路由须合并“区间内 clean 身份”和“公司内全部可见 dirty 身份”，补齐后再分别按原请求过滤。只按脏身份的新日期筛选会漏掉被移出的旧月份。
- true 模式可用首笔轻事实表达；false 模式仍须读取首日全部 eligible 源行，并应用各行平台/币种条件。winner 的币种不能替代首日所有行的币种。
- 原始明细及附加展示字段继续从同一个快照读取。变化只影响明细时也必须保持其当前性，金额版本不能代替明细版本。
- 派生行缺失、模式不符、复制字段依赖不完整、脏域枚举不完整或超限时，保留原生完整读取；不能截断脏身份再宣称完整。

### 第一消费者与候选 relation adapter

首个实际消费者仅为整个 RR 下的贴单期间汇总；不直接改动所有静态 firstSettlement 调用。clean true relation 明确提供 `id/organization_id/shop_id/order_id/record_date/platform/currency/status`：id/date/平台/币种来自已验证事实，status 在有效候选不变量下投影为 SETTLED；dirty/missing 分支以原 SQL 提供同一列形状。category 等未被该调用方使用的字段不伪造，也不擅自推广 adapter。

两个分支合并后，继续调用原 `repo.query(scope,filter,alias)` 与 `FinanceReportingMonths.constrain`，保留连接可见性、UNASSIGNED、分组和首笔 record ID 的部分月校正子查询；营销排除仅在原调用方要求时执行。raw provenance 改变但首笔字段不变时，资格仍由 S 中当前校正表和源 raw 判断，不从缓存日期猜测。

后续 false 消费者必须重新联结首日全部 eligible 源行，对每行使用原 platform/currency/reporting-ID 谓词，保留原 DISTINCT 和 ORDER/REFUND 多源记录语义。新关系启用前独立对照原生关系；尚未切换的调用点继续原算法。

dirty 预算按公司与 pending 索引读取至 cap+1，超出直接选择整条原生路径，不先做无界 COUNT 或 IN。预算内全部身份都要解析；不能按其存储旧日期或 winner 币种过滤。贴单的 missing 在完整 totals 域识别并计入预算，不能漏掉没有状态行的订单。

### 限定启用与事务预算

OFF/SHADOW/ACTIVE 使用默认空的公司/日期范围 allowlist，查询须完整落在已获验的范围内才进入相应候选路径。Guard/seed 只证明技术覆盖，不证明获准激活；自动发现和完成新公司不能自动加入 allowlist。关闭路由不删除事实、源登记或 revision。

整个贴单 workspace 的独立前置修复采用明确 30 秒只读 RR，与现有前端读取 deadline 保持有界兼容；原期间 SQL 在该修复中保留，之后接入事实时再抽取 nativePeriod。30 秒不是性能目标。候选预算必须早于整个 RR deadline 并保留必要后续读取余量。候选局部预算主动耗尽且事务仍有效时可同 S native；整个事务已到期、rollback-only 或连接失效则整体失败并丢弃已读片段，不能只给 period 新开事务拼接来源/文件。SHADOW 也遵守这一边界，不能把候选错误吞成完整原生响应。

## Redis 与后续事实的边界

本阶段不能仅以首次事实版本缓存整页。贴单代次、当前成本作业、汇率、权限、原始明细等是独立依赖；原有通用软刷新不构成目标快照的覆盖证明。

下一阶段分别处理：

1. RECORD 精确计数：按源记录计数，保留来源、DATE_PENDING、广告费分类及完整/部分归属月资格；刷单经营排除不能用于原始页总数。
2. SETTLED 精确计数：按首笔单行关联 API ORDER/REFUND 源记录，保留同订单多源记录及结算币种语义。
3. 订单成本/贴单、普通账本的当前贡献及小汇总行：源事务登记完整 owner 依赖，发布时原子替换旧贡献，保留所有已知/未知计数和历史 FX 细分。
4. 广告按账户/日期/重叠来源域处理，缺账户可能扩大为平台日；不能把它统一裁剪为店铺日。

精确尾差候选依赖能枚举完整 dirty owners、同快照旧贡献和新贡献；必须做替换/删除与跨月校正。当前月 JSON 本身不足以安全做逐订单减旧加新，不使用 event_id 大于水位的简单相加。

## 实验证据与验收门槛

- 真正 RED/GREEN：原生 first date/ID 独立对照；同日多币种/类别、取消/非 API、营销、组织/移除/组、部分月校正、known-empty 与 missing。
- 两连接证明：低 ID 晚提交、R 后源变动、租约替换、发布回滚、旧/新身份并发、首次日期跨月/删除首笔、复制字段变动，以及种子完成边界。
- 初始化/升级回归覆盖游标前后新增、跨公司/店铺/订单迁移、批次提交前后崩溃、旧 seeder 完成 CAS、旧 schema 且 requested=applied、缺初始化行和新 totals missing。
- 整个贴单 workspace 同快照：在来源计数/文件读取之间由另一连接切活动代次，结果仍来自一致的 S；detailsOnly 同样测试。
- 隔离通过后定向迁移与影子回填，先核对来源/事实规范化哈希和数量，观察 dirty 到达、完成、最老年龄、锁等待和预算回退。事实路径未验证前保留原生读取。
- 在同一真实 RR 中对照原生与事实 clean/dirty 路径，记录耗时、读取规模及完整性。只有实际服务能力支持，才启用相应查询范围。
- 本切片通过后继续精确计数、汇总和 Redis，全目标仍需冷缓存、首入、筛选、分页及持续同步/导入逐页真实验收。

## 来源与接手

阶段入口：[第一阶段计划](../plans/2026-11-14-finance-query-facts-stage1.md)。本机详细依赖审计位于 `.superpowers/sdd/2026-11-14-finance-query-facts-stage1/query-facts-audit.md`，包含原生 true/false、队列和广告重叠域反例。此文不修改已有业务公式或已应用迁移。
