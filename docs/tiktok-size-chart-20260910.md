# TikTok 尺码表自动上传（2026-09-10）

## 官方核查

- [Get Category Rules](https://partner.tiktokshop.com/docv2/page/get-category-rules-202309)：`GET /product/202309/categories/{category_id}/rules`。US/EU/SEA 使用 `category_version=v2`，`locale` 可省略沿用店铺默认，`shop_cipher` 属于该店铺。响应 `data.size_chart.is_supported/is_required` 控制是否可用、是否必需。
- [Create Product](https://partner.tiktokshop.com/docv2/page/create-product-202309)：图片结构 `size_chart: { image: { uri: "..." } }`；模板为 `{ template: { id: "..." } }`，两者都给时模板优先。图片 `uri` 必须通过 `Upload Product Image` 以 `use_case=SIZE_CHART_IMAGE` 上传获得。
- [Upload Product Image](https://partner.tiktokshop.com/docv2/page/upload-product-image-202309)：尺码图短边至少 1024px、文件最多 10MB。不可复用 DESCRIPTION_IMAGE 的上传结果，也不可把外站 URL 放进 `uri`。

## 当前商品证据

71 SKU 商品 `cadfaf79-0b0f-49a1-b298-da9e37e3ecd8` 的已处理详情图 27、28 都是真实尺码表，包含 S/M/L/XL、Chest/Length/Sleeve Length 及测量值。

首张图片 ID 为 `85b00d09-e509-4087-b0c5-28cac1cb0426`。主任务已将经人工查看确认的 ID 记入当前采集商品 `normalizedData.sizeChartImageId`。不是把第一张详情图普遍当尺码表。

证据：`output/tiktok-size-chart-20260910/source-contact-sheet.jpg`、`source-images.json`。官方源 bootstrap 没有 `size_chart` 专有字段，这次属于已采集图未标注尺码用途。

96 SKU 商品在来源描述包含完整真实文本：

```text
Size (weight), length, chest circumference, sleeve length, shoulder width
S(35-40kg)68cm 47cm 19cm 46cm
M(40-55kg)70cm 50cm 20cm 48cm
L(55-65kg)72cm 53cm 21cm 50cm
XL(65-75kg)74cm 56cm 22cm 52cm
XXL(75-85kg)76cm 59cm 23cm 54cm
XXXL(85-100kg)78cm 62cm 24cm 56cm
```

自动路径直接把上述原标签、单位、测量数值排版成图片；不推算、改单位或补列。渲染证据：`output/tiktok-size-chart-20260910/source-measurements-rendered.png`。

## 集成与自动策略

`TikTokProductSizeChart(client, downloader, objectMapper)`：

```java
var chart = helper.prepare(capture, images, connection, categoryRules);
if (chart.present()) body.put("size_chart", chart.payload());
```

每次上传自动按以下证据顺序选用：当前商品明确标注的 `sizeChartImageId`；`SIZE_CHART` 图片角色或仅为 Size Chart/Size Guide/尺码表/尺寸表等短用途标签；来源描述中的有标题、真实测量与单位的尺寸段。

文本支持单行、换行、S/M/L/XL/XXL/2XL 等尺码，以及中文衣长/胸围等标签。单位可在每个值中，也可明确给在表头。无完整测量、行缺值或仅有尺码列表不会生成；必需类目明确提示补采真实尺码表。多个不同用途图片而未指定唯一图时提示确认，不猜测。

图片归属、处理状态及本地归档校验均保留。短边不足 1024px 的原尺码图只按比例放大，不重新生成测量内容。

13 项独立测试通过。子任务未创建远程商品或部署。
