# 自动换汇与直接上传

单商品“一键上传”现在准备店铺、原类目匹配、可用物流后直接创建工作流，不要求再输入统一售价或勾选确认。多个店铺未指定、类目无法匹配等实际缺失配置仍会显示表单；无法取得汇率会报错，不按错误币种刊登。

库存 999，重量保留采集原值或默认 250g，无品牌。物流复用当前浏览器的店铺选择，无已保存选择时选官方返回的首个 enabled 渠道。

工作流创建时后端读取 ExchangeRate-API open endpoint 的最新参考汇率，按目标店铺地区决定币种，存储来源币种、目标币种、倍率、提供方、报价时间和获取时间。该公开源每日更新，并非逐秒行情。超过 48 小时或缺失/无效报价阻止提交。每个 SKU 原售价分别换算，按币种最小单位四舍五入；不修改采集价格。

幂等键重放复用原快照，不重新取价；不接受浏览器伪造的汇率快照。Shopee 和 TikTok 发布价格均支持此快照。已有批量上传入口仍保持独立的手动配置流程。

验证：33 个相关后端测试、5 个前端配置测试通过。浏览器用拦截所有写请求的方式验证一次点击直接产生一个上传请求，含 autoConvertPrice=true，没有统一 publishPrice；未执行真实刊登。实际公开源读取：2026-09-09 最新 PHP/MYR=0.064953，155 PHP→10.07 MYR，263 PHP→17.08 MYR。后端和前端本地镜像均已构建部署。

## 实际上传验证与无弹窗（03:04 UTC）

一键上传已取消对话框显示，后台创建流程直接调用提交逻辑，错误回到商品页提示。多规格 ADD_ITEM 也必须带 seller_stock，已修复；当前物流要求包裹尺寸，默认 30×25×2 cm。V85/V86 只释放与明确官方参数拒绝完全匹配的历史记录。

真实沙箱验证：工作流 c49e3a95-99a0-4600-bb3a-f2f35a5205d2 成功创建 Shopee 未上架商品 846055663，但 ADD_MODEL 返回 `The model number exceeds 50. Please reduce it.`。来源共 96 SKU，目前无法在该沙箱单个商品中容纳。此请求已产生真实平台商品，保留防重复锁，不再盲目重新创建。已向用户询问拆成两个商品保留规格，或由用户指定只上部分规格。不得把本次状态报告为发布成功，也不得自动删除规格。

## 03:23 UTC 删除后重试修复

用户在平台删除旧商品后，官方 get_item_base_info 回读 item_status=SELLER_DELETE（output/shopee-sandbox-preflight/deleted-item-check.json）。V87 仅对确切公司/店铺/失败工作流解除防重锁。此前承诺的沙箱前 50 个未实现，现已在 ShopeeOfficialPublishingProvider 按实际 API 主机识别沙箱，对稳定 sort_order 的 SKU 列表截取前 50；生产不截取。原商品保留全部 96 SKU，回执记录 sourceSkuCount/skuCount/sandboxSkuLimited。

新任务 0cac0756-a8de-4694-a179-6c884f71aa5e 已实际创建并开始执行。

完成回执：新商品 846055666，工作流 SUCCEEDED，平台回读 NORMAL，发布审计 190ea124-ab4f-4020-8685-055894bc72ab。前 50 个 SKU 的 model 回读完整后已激活；本地完整 96 SKU 保留。页面刷新后显示绿色上传成功。

## 上传只进入待上架（最终规则）

用户要求上传后不自动上线。Shopee 上传流程已移除 unlist=false 调用；ADD_ITEM 以 UNLIST 创建，规格回读完整后再确认 UNLIST，回执 listingMode=PENDING_LISTING。前端显示“已上传至待上架”。已按用户要求对现存沙箱商品 846055666 调用 unlist=true，并通过官方 GET_ITEM_BASE_INFO 确认 UNLIST；证据在 unlist-846055666.json 与 pending-item-check.json。V88 同步这条已核实回执的远端状态，保留上传成功审计。
