# 裂变任务恢复完成

> 历史发布记录；镜像、测试与“当前/最新”均指记录当时。 当前入口见[业务说明](../../business/README.md)与[运维指南](../../guides/operations.md)。

批次 3c0ed604-fbd7-4d43-a1c2-6c3ca2b9b217；执行 275ca06c-3b7a-46c9-a4d8-43bc617d6ea9。

完成状态：COMPLETED / PUBLISH。20张 DRAPED_FRONT READY，2款草稿上传成功，Shopee商品编号42584676478（V1）、52068247342（V2）。原工作流57344107-31e8-4968-8277-311dae8a2a47及24bf0e44-e64f-45c6-a10c-6b6e880f3093均SUCCEEDED/COMPLETED。保留历史失败审计，没有重复创建商品。

本次只重生1张母图，新job1fa6a74f-0188-479b-9450-1710870fc585。原母图保留为RETRIED，原20张精确套版及印花保留。调用预留6→7，上限16不变。新图完整无触边，离线粗轮廓归一化印花宽高比0.9975/1.0011，中心偏差0.0010/0.0023；视觉检查版型保持模板。量化仅用于本次辅助检查，不能等同所有生成图的自动版型保证。

持久修复：
- CommerceBatchArtworkRetryService增加显式READY母图重试；验证停止状态、无租约/未决任务/已发布记录/派生图，校验GUIDE和印花绑定，使用最新模板优先提示与参考顺序，原预算预留及幂等键。18项相关测试通过。
- 前端母图卡片增加“按模板重做母图”，不自动触发付费调用。40项相关测试通过。
- ShopeeOfficialPublishingProvider商品图统一走downloadProductImage，修复commerce归档相对地址误走公网HTTPS校验。22项图片专项及21项启动/视频/TK测试通过。扩展规格mock测试有已存在的0/50回读模拟失败，与本次下载路径无关，未改模型同步逻辑。
- ShopeeAttributeResolver和ShopeeSizeChartExtractor补齐归档图证据读取；原source_key和普通原图优先，严格commerce别名与组织/商品绑定，不放宽外链安全校验。16项测试通过。

生产最小类覆盖前均编译修改前源码，对比当前生产javap字节码一致。后端ai-commerce-platform:drape-resume-20260921，前端heyi-ai-frontend:drape-retry-20260921，health UP、公开脚本核验完成。仅在原任务明确未跨外部创建边界时重排同workflow，保留原失败audit并转移其已知失败幂等key，后续真实平台回执确认成功。

操作脚本与私有诊断图片在output/drape-resume-20260921。恢复直接使用受保护事务以避免要求用户重新登录；必要条件不满足时SQL CHECK中止并回滚。用户授权为“让任务继续执行并修复出现的问题”。
