# 财务 BI

入口：`#finance-overview`。仅平台超级管理员可见和使用；不能通过给普通管理员分配权限开放此模块。服务端每个财务接口均通过 `OrganizationAccessPolicy.isPlatformAdmin` 校验，数据按企业和用户隔离。

## 页面

- 财务总览：净结算、订单、退款、平台费用、广告消耗、外部成本；收入趋势、店铺排行、费用构成、订单状态、广告成本趋势及店铺汇总。
- 结算中心：账单、交易明细、TikTok 官方预计待结算。
- 对账中心：同店铺、账单编号、币种的账单金额和结算交易金额核对。明细分页未完成、金额未知、数据缺失不会标为一致。页面最多 1,000 张账单，Excel 导出支持当前筛选的全部账单（最多 100,000）。
- 订单与退款、费用与调整：分页明细及趋势。
- 广告分析：TikTok 广告账户消耗、曝光、点击、转化；可选 GMV Max 专项消耗、归因收入、SKU 订单数及归因 ROI。两种消耗不相加。
- 财务店铺管理：复用现有店铺授权；财务开关、数据、同步进度独立。初始关闭同步，由超级管理员按店铺开启。

沿用 HR 看板的 Tabler 设计参考和本地 ECharts 6.1.0。原有首页、全局配色、助手和 HR 业务不变。支持日、周、月、季度、半年、年聚合；这里的 K 表示业务统计周期，不是证券开高低收蜡烛图。

## 人民币切换

“原币 / 人民币 ¥”一键切换看板、图表、明细和导出。原币模式按单一币种统计；人民币模式可把不同币种分别换算后汇总。原始账单永不被改写。

参考汇率来源：ExchangeRate-API 免费接口 `https://open.er-api.com/v6/latest/CNY`，页面及导出提供来源链接。将以 CNY 为基准的报价取倒数，得到每一单位原币对应的 CNY。使用 BigDecimal 计算，换算金额保留六位用于汇总，页面显示两位。

每天缓存一个参考快照。外部服务故障时最多使用三天内快照，并显示汇率日期；没有可用汇率或某币种缺失时明确报错，绝不只换算部分金额后冒充总额。刷新按钮重新选取可用快照；翻页及导出固定使用当前看板快照。

采用同一参考汇率重述所选时期的金额，**不等同历史交易汇率、银行结汇金额或人民币会计账簿**。原币对账结果不受人民币四舍五入影响。

## 官方接口与口径

| 数据 | Shopee | TikTok Shop / TikTok for Business |
|---|---|---|
| 订单 | `/api/v2/order/get_order_list` + `/get_order_detail` | `/order/202309/orders/search` |
| 退款 | `/api/v2/returns/get_return_list` | `/return_refund/202309/returns/search` |
| 已释放结算 | `/api/v2/payment/get_income_detail`，`income_status=1` | `/finance/202309/statements` |
| 结算拆分 | `/api/v2/payment/get_escrow_detail` | `/finance/202501/statements/{id}/statement_transactions` |
| 预计待结算 | 当前版本不以订单金额推算 Shopee 待结算 | `/finance/202507/orders/unsettled` |
| 广告消耗 | 支持导入平台账单 | `/open_api/v1.3/report/integrated/get/` |
| GMV Max 专项 | 不适用 | `/open_api/v1.3/gmv_max/report/get/` |
| 采购、头程等成本 | 手工导入 | 手工导入 |

当前不提供资金流水、提现、银行到账、打款接口。平台结算不意味着银行到账。

订单按创建日期，结算和费用按平台结算日期，广告按平台返回的账户报表日期。待付款及取消订单保留记录但不计入订单金额；只有已完成退款计入成功退款金额。空金额保留未知，不伪造为零。

TikTok 结算扣费的负数转为费用正数，退款返还相反；原始响应保留。平台费用已含在净结算中，不再次扣减。结算账单与明细不能相加，广告归因收入与订单金额不能相加，也不作为净利润。

