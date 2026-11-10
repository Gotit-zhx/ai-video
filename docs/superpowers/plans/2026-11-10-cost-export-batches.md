# 成本报表自动分批导出计划

**目标：** 用户确认真实失败为超过 50,000 单或 200,000 条 SKU，不是浏览器权限。全部店铺查询自动后台生成；小范围保持 XLSX，大范围按原单文件上限分批，打包一个 ZIP 下载，无需人工切日期或逐店导出。

**架构：** 保留当前 `FinanceCostExport` 财务行生成与原同步接口。新增只读、稳定事务快照下的文件导出和有界个人任务队列，复用现有 `MaintenanceGate` 和费用导出的权限/幂等模式，不新增数据库表或迁移。生产排在广告 `.4` 验收之后，以新 `.5` 热修纳入最新生产基线。

## 固定接口

- 后端 `FinanceCostExport.exportFile(Scope company, Filter filter, String source, String recordId, String costStatus, String numbers, Path directory, BiConsumer<Long,Long> progress)` 返回 `GeneratedFile(Path path,String filename,String contentType,long orders,long lines,int parts)`；调用跨 Spring 代理，使用只读 REPEATABLE_READ 快照，整个任务有时限。单份上限 50,000 单/200,000 SKU，单笔超过明细上限明确失败，不能截断。
- HTTP `/api/v1/finance/cost-exports`：POST 请求字段与当前成本筛选相同，`Idempotency-Key`；GET 列表、GET `/{id}` 状态、GET `/{id}/download`。任务所有者为真实公司+用户，实际数据查询使用 `companyScope`；finance:view 可创建只读导出，同源保护。状态 `QUEUED/RUNNING/READY/FAILED`，字段 `id,status,error,filename,contentType,orders,lines,parts,processed,total,start,end`。下载重新鉴权，private/no-store。
- 前端保留“导出 Excel”入口，提交任务、展示后台进度；READY 时按实际 XLSX/ZIP 文件名下载并保留可见下载链接。公司/用户切换后旧任务不可使用。浏览器拒绝保存时不宣称文件已保存，不绕过工具的下载拒绝。

## 任务与文件边界

1. **流式分批文件（实现代理）：** 仅修改 `FinanceCostExport.java`、`FinanceCostWorkbook.java`、必要的新分批辅助类和对应 Java 测试。复用原行生成，稳定分页不重复/漏单，先筛选小型键再读取原始 JSON，避免大 JSON 排序和 OFFSET 扫描放大。每份包含汇总/订单/SKU；独立期间退货参考附表只保留一次，不能各分片重复累计。临时文件按批关闭和清理，内存仅保留一页/一个订单；XLSX 直接写文件。任务输出总大小有界，超限明确失败。
2. **任务与鉴权（实现代理，主会话整合）：** 新 `FinanceCostExportJobs`、`FinanceCostExportController` 及测试。单后台工作器、有界队列；每用户最多两项在途、同条件/幂等键复用，完成文件保留 30 分钟，总保留与单任务体积上限，错误/过期/关闭清理仅本任务临时目录。下载在锁内打开流，避免到期清理与下载竞态。只写临时文件和任务元数据，不写账本、成本或同步状态。
3. **进度及下载交互（实现代理）：** 仅 `frontend/finance-workspace.js`、`frontend/finance-costs.js` 和对应 `scripts/qa` 用例。同步接口不再是大导出的必经路径，展示固定筛选和 XLSX/ZIP 结果；保留可下载入口与公司/用户校验，自动尝试下载失败时可见重试。关闭面板不取消已入队生成，重开能找回个人任务。
4. **综合验证与发布（主会话）：** 跨店/跨组/跨原币/跨公司隔离、订单与 SKU 两种分批边界、空订单/缺失成本/GIFT/排队与实际成本、金额精度、失败清理、快照期间并发重算不改变导出集合；本地受限隔离 MySQL 和现有财务/前端回归。独立审查后等待 `.4` 交接，按 Gitflow 发布 `.5`，不修改已有候选或标签。

## 运行约束

实现代理不运行共享 Maven、不切分支、不暂存/提交、不访问生产；主会话统一运行 Java 检查和提交。前端测试可独立运行。财务口径、空值、公司隔离及原始数据全部保留。任务容量/文件空间有上限，临时目录不在业务卷或 Git 内；日志不记录完整数据。
