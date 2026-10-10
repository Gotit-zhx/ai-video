# 完整商品采集实测（0.2.1）

源商品：Shopee PH 1500237133 / 47962221805，COD SUKISILK Zootopia Judy Hopps Embroidery Loose Fit Women Casual Short Sleeve T-Shirt。

## 根因与修复

旧记录仅1张图、0SKU，AUTHORITATIVE_SKU_DATA_UNAVAILABLE。用户已登录Chrome内直接get_pc补读403，而当前页面React商品组件保留完整item。扩展现在从商品h1附近有限父级的memoizedProps.item恢复数据，严格核对商品和店铺ID；支持初始页面缓存，保留网络数据优先级。只补同SKU缺失的页面库存，不覆盖网络价格或明确库存0；补值记录SHOPEE_RENDERED_PDP来源。库存是商品页面暴露值，不等同卖家仓库实时库存。

图片采集/传输/保存统一120张，超出明确标记；平台发布限制保持独立。视频下载部分失败允许重试。后续不完整SKU重采不会覆盖已有完整记录。

## 已登录Chrome实测

已更新用户正在加载的output/collector/heyi-collector到0.2.1，通过真实采集按钮更新原记录8c409d76-7325-4b9a-8f6c-f17e6d3c3229。

- 96/96唯一SKU（16颜色×6尺码），价格、图片、页面库存无缺失。
- 9轮播图与16颜色图存在重合，去重16张全部保存。
- 1个MP4已归档1,610,139字节；页面显示视频已保存1/1。
- 详情说明1140字符；源富文本段落均为文字，没有独立详情图片。
- PHP159–269，销量34，评分5，评价9；类别、属性已保存。
- Chrome采集按钮最终已采集。

核对数据：output/collector/product-47962221805-verification.json。

## 验证

扩展check.mjs全部通过（解析、观察器隔离/库存来源、96SKU、120图、登录与视频失败重试等）；Java图像规划3项、采集服务45项通过。后端已构建部署；新版发行包output/collector/heyi-collector-0.2.1.zip。
