# TikTok 商品视频上传契约与实现

> 历史发布记录；镜像、测试与“当前/最新”均指记录当时。 当前入口见[业务说明](../../business/README.md)与[运维指南](../../guides/operations.md)。

2026-09-10 使用独立浏览器打开官方文档并展开视频子字段核对。

官方来源：

- [Upload Product File 202309](https://partner.tiktokshop.com/docv2/page/upload-product-file-202309)
- [Create Product 202309](https://partner.tiktokshop.com/docv2/page/create-product-202309)
- [Check Product Listing 202309](https://partner.tiktokshop.com/docv2/page/check-product-listing-202309)
- [Get Product 202309](https://partner.tiktokshop.com/docv2/page/get-product-202309)

Upload Product File 使用 `POST /product/202309/files/upload`，权限为 `seller.product.basic`；Header 携带卖家授权 `x-tts-access-token`，Query 为 `app_key/sign/timestamp`，不要求 `shop_cipher`。multipart 表单包含二进制 `data` 和包含扩展名的 `name`；文件名不能有空格，不能以符号开始，扩展名前以外不能有句点。

官方支持 PDF 以及 MP4、MOV、MKV、WMV、WEBM、AVI、3GP、FLV、MPEG 视频。视频最大 100 MB，画面比例必须在 9:16 至 16:9 之间。1:1、720p 以上和 20–60 秒属于推荐值，未擅自当成硬性限制。当前采集归档入口仅接受 64 MiB 以内 MP4/WebM，因此自动发布仅取现有这两种归档格式。

上传成功返回 `data.id`。Create Product 和 Check Product Listing 接受单个 `video: {id: ...}`，Get Product 返回 `data.video.id`。接口没有在此契约提供媒体任务轮询编号或查询接口，因此不能假设存在 Shopee 式分片/转码状态机。

实现只读取同公司同商品的本地归档，排除已被去重替代的视频，并校验文件长度、SHA-256 与路径；不从发布请求中的视频 URL 下载内容。存在多个有效归档时按 `created_at,id` 选第一个，回执记录来源视频总数，单个商品只上传一个视频。

V93 共享媒体检查点按公司、目标店铺、内容 SHA-256 和应用/接口环境隔离；独立事务保存成功媒体 ID，产品事务回滚后仍可复用。数据库租约避免同视频并发重复上传。请求尝试另表按媒体 ID 和固定请求 idempotencyKey 的 SHA-256 隔离，网络中断、超时或缺少 ID 使用现有工作流媒体等待机制，同一请求最多持久化尝试三次。旧工作流交替轮询不会重置预算；用户新建批次使用新 key 可以重新尝试，已有成功媒体仍跨批次复用。上传接口已有 90 秒完整响应期限；视频失败不会穿过创建商品边界。响应丢失时有限重传可能留下未绑定的媒体文件，但不会触发商品创建。

取得媒体 ID 后仍需通过 listing_check，并在商品创建后核对 `Get Product.data.video.id`；视频缺失或不匹配会进入现有远端结果待核查流程，不标记成功、不盲目重建商品。已完成媒体检查点不能替代商品回读校验。

本代码任务未调用真实媒体上传或创建商品接口；正式环境权限、网络吞吐、具体视频比例及最终平台接受结果仍须通过已授权测试店铺验证。
