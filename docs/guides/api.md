# 接口与鉴权导航

核对日期：2026-11-05。本文提供查找入口和关键契约，不替代 Controller、请求 DTO、权限策略及接口测试；仓库未提供覆盖所有服务的统一 OpenAPI 文件。

## Web 登录与响应

浏览器通过 `POST /api/v1/auth/login` 建立会话：

```http
POST /api/v1/auth/login
Content-Type: application/json

{
  "organizationCode": "heyi",
  "phone": "13800000000",
  "password": "<你在本地设置的管理员密码>"
}
```

请求字段来自 `auth/AuthController.java`，并非单个 `username`。响应设置 `HEYI_SESSION` Cookie；后续请求携带该 Cookie，不使用 HTTP Basic。`GET /api/v1/auth/me` 查看身份、`POST /api/v1/auth/logout` 退出；同一账号重新登录会使旧会话失效。本地 HTTP 使用非 Secure Cookie，生产使用 HTTPS/Secure Cookie。调试时保持同一主机名，避免混用 `localhost` 和 `127.0.0.1`。

普通 Java JSON 响应由 `common/api/ApiResponse.java` 包装为 `success`、`data`、`message`、`timestamp`。SSE、文件下载、健康检查以及 Python 接口有独立格式；Spring Security 的 401/403 响应也不保证包含所有包装字段。以 HTTP 状态与实际响应处理失败，不只检查 `data`。

## 鉴权边界

| 入口 | 凭证 / 限制 |
| --- | --- |
| 业务 Web API | Redis Session 与后端权限/数据范围校验；路由规则见 `config/SecurityConfig.java` |
| 扩展 | `ExtensionAuthController` 登录/配对换取插件 Bearer；受版本、权限、撤销及企业限制，不能作为全站通用令牌 |
| `/api/v1/automation-agent/**` | 安全链允许进入，但 Controller/服务继续验证内部 Agent 凭证；不是匿名业务 API |
| Pi Runtime | 独立内部令牌，由 Java 客户端访问；不使用 Web Cookie |
| 官方回调 | 允许进入回调入口，继续检查平台、一次性 state、过期及授权归属 |
| `/api/public/v1/video-references/**` 等素材 | 签名/时效等由对应控制器检查，路径含 public 不等于文件可任意读取 |
| `/api/v1/hr/**` | Nginx 交给 Python Connector，再验证平台身份/招聘权限与设备授权 |
| `/actuator/health` | 允许健康探测；其他 Actuator 端点不因此公开 |

财务通过 `FinanceService.authorize/requireManagement` 做公司级权限检查。`finance:view` 可查看、导出和保存个人字段偏好，管理及导入需 `finance:manage`。写接口另有同源检查；费用与成本异步导出允许查看者显式创建只读报表任务，其任务再绑定公司和真实用户。

## 主应用接口地图

下表路径为当前 Controller 的基路径，子接口请读相应源码。Java 源码根为 `src/main/java/com/yingdao/aicommerce/`。

| 领域 | 基路径 | 代码入口 |
| --- | --- | --- |
| 企业 / 部门 / 角色 / 用户 | `/api/v1/system/...` | `saas/web/SaasAdminController.java` |
| 模型 | `/api/v1/system/model-configs` | `saas/web/ModelConfigController.java` |
| 通用工作流 / 事件 | `/api/v1/workflow-jobs` | `workflow/web/WorkflowJobController.java`、`WorkflowEventController.java` |
| 当前活动任务 | `/api/v1/tasks/current` | `task/web/ActiveUserTaskController.java` |
| 脚本 / 手工分析 | `/api/v1/video-scripts` | `video/web/VideoScriptController.java`、`ManualScriptAnalysisJobController.java` |
| AI / 通用视频 / 监控 | `/api/v1/ai-video-tasks`、`/api/v1/general-video-tasks`、`/api/v1/ai-video-monitor` | `video/web/` |
| 商品采集 / 图片 / 发布流程 | `/api/v1/product-captures` | `product/web/ProductCaptureController.java` 及同目录专用控制器 |
| 插件身份 | `/api/v1/extension-auth` | `auth/ExtensionAuthController.java` |
| 主图模板 / 结果 | `/api/v1/product-main-image-templates`、`/api/v1/product-main-image-results` | `product/web/ProductMainImage*Controller.java` |
| 模板 / 裂变批次 / 设计稿 | `/api/v1/commerce-templates`、`/api/v1/commerce-batches`、`/api/v1/commerce-designs` | `product/web/Commerce*Controller.java` |
| 商品店铺 / 官方授权 | `/api/v1/shops`、`/api/v1/shop-authorizations` | `shop/web/` |
| 财务 | `/api/v1/finance` | `finance/FinanceController.java` |
| 成本 / 贴单 / 刷单费 | `/api/v1/finance/workspace` | `finance/FinanceCostController.java` |
| 费用矩阵导出 | `/api/v1/finance/fee-exports` | `finance/FinanceFeeExportController.java` |
| 成本分片导出任务 | `/api/v1/finance/cost-exports` | `finance/FinanceCostExportController.java` |
| 千易公司配置 / 店铺 / 退货 | `/api/v1/finance/qianyi`、`/api/v1/finance/shops/{shop}/qianyi`、`/api/v1/finance/qianyi/returns` | `finance/FinanceQianyi*Controller.java` |
| 知识库及附件 | `/api/v1/knowledge`、`/api/v1/knowledge/files` | `knowledge/KnowledgeController.java`、`KnowledgeFileController.java` |
| 自动化助手 | `/api/v1/automation-agent` 等 | `automation/web/` |

