# TikTok 商品详情图片（2026-09-10）

> 历史发布记录；镜像、测试与“当前/最新”均指记录当时。 当前入口见[业务说明](../../business/README.md)与[运维指南](../../guides/operations.md)。

## 官方要求核对

直接读取当前 TikTok Shop 官方文档页面：

- [Create Product](https://partner.tiktokshop.com/docv2/page/create-product-202309)：`description` 为 HTML，最多 10,000 字符；最多 30 个 `<img>`，每个必须有 `src`、`width`、`height`，图片尺寸小于 4000px，并使用 TikTok Shop 图片 URL。
- [Upload Product Image](https://partner.tiktokshop.com/docv2/page/upload-product-image-202309)：所有商品图片必须先通过该接口上传；`use_case=DESCRIPTION_IMAGE` 用于描述图；响应 `data` 提供 `uri`、`url`、`width`、`height`。描述 HTML 的 `src` 使用响应 `url`，不能使用原平台图片 URL，也不能把 `uri` 直接作为 URL。

官方原文：

> Image guidelines: You must use TikTok Shop image URLs. Max 30 `<img>` tags, each under 4000px with src, width, and height attributes.

> DESCRIPTION_IMAGE: An image used within the product description.

## 独立实现

新增 `TikTokProductDescription`（Spring Component）：

```java
TikTokProductDescription.PreparedDescription description =
    descriptionHelper.prepare(capture, images, connection);
body.put("description", description.html());
receipt.put("descriptionImageCount", description.imageCount());
```

调用应在进入 Create Product 不可逆边界前，并保留原有 listing_check。可删除原 `htmlDescription` 调用和不再使用的旧方法。

- 图片来源：`ProductCaptureImage.roleName=DETAIL`；按 `sortOrder` 排序，仅接受当前组织、当前采集商品、`PROCESSED` 的处理结果。
- `downloadProductImage(processedUrl,image)` 保留水印图片归档的组织/商品/图片归属校验。
- 原 HTML 的图片在原位置替换为已处理且重新上传的图片；未出现在 HTML 的 DETAIL 图依次追加，同一个处理后 URL 只上传/显示一次。
- 原 HTML 若引用未采集的图片，明确要求补采，不透传未经处理的原平台图片。
- 缺失官方返回 URL、超过 30 张或最终 HTML 超过 10,000 字符时停止创建商品，明确说明原因；不默默丢图。
- 原图小于 4000px 时保留原处理字节；长图按比例缩到最大 3999px，PNG 超过 10MB 时改用 JPEG。极窄超长图片缩放后不足最小 100px 时明确提示先分段。

主流程还需把 `DETAIL` 排除在最多 9 张主图候选以外；主图和规格图的归档下载也应改为 `downloadProductImage(url,image)`。这些 Provider 集成改动由主任务统一完成。

## 验证

13 项 helper 测试覆盖：图片顺序和处理归档、原 HTML 原位替换、URL 去重、检测无水印后的同原图 URL 上传、描述内主图重新上传、未采集/未处理图拒绝、组织及商品隔离、30 张限制、10,000 字符限制、缺失官方 URL、长图等比缩放、正常图片字节保留及超长图明确报错。

未在此子任务创建远端商品或更改运行部署。
