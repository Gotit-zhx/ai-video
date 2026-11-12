# 商品成本资料可靠性改造设计

日期：2026-11-12。用户已批准聊天中的完整方案并要求实施。基线为 `develop` fe7b597，加已验证的 SKU/SPU 前置变更（b03bbb1、0061a2c）；本功能分支从 develop 创建并以 no-ff 合入该前置分支。普通开发不操作共享服务器、凭据或生产数据库。

## 目标与保护边界

所有外部成本文件与人工成本变更进入持久候选批次；可靠资料自动处理，冲突不按上传时间覆盖。修改有明确期间、来源、单位及影响报告，正式结果按有效版本提供。保留 ERP 实际凭据优先、人工已确认关系优先、SKU→SPU、GIFT、取消/退款、首次结算及租户隔离口径；不新增利润扣项。平台已结算与财务关账分别处理。

仅使用现有 Java 21/Spring/JDBC/MySQL/原生 JS，无新外部服务。迁移仅新增 V153，不修改已应用迁移。自动重算不追加完整 before/after JSON；只保存当前结果、候选受影响结果、配置版本与简明审计。不实施未经验证的 ERP 接口，不伪造来源可信度。

## 数据与状态

- `finance_cost_changes`：公司/分组/可选店铺、类型、原始来源及内容哈希、语义哈希、原始解析或错误、规范化 payload、基线签名、状态、风险、校验及预演、操作者/复核者、版本、游标与处理进度。类型 IMPORT / REMOVE_SOURCE / MANUAL_CREATE / MANUAL_UPDATE / MANUAL_DELETE / MAPPING / SETTINGS / ROLLBACK。状态 QUEUED / VALIDATING / BLOCKED / REVIEW / REVIEW_SECOND / PREPARING / ACTIVE / REJECTED / STALE / FAILED。来源证据不能由客户端的 trusted=true 决定。
- `finance_cost_group_state`：公司/分组活动 revision、活动批次及生效时间。现有来源以 LEGACY_UNVERIFIED 标识，保留原计算结果，不迁移成“已审核”。组行锁串行化生效与版本修改。
- `finance_cost_change_results`：仅受影响订单的候选商品成本及输入哈希/旧成本/任务水位，按 change/order 主键分页保存；候选永不作为正式结果提前展示。
- `finance_cost_periods`：公司/分组/月关账状态及理由/版本。已关账变更需两个不同于提交人的独立复核者；普通人工复核须与提交人不同。生效前重验审批绑定的 payload、基线和期间版本。
- `finance_cost_source_profiles`：公司/分组来源标识、模板/单位/编号类型/仓库别名、确认状态及复核者。对人工上传，首次或不可靠资料需独立复核；仅已确认来源、完整单位和身份、无覆盖冲突且影响受控的补充资料可自动生效。来源标识须与已确认配置绑定，不因填写文件名获得可信度。
- 仓库以稳定公司/分组内 ID 识别，别名只由已确认配置关联；现有仓名保留兼容显示与旧结果。不得从不明仓名猜国家并自动确认。

## 校验与风险

校验输出统一 `{code,severity,message,source,action,sku,warehouse,month,row}`，severity 为 BLOCK / REVIEW / INFO。格式不合法、公式、损坏的长数字、非人民币、同批同键不同价、缺少/歧义身份、单位不明均不得直接生效。相同键为公司、分组、仓、编号类型、型号、月份、单位；同值幂等。输入缺行只补充，不代表删除。不同有效价格需要明确修订，不按最后上传时间判断真实性。

初始风险阈值可配置：单价变化比例超过 20% 且每单位差额超过 1 元；预演成本绝对变化超过 1000 元或相对变化超过 5%（旧金额大于 0 才计算比例）；造成新增未完成订单、仓/单位/换算改变、已有人工关系替换、回溯月份均需复核。这些为初始产品保护参数，不代表真实数据分布验证。新型号没有历史价时不能自动假定可信。SPU 需有明确适用规格/统一成本证据，否则只生成候选。

编号保持字符串，科学计数法或超过 Excel 安全整数精度的数值编号拒绝，不修补成猜测 ID；保留原行号。四列/月横排成本表继续可读；缺单位/编号类型/来源的老格式进入待确认，页面提供批次级字段补充，不能静默按“件”处理。

## 生效与期间

