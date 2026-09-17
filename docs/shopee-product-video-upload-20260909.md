# 采集视频随商品上传 Shopee

单独上传与批量上传都经过 `ShopeeOfficialPublishingProvider`。该入口现在自动读取当前企业、当前采集商品的有效 `product_capture_videos`，上传并等待 Shopee 转码完成，再把 `video_upload_id: [上传编号]` 写入 `add_item`。保持 `item_status=UNLIST`；无采集视频时沿用原商品上传流程。

## 接口依据

核对的是公开 SDK 保存的 Shopee 原始接口文档快照，以及另一份独立 SDK 的接口字段；后续已通过已授权失败任务恢复完成真实沙箱验证。本次固定源码版本：

- [congminh1254/shopee-sdk / init_video_upload 原始 schema](https://github.com/congminh1254/shopee-sdk/blob/9865d050057f763344a0064fb831f6157ec7b15c/schemas/v2.media_space.init_video_upload.json)
- [upload_video_part 原始 schema](https://github.com/congminh1254/shopee-sdk/blob/9865d050057f763344a0064fb831f6157ec7b15c/schemas/v2.media_space.upload_video_part.json)
- [complete_video_upload 原始 schema](https://github.com/congminh1254/shopee-sdk/blob/9865d050057f763344a0064fb831f6157ec7b15c/schemas/v2.media_space.complete_video_upload.json)
- [get_video_upload_result 原始 schema](https://github.com/congminh1254/shopee-sdk/blob/9865d050057f763344a0064fb831f6157ec7b15c/schemas/v2.media_space.get_video_upload_result.json)
- [add_item 原始 schema](https://github.com/congminh1254/shopee-sdk/blob/9865d050057f763344a0064fb831f6157ec7b15c/schemas/v2.product.add_item.json)
- [EcomPHP/shopee-php / MediaSpace](https://github.com/EcomPHP/shopee-php/blob/c6210a7b79b10c842bf90f153f86c1877578757b/src/Resources/MediaSpace.php)

| 步骤 | 路径 | 签名及参数 |
| --- | --- | --- |
| 初始化 | `/api/v2/media_space/init_video_upload` | Shop 签名；JSON `file_md5`、`file_size` |
| 分片 | `/api/v2/media_space/upload_video_part` | Public 签名；multipart 文件字段 `part_content`，文本字段 `video_upload_id`、`part_seq`、`content_md5`；序号从 0 开始，除末片外每片 4 MiB |
| 完成 | `/api/v2/media_space/complete_video_upload` | Public 签名；JSON `video_upload_id`、`part_seq_list`、`report_data.upload_cost`（毫秒） |
| 查询 | `/api/v2/media_space/get_video_upload_result` | Shop 签名；GET `video_upload_id`；只有 `SUCCEEDED` 可供商品使用 |
| 关联商品 | `/api/v2/product/add_item` | `video_upload_id` 为字符串数组，文档明确只接受 1 个 ID |

生成的 TypeScript manager 将分片/完成步骤都标为 `auth:true`，与原始 schema 的 `api_type=Public` 和 `common_params` 不一致；实现采用原始 schema 的 Public 签名。PHP SDK 的 multipart 注释也与其 JSON 实现不同，因此分片实现采用原始 schema 指定的 multipart。

文档明确视频时长 10–60 秒、大小最多 30 MB。当前归档接受 MP4/WebM，本次不猜测额外格式要求或自动裁剪时长；先校验本地大小与 SHA-256，实际格式/时长不被平台接受时清晰阻止商品提交，避免无声丢失视频。Shopee 每件商品仅支持一个视频，按 `created_at,id` 固定选择首个有效采集视频；其余视频保留在采集记录，发布回执记录采集数量和说明。

## 持久化和重试

`V91__shopee_product_video_uploads.sql` 新增媒体上传账本，以企业、店铺、视频内容 SHA-256、API 环境及开发者应用范围共同隔离。记录远程上传编号、已确认的下一个分片序号、转码阶段和最终回执；300 秒租约防止同一视频同时上传。所有账本变更通过独立 `REQUIRES_NEW` 事务提交。

已成功的视频复用上传编号；断开的分片从最后确认序号继续，相同分片序号仍使用相同字节和 MD5。`complete_video_upload` 丢响应后先查询：成功直接复用，转码中只等待，明确 `INITIATED` 才再次完成。初始化丢响应且没有得到上传编号时，只能重新初始化媒体会话，可能留下一条未使用媒体会话；这不创建商品，账本在调用前递增 `init_attempts`，最多初始化 3 次。已取得上传编号后的网络恢复始终沿用同一媒体会话。

每轮最多查询 30 次，间隔 2 秒；仍在转码、媒体上传占用或暂时网络失败抛 `VideoUploadPendingException(503)`。`ProductCaptureService` 仅在商品创建边界之前重抛此异常，回滚临时发布审计，让工作流复用原幂等键自动继续。视频有独立的 30 分钟等待期限，后台每 15 秒再次调度，避免长转码耗尽普通失败重试次数。创建边界之后的异常仍保留失败审计，不能触发重复 `add_item`。

所有读取仅允许当前采集记录绑定的本地归档文件，校验文件名、文件长度和 SHA-256，拒绝符号链接；不下载 `source_url`，不接受浏览器传来的任意视频 URL。

视频就绪后的商品图片上传按当前图片最多尝试 3 次，间隔 1 秒、2 秒；已上传图片 ID 和当前图片字节保留，不重复上传前面的图片或重复下载当前图片。仅网络 I/O、明确截断的响应 JSON，以及 HTTP 500/502/503/504 可恢复；结构化明确业务拒绝、所有 4xx、证书错误、中断、本地配置及一般 JSON 格式/映射错误不重试。最终错误和日志只记录图片序号、固定 API 路径、尝试次数、异常类型和 HTTP 状态，不输出签名 URL、query 或响应 body。重试始终位于商品创建边界之前。

## 验证范围

新增模拟测试覆盖分片大小/序号/MD5、Public 签名与 multipart 字段、已上传复用、分片续传、完成响应丢失后的回读、有限转码等待、平台拒绝时不丢视频、企业/店铺/API 环境隔离、归档校验、并发租约、待上架字段和创建边界。发布服务测试使用 Spring 事务代理验证创建前待处理异常回滚，以及创建边界之后保持失败审计。后续经已授权失败任务恢复完成真实沙箱验证：3,270,019 字节视频初始化1次、1片上传完成，远程转码SUCCEEDED；商品846056103携带该视频提交成功并回读UNLIST。对应工作流成功回执见 output/image-socket-failure-20260909/publication-receipt.json。

2026-09-09 最终组合验证：视频、发布服务、图片网关、队列和工作流相关 140 个测试全部通过（0 失败、0 错误），Java 21 Maven `package` 成功。其中图片上传恢复 18 个测试覆盖当前图片重试、先前图片不重传、三次上限、精确响应 EOF、5xx 业务与系统错误分类，以及日志信息脱敏。工作流测试包含恢复后绑定图片任务的企业/商品归属校验和真实幂等键继续处理。