GMV Max 需在广告账户连接弹窗填写可选的广告平台店铺 ID（不是假定商品店铺内部 ID）。使用官方 `advertiser_id, stat_time_day` 维度和 `cost, orders, gross_revenue, net_cost` 指标。该专项授权出错不会阻塞普通账户报表；错误单独显示。跨店账户可保持未分配，不强行摊到某个店铺。

广告令牌单独通过 `/advertiser/info/` 验证，AES-GCM 加密保存，不回显。店铺授权不等于广告授权。广告接口及导入的广告费用不能在同一个绑定店铺重复接入。

## 同步与数据质量

- 订单、退款：每 10 分钟。
- 结算、待结算、广告：每小时。
- 初次分批回补近 90 天平台仍提供的数据；凌晨后的首次增量回看最近 30 天。平台权限、接口保留期和店铺开通时间决定实际覆盖范围。
- 预计待结算为动态快照，每小时完整检查最近 90 天及系统仍保存的更老待结算记录，只有分页完整后才清理消失的预估记录。
- 独立单线程任务，不阻塞原来的全局定时器；持久化租约、游标和时间范围，失败指数退避，最大六小时。
- API 稳定 ID 幂等更新，不按名称/金额判重。未识别响应结构不会当作“无数据”推进游标。
- TikTok 账单逐页抓取交易，完整后才改为已结算对账状态。上游数量在分页期间变化则重放该账单/时间窗，已有记录幂等覆盖。
- 原始响应仅用于留存和校验，详情接口不直接返回可能包含买家信息的原始 JSON。

## 导入、导出

支持 UTF-8 CSV / XLSX，标题行映射，单次导入最多 20,000 行、12 MB。类型为账单、广告费、外部成本；验证全部行后事务提交。相同文件与映射重复导入不重复入账；记录编号稳定时允许更新原记录。文件中的公式不执行，导入需先转为值。

导出按当前平台、店铺、日期和币种筛选，不限于当前页面；最多 100,000 条。Excel 包含明细、统计口径、对账，人民币模式增加汇率快照及换算列。金额单元格可直接计算；订单等长 ID 保持文本。CSV 做公式注入防护。

## 部署及验收

迁移 `V117__finance_bi.sql` 仅新增六张财务表，不修改已有业务表或普通角色权限。专用发布脚本 `scripts/release-finance-bi.py` 在现网镜像上增加 finance 包及迁移，覆盖四个已审阅前端文件；验证所有其他 JAR 项和其他运行容器保持不变。

发布前留存数据库快照和旧镜像。不会打断正在执行的应用或 HR 请求；发布前检查任务和租约。回滚保留新增财务表，不能删库回滚或改写 Flyway 历史。

自动测试覆盖超管限制、用户隔离、时间聚合、混合币种转换、固定汇率导出、未完整账单、平台异常结构、幂等 ID、导入原子验证、数值 Excel、CSV 公式防护、XXE；另在隔离 MySQL 库验证迁移、外键、金额精度和对账 SQL。

本地 UI 验收数据仅在独立预览服务模拟，绝不进入生产。真实广告数据需管理员提供有效广告授权；真实店铺数据需开启财务同步后检验相应平台权限。

官方参考：

- TikTok Shop 文档：`https://partner.tiktokshop.com/docv2/page/get-statements-202309`
- TikTok Shop 结算交易：`https://partner.tiktokshop.com/docv2/page/get-statement-transactions-202501`
- TikTok Shop 待结算：`https://partner.tiktokshop.com/docv2/page/get-unsettled-transactions-202507`
- TikTok 广告指标：`https://business-api.tiktok.com/portal/docs/metrics-in-gmv-max-campaign-reports/v1.3`
- Shopee Open Platform：`https://open.shopee.com/developer-guide`
- 参考汇率说明：`https://www.exchangerate-api.com/docs/free`
