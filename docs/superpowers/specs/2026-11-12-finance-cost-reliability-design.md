# 人工成本输入防错设计

日期：2026-11-12。用户最新范围：**只预防人工传入表格和人工操作的不可靠性，其他内容不处理**。本说明替代同文件上一版广泛治理设计；旧版仅保留在 Git 历史，不作为实施要求。

## 范围

仅在商品成本人工入口增加“暂存→校验/差异和影响范围预览→确认或独立复核→原有保存和重算”。覆盖成本表上传、成本来源移除、人工成本新增/修改/删除、人工 SKU 关联及成本设置。平台订单同步、ERP、自动 SKU/SPU 匹配、计算公式、成本 worker、报表/导出查询及其状态保持现有实现。不新增结果视图、全量成本版本切换、财务关账、来源连接器、全局仓库主数据或自动数据异常工作台。

不增加新的计价期间规则。预览必须展示操作真正影响的范围：分组文件影响分组相关店铺，人工关联/设置影响当前店铺，固定人工价格适用相关规格历史和后续订单。若现有保存会重算历史，必须明确显示并单独确认；不能让用户误以为只修改了当前查询月份。

## 人工输入暂存和校验

新增 V153（不改旧迁移）保存 `finance_cost_input_changes`，字段含公司/分组/店铺、类型、状态、payload、原始文件 bytes/hash、语义 hash、当前基线签名、校验结果、差异、影响范围摘要、提交人/确认人/理由/凭据、version、created/updated/applied。类型 IMPORT、REMOVE_SOURCE、MANUAL_CREATE、MANUAL_UPDATE、MANUAL_DELETE、MAPPING、SETTINGS、UNDO。状态 BLOCKED、REVIEW、READY、APPLIED、REJECTED、STALE。只保存显式人工操作的资料和审计，不改自动订单结果存储。

原有人工 HTTP 写入口统一提交暂存单，不能再直接写正式成本。既有内部应用函数可保留 package-private，供经过验证的应用事务及原回归夹具使用；前端旧接口也不能绕过。上传成功返回 staged，不宣称生效。自动 auto-match 不进入人工审批，不改其行为。

表格保持现有四列和月横排格式，补充 unit、identifierType（SKU/SPU/MIXED）、sourceKey/evidenceRef、reason、revision、spuUniform 元数据；编号列可显式指定每行类型，不能仅凭数字猜测 SPU。缺单位/类型、SPU统一成本尚未确认需要补充信息；文件名或上传者声明不当作已验证数据来源。可识别的无变更重复导入直接返回重复，不产生第二次写入或重算。

硬性拦截：缺必要列、同批同键不同价、公式、负数/非法值、损坏/科学计数编号、XLSX数值长编号丢精度、非CNY、空仓库、无效型号、越权分组/仓库和无效单位换算。Excel未知零成本不得直接确认通过；真实零由现有手动成本零价确认流程处理，GIFT例外保持。已核验同值重复只去重，不因缺行撤销其他资料。

不同文件同仓同型号同月不同价不再按最后上传自动覆盖：预览列出原价、新价、原来源、新来源，必须 revision=true、有效理由/依据，并进入独立复核。新型号/普通新增非冲突资料须确认资料含义，但无需新增组织审批流程。错误资料保留暂存原因，修正可重传或 revise 元数据，旧审批失效。

## 操作预览与确认

预览至少包含新增/未变/改价的成本单元数量、逐行异常（示例有界）、旧新配置差异、影响店铺数量/订单数量及历史范围、当前固定人工成本的真实适用范围。金额影响只有能够按现有数据可靠得到时展示；不能将样本估算声称全量准确金额。数据不足显示未核定并按高风险处理。

