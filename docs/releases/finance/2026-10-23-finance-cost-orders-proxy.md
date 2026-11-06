# 订单成本接口空指针修复

> 历史发布记录；镜像、测试与“当前/最新”均指记录当时。 当前入口见[业务说明](../../business/README.md)与[运维指南](../../guides/operations.md)。

订单成本页面及成本金额下钻调用 `/api/v1/finance/workspace/costs/orders` 时出现 500。线上堆栈定位到 `FinanceCostReport.orders` 的空仓储依赖：控制器直接读取 `FinanceCostWorkspace` 的字段，而该服务在运行时由 Spring CGLIB 代理。

- 将查询入口移入 `FinanceCostWorkspace.orderCosts`，控制器通过服务方法调用目标实例；依赖字段改为私有，阻止再次直接读取。
- 新增 MockMvc 回归，仓储、服务和成本工作区均使用真实 Spring 类代理。覆盖日期筛选、分页、单条账单下钻及无效来源参数。该测试在修复前复现相同空指针和 HTTP 500，修复后通过。
- `FinanceTest`、`FinanceCostWorkspaceTest`、`FinanceOrderCostsTest` 共 46 项检查通过。此前的发布验收直接调用报表类，未覆盖本次出错的控制器与工作区代理路径。
- 发布前使用线上数据进行只读事务检查，通过实际控制器及服务类代理验证六种来源、单条来源跨日期下钻、分页和公司隔离。浏览器验收环境处于未登录状态，因此不把服务端验证表述为浏览器端到端验证。
- 发布仅替换 `FinanceCostController`、`FinanceCostWorkspace` 及其内部类；不改变成本算法、金额、数据库结构和前端资源。发布脚本校验原镜像与类哈希、排空同步请求、保留检查点与原同步计划，并在健康或控制器验收失败时回退。
- 正式镜像 `finance-cost-orders-proxy-20261023` 已部署。安装后的运行类通过上述只读控制器验收，应用健康正常，匿名请求保持 401；同步计划已恢复。
