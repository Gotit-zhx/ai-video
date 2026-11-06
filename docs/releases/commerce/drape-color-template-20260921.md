# 搭放彩色模板自动中性化

> 历史发布记录；镜像、测试与“当前/最新”均指记录当时。 当前入口见[业务说明](../../business/README.md)与[运维指南](../../guides/operations.md)。

原阻断由 CommerceDrapeReuseWorkflow.requireWhiteTemplate 对模板布色做固定阈值判断触发，启动预检和生图准备均调用它。下游搭放处理仍需要白色中间母图，因此没有单纯删除检查。

现在预检验证图片和服装轮廓可处理，不再限制原模板底色。prepareGuide 在内存中将服装蒙版内部转为中性白色，保留相对亮度变化、透明度和蒙版外背景，再贴印花并交给已有白色母图流程。原始归档不变。GUIDE 缓存策略更新为 guide-neutral-v2。无独立 SKU 颜色的模板原色，通过既有 CommerceGarmentColor 从原图提取色值后用于最后渲染，不将白色中间图视作商品原色。

46 项相关测试通过，包括粉色、深色、棕色、白色合成模板的中性化/背景透明度/原色提取，以及搭放母图复用、提交与批次启动。未额外发起付费模型生成，因此真实模型最终效果仍受原图和模型输出影响；保留原有母图、印花映射和输出完整性校验。

部署单类修复（CommerceDrapeReuseWorkflow）。发布前将修改前源码编译并与生产 javap -c -p 输出比对一致，避免带入其他本地修改。新镜像 ai-commerce-platform:drape-color-template-20260921；前一镜像 ai-commerce-platform:batch-env-optional-20260921。活动任务为零才重启，健康检查失败自动回滚。