低风险：有明确资料、身份/单位、无覆盖冲突、无异常的新增和不改变既有结果的操作，可由提交者确认后沿用原有写入；语义无变化不写。高风险：已有价格覆盖、仓/计价口径/换算改变、已存在人工关联替换、大范围修改/删除或影响不能核定，必须另一个有 finance:manage 的同公司用户复核；提交者不能自审。原始硬错误不可通过复核忽略。初始单价风险值20%且绝对差额>1 CNY，仅作风险提示，不代替覆盖冲突门控；大范围按影响超过1000笔标记，阈值集中常量并在响应说明。首次新目录缺历史比较并不意味着已验证真实性。

确认请求必须携带 version、acknowledgeImpact、acknowledgeHistorical（涉及历史时）、reason、evidenceRef。在同一事务内锁定组/目标配置，重算当前基线签名并核对旧版本和规范化payload；任何其他人工更改导致基线变化即标记 STALE，不能使用旧预览落地。确认人与权限由登录身份得出，不能由客户端填写。重复确认返回原结果，不重复保存/重算。错误事务回滚，保留旧有效配置。

原始文件保留一次，12MiB沿用上限；详情接口只提供有界解析/差异，不返回完整原文件或全部业务订单。删除来源原始资料保存在本次显式输入记录，审计保留。已应用操作可“撤销本次操作”：生成反向暂存单并重新做同样的基线/影响/复核，不能回退其他后续更改。若后续修改使反向恢复不唯一，明确拒绝并提示重新预览。

## API/前端契约

沿用 `/api/v1/finance/workspace` 权限与同源校验。

- GET `/costs/input-changes?groupId=&shopId=&page=` → `{items,total,page,pageSize}`；每项 id/type/status/groupId/shopId/sourceName/submittedBy/version/createdAt/validation/impact/requiresIndependentReview/canConfirm/canReject。
- GET `/costs/input-changes/{id}` → 该暂存单详情，含 diff、metadata、actualScope 和有界 rows/issues。
- POST `/costs/input-changes/{id}/revise` → metadata、version，重做校验/差异，已拒绝或已应用不能修改。
- POST `/costs/input-changes/{id}/confirm` → version、reason、evidenceRef、acknowledgeImpact、acknowledgeHistorical、revision（冲突时）；执行确认/独立复核和原有保存。
- POST `/costs/input-changes/{id}/reject` → version、reason；取消暂存，不改正式成本。
- POST `/costs/input-changes/{id}/undo` → version、reason、evidenceRef，生成反向暂存单。
- 原 `/costs/import` 增 optional metadata JSON multipart；所有原人工变更返回 `{staged:true,changeId,status,duplicate?,rows?}`。
- `/costs` 只增加 `inputSafety:{enabled:true,pendingCount}`，使前端启用暂存语义；不改变原成本结构。前端新页签“导入与操作核对”，不新增自动订单异常页。

前端使用 `FinanceCostInputSafety.mount(root,{request,groupId,shopId,canManage,onChanged})`，返回 dispose。新增模块 `frontend/finance-cost-input-safety.js`。表格上传和人工操作补理由/依据，显示真实影响范围和待确认状态；暂存详情显示旧新值、来源、单位、数量、风险、分组/店铺，并提供补充资料/确认/驳回/撤销按钮。read-only不显示写按钮，高风险提交者不能看到自审按钮。轮询仅GET，失败保留旧显示，所有不可信文本用textContent。兼容本地预览；线上 enabled=true 时旧按钮不能继续提示立即已保存。

## 验证

纯校验先RED后GREEN。隔离MySQL验证暂存不改原值、冲突不覆盖、跨公司拒绝、自审高风险拒绝、过期预览拒绝、重复确认幂等、失败回滚、删除可查来源、撤销不覆盖后续变更。真实JSDOM交互验证表格/人工操作提交暂存、元数据和确认ack、只读/自审限制、差异提示、失败保留内容、GET-only刷新。重跑原商品成本/SKU-SPU/导出回归，证明自动计算链路没有改动。当前任务只本地实施和验证，不部署到共享服务器。