查找方法：先用 `rg --files src/main/java` 定位 Controller，再读类级 `@RequestMapping` 与方法映射的组合。不要仅按文件名猜路径，也不要把独立 Python 服务的路径加到 Java Controller。

通用工作流列表的页码从 **0** 开始（`page=0&size=20`，size 最大 100），事件为 `GET /api/v1/workflow-jobs/{id}/events?after=0`，使用 `text/event-stream`。财务记录列表从 **1** 开始，每页 50；不同接口不共享一个统一分页假设。

## 财务源订单与导出

全部订单示例（需已登录 Cookie，店铺 ID 使用当前公司真实的财务连接 ID）：

```http
GET /api/v1/finance/records?kind=ORDER&orderScope=ALL&start=2026-10-01&end=2026-10-31&page=1
```

- `orderScope=ALL` 按店铺本地创建日期选择已采集官方订单，含取消、未结算、其他月结算；这是页面默认视图。API 不传该参数仍走既有结算口径，接入方应显式传值。
- `orderScope=SETTLED` 保留按首次结算日期的订单视图。源订单查询不改变总览、商品成本或利润核算。
- `numbers` 为完整订单编号查询；仅全部订单视图允许跨全部创建日期，同时保留公司、店铺、分组、平台和币种限制。
- `shopId`、`groupId`、`platform`、`currency` 是范围过滤；`displayCurrency=CNY` / `fxSnapshot` 是常规展示换算选项。
- 单条详情 `GET /api/v1/finance/records/{id}` 也需传相同 `orderScope`；CSV/XLSX 用 `GET /api/v1/finance/export` 并带同一筛选及 `view=ORDER&kind=ORDER&format=csv` 或 `format=xlsx`。

全部订单的金额与记录数不能被解释成已结算合计。结算金额 0 是有效结算；无结算应显示未知/暂无，不能用 0 替代。详细验收见[源订单记录](../finance-source-orders-20261105.md)。

## 异步费用矩阵

`POST /api/v1/finance/fee-exports` 必须提供 `Idempotency-Key`，JSON 包含 `start`、`end`、`groupId`、`shopId`、`platform`、`currency`。任务固定提交时的筛选；`GET` 同路径列表、`GET /{id}` 查状态、`GET /{id}/download` 下载。

这个导出始终按原币分工作表，不使用看板人民币汇率，不合并跨币种总额。任务与文件只在内存暂存，完成后 30 分钟过期，后端重启会清除。未知提交结果由用户显式使用原幂等键和请求核对，不自动重新 POST。参见[费用导出验收](../finance-fee-export-20261104.md)。

## 成本后台分片导出

`POST /api/v1/finance/cost-exports` 提供 `Idempotency-Key`，JSON 保留成本页的 `start/end/shopId/groupId/platform/currency/kind/recordId/costStatus/numbers`。同一个键不可换条件，未知提交结果使用原键核对，不自动重建任务。

`GET` 同路径列出本人任务，`GET /{id}` 返回状态及进度，`GET /{id}/download` 重新鉴权后输出文件流。任务所有者使用真实公司+用户，数据查询仍用财务公司范围。状态为 `QUEUED/RUNNING/READY/FAILED`；文件名和 Content-Type 取实际结果，小文件为 XLSX、多份为 ZIP，不固定扩展名。失败不返回部分“成功”结果。

任务只读固定快照，临时文件与进度不写账本或同步状态。结果保留 30 分钟，重启后不存在的任务明确提示重新生成。原同步 `/workspace/costs/orders/export` 接口保留其容量保护；页面大范围导出使用后台接口。单片、任务及临时存储上限见[成本说明](../business/finance/costs.md#全店待补齐与批量导出)。

## 接口变更时的最低核对

修改请求字段、枚举、分页或错误格式时，同步检查前端、插件及桌面客户端调用方。核对未登录 401、无权限 403、跨企业/店铺访问、重复提交及过期结果；需要完整接口清单时从 Controller 和服务端测试维护，避免复制一份不再更新的手写协议。