导入及所有人工写入的现有 HTTP 入口改为提交候选，不能通过旧接口直写绕过；内部兼容的应用方法仅用于已校验的生效事务及测试夹具。每次变更要求 reason 与 evidenceRef；修改已有价格必须声明 revision。人工固定价、关联及设置需有效起止月份，缺省为当前成本月份而非全部历史/未来。历史旧配置作为兼容基线保留，新的期间版本按业务成本月份选择，不能因更新当前配置丢失之前期间的关系。

候选后台每次最多读取/计算 100 笔订单，持久游标、重启恢复；预演涵盖目标组/店铺中受期间影响的已入库订单，金额影响按已有首次结算口径另外统计，明确已覆盖范围。复核绑定本次预演及基线。每批使用来源哈希和配置 revision；基线/订单输入变化重验并重新准备，不使用过期结果。

所有候选准备完毕并核对输入水位后，在组锁事务内写入有效配置并切换活动 revision。可使用 `finance_current_order_costs` 视图将活动候选结果覆盖旧结果，普通重算在同版本和新输入下更新当前结果；所有成本查询、导出、队列判断与千易参考成本读取同一活动资料。预演期间继续提供旧有效结果并提示更新中；没有旧结果的订单保持未知。不能通过复制全历史 JSON 或一个无界大事务实现切换。旧任务生效前校验组 revision，过期任务不得覆盖新版本。贴单独立任务不能覆盖商品成本。

撤销以新候选版本恢复上一个有效配置并预演重算，不删原始来源/订单/账本。文件撤回为状态化来源撤回，不物理丢失证据。关账期间显示明确调整范围，保留原版本与修订记录。

## API 与页面契约

统一前缀 `/api/v1/finance/workspace/cost-governance`，沿用财务授权、manage、同源校验及公司范围。

- GET `?groupId=&shopId=&page=` → `{changes,profiles,periods,state,policy,page,total}`；批次含 id/type/status/risk/sourceName/submittedBy/effectiveFrom/effectiveTo/createdAt/version/progress/validation/impact/canReview/reviewCount。
- GET `/{id}` → 完整批次（规范化记录分页、有界异常示例，不输出完整订单或私密凭据）。
- POST `/{id}/revise` → reason/evidenceRef/effectiveFrom/effectiveTo/unit/identifierType/sourceKey/warehouseAliases/spuUniform/revision/version，重新校验并使旧审批失效。
- POST `/{id}/review` → version/reason/decision APPROVE或REJECT；独立身份校验、审批绑定预演，重大/关账第二人复核。
- POST `/{id}/retry` → version；仅可重试当前范围，不重置其他同步。
- POST `/{id}/rollback` → reason/effectiveFrom/effectiveTo；新候选而非物理删除。
- POST `/profiles` → 来源配置提交/复核（不能自审直接信任）；POST `/periods` → month/closed/reason/version，有明确权限及范围。
- GET `/issues?groupId=&shopId=&page=` → 分页结构化当前异常，包含订单号、来源、原因、下一步、处理入口和最后更新时间；排队、缺资料、系统失败分别展示。
- 原上传 `/costs/import` 增加可选 metadata JSON 字段，返回 `{staged:true,changeId,status,rows,duplicate}`。原 settings/mapping/manual/remove HTTP 返回相同候选信息。前端不能继续提示“已生效/已重算”。

成本管理新增“资料校验与版本”“成本异常”两个页签；导入表单补来源、单位、编号类型、适用月份、修订意图。人工编辑补期间和依据。列表显示状态、进度、影响订单/金额和复核入口，支持查看差异、补充说明、驳回、重试、撤销版本；不提供忽略全部异常或按零填充按钮。来源配置和关账放在此管理页，不新增全站导航。只读用户可看不可写，提交者不展示自审入口。任何轮询仅刷新数据不重复提交；失败保留上次有效结果并给出错误。

## 验收

单元/隔离 MySQL/真实 DOM 交互覆盖：语义重复、旧文件误覆盖、分组/仓隔离、损坏长ID、单位冲突、SPU规格差异、缺价与真零、适用月、既有人工关系保护、独立及双人复核、审批后输入变化、期间关账、任务重启和过期任务、候选不影响正式汇总、切换一致、只撤销本版本、来源不丢失、导出范围及价格追溯。迁移在可销毁数据库验证旧数据保留和新表索引；所有现有成本回归保持，不能通过全局绕过治理使测试变绿。
