# 标题失败后的旧发布任务恢复

> 历史发布记录；镜像、测试与“当前/最新”均指记录当时。 当前入口见[业务说明](../../business/README.md)与[运维指南](../../guides/operations.md)。

2026-09-21 用户反馈修改后点击重试仍显示旧标题错误。数据库证明任务更新时间与失败审计未变：工作流 `4f697b27-d2e6-4d48-9a18-2b66a5c4ea86` 被 `publication_attempted=1` 和目标占位阻止重新提交，`resolvePublicationTarget` 直接返回旧失败记录，未进入新标题适配逻辑。

修复：将严格匹配 `Shopee 官方接口 /api/v2/product/add_item 失败：Product name length must be between <数字> and <数字> characters. Please adjust it.` 的拒绝归类为确认未创建，调用既有 failRejectedPublication 释放占位。超时、未知结果、非 add_item 接口、带已创建信息或额外错误的消息不放行。

测试 23 项通过，包含真实工作流经过创建边界后的标题拒绝释放、防重试超时隔离。修改前本地处理器 javap 字节码与生产一致；仅部署处理器 3 个 class，保留其他全部配置与代码。

旧任务恢复前备份了工作流和审计；事务内锁行并核查精确原始拒绝、FAILED、无 external_id、无同店成功/执行中审计、APPROVED、版本一致、已绑定图片作业。保留原失败审计，将其幂等键后缀标记为 rejected-title；将原工作流改为 PENDING/PUBLISH，从既有图片和视频继续。没有重置整个商品或重新运行生图。

生产部署目录 `/opt/heyi/deployments/shopee-title-recovery-20260921`，包含备份、部署和一次性精确恢复脚本。恢复后的真实状态已确认 RUNNING/PUBLISH。

实测续跑结果：新请求通过标题校验，ADD_ITEM 随后明确拒绝价格低于 5 PHP。73 个 SKU 的现价全为 0.01 MYR，原价字段为 15–17.8 MYR，自动汇率 15.395109 将现价换算为约 0.15 PHP。未擅自使用原价或调整售价。精确核对新失败审计 4233c1c4-ba81-41fc-b559-8d4dccf354f3 无 external_id 后，释放此条明确价格拒绝的目标占位，并保持 FAILED 等待用户确认价格，未再次自动提交。
