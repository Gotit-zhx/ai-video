# Shopee / TikTok Shop 官方授权与商品发布基线

> 核验日期：2026-09-04（Asia/Shanghai）
>
> 适用范围：赫逸智能化平台的官方店铺授权、Token 管理、图片上传与商品发布。
>
> 判定原则：官方 API Reference / Developer Guide 是唯一协议依据；GitHub 项目只用于借鉴结构和测试思路。

## 1. 结论与上线门槛

真实发布链路应保持默认关闭。只有本文件的 Sandbox 清单全部通过，并同时配置
`APP_PRODUCT_PUBLISHING_PROVIDER=OFFICIAL` 与
`APP_SHOP_LIVE_PUBLISHING_ENABLED=true`，才允许打开生产发布。

本次核验发现以下项目必须作为上线阻断项：

1. Shopee 新应用应使用 `/auth` 授权页；旧
   `/api/v2/shop/auth_partner` 已在最新授权指南中标为 legacy。
2. TikTok Shop 卖家授权页使用 `services*.tiktokshop.com/open/authorize`
   和 `service_id`；`auth.tiktok-shops.com` 只承载 Token 接口，不应再拼
   `/oauth/authorize`。
3. Shopee `init_tier_variation` 的当前结构是
   `standardise_tier_variation`，其子字段也已变化；不能只替换外层字段名后继续发送
   旧 `name / option_list / option` 结构。
4. Shopee `add_item.logistic_info` 在当前官方 Schema 中是必填字段，空数组不能当作
   “已完成物流配置”。
5. Shopee `get_shop_info.is_upgraded_cbsc=true` 或该字段缺失时，本地商品写接口必须
   fail closed；前者切换到 `global_product`，后者等待重新探测或人工确认。
6. TikTok Shop API 版本是逐接口版本，不是全局统一版本。创建商品当前仍为
   `202309`，但准备、搜索、编辑等接口已经分别出现 `202312`、`202502`、
   `202509` 版本，禁止把一个版本号盲目套到全部路径。

## 2. 通用安全与数据模型

- OAuth `state` 使用至少 256 bit CSPRNG，数据库只保存哈希，默认 TTL 10 分钟；
  回调时按平台、企业、发起用户匹配并原子消费，一次性使用。
- 回调 URL 必须是 Partner Console 中登记的 HTTPS 精确地址；`returnPath` 只允许本站
  相对路径，禁止开放重定向。
- 每个平台、每个授权主体、每个店铺独立保存 Token。TikTok 的 `shop_cipher` 也必须
  与店铺 ID 一一绑定，不能跨店复用。
- Access Token、Refresh Token 用独立 AES-256-GCM 密钥加密，企业 ID 与内部店铺 ID
  作为 AAD；不得出现在响应、日志、异常消息或发布审计原文中。
- Refresh Token 轮换必须加数据库行锁或分布式锁，并以版本号/CAS 原子保存新旧
  Token；失败后重新读取，不能并发重复消费旧 Refresh Token。
- 每次官方写请求保存平台 `request_id`、内部幂等键、目标店铺、接口路径、脱敏请求摘要、
  完整字段级错误和最终远端商品/SKU ID。
- 类目、属性、品牌、物流、仓库和限制均以目标店铺实时返回为准。缓存键至少包含
  `platform + region + shop + category + apiVersion`，不能跨站点共用 ID。

## 3. Shopee 官方授权

### 3.1 当前授权入口

新授权链接不需要旧式 `timestamp/sign`：

```text
全球       https://open.shopee.com/auth
中国大陆   https://open.shopee.cn/auth
巴西       https://open.shopee.com.br/auth
全球沙箱   https://open.sandbox.test-stable.shopee.com/auth
```

卖家授权示例：

```text
https://open.shopee.com/auth
  ?partner_id={partner_id}
  &auth_type=seller
  &redirect_uri={url_encoded_callback}
  &response_type=code
  &state={single_use_state}
```

参数说明：

- `partner_id`、`auth_type`、`redirect_uri`、`response_type=code` 必填。
- `auth_type` 当前可为 `seller`、`supplier`、`user`；本平台店铺发布使用 `seller`。
- `state` 官方列为可选，但本平台安全规范强制要求。
- 店铺授权回调返回 `code + shop_id + state`；主账号授权返回
  `code + main_account_id + state`。
- `code` 一次性且约 10 分钟失效，应立即交换 Token。
- 授权最长 365 天，卖家可选择更短期限；应记录官方授权到期时间。

旧链接仅兼容存量，不用于新接入：

```text
GET /api/v2/shop/auth_partner
  ?partner_id=...
  &timestamp=...
  &sign=...
  &redirect=...
```

官方来源：[Authorization and Authentication](https://open.shopee.com/developer-guide/20)。

### 3.2 Token 接口

| 操作 | 方法与路径 | 公共签名 | Body |
|---|---|---|---|
| Code 换 Token | `POST /api/v2/auth/token/get` | Public | `code, partner_id`，并且只传 `shop_id` 或 `main_account_id` 中适用的一项 |
| 刷新 Token | `POST /api/v2/auth/access_token/get` | Public | `refresh_token, partner_id`，并且只传 `shop_id/merchant_id/supplier_id/user_id/principal_id` 中适用的一项 |

Shopee Access Token 默认约 4 小时，Refresh Token 约 30 天并轮换；刷新后旧 Access
Token 可能有约 5 分钟重叠窗口。有效期以每次响应为准，不用本地常量覆盖响应值。

官方来源：

- [Get Access Token](https://open.shopee.com/documents/v2/v2.public.get_access_token?module=104&type=1)
- [Refresh Access Token](https://open.shopee.com/documents/v2/v2.public.refresh_access_token?module=104&type=1)

### 3.3 API 签名

签名串使用 UTF-8，`partner_key` 为 HMAC-SHA256 密钥，输出小写 hex：

```text
Public   = partner_id + api_path + timestamp
Shop     = partner_id + api_path + timestamp + access_token + shop_id
Merchant = partner_id + api_path + timestamp + access_token + merchant_id
```

`api_path` 必须是 `/api/v2/...`，不包含 host、query 或 body；`timestamp` 是 Unix 秒且
五分钟内有效。POST 的公共参数放 query，业务参数放 JSON 或 multipart body。

官方来源：[API calls](https://open.shopee.com/developer-guide/16)。

## 4. Shopee 最新本地商品发布链路

### 4.1 准备与路由

| 顺序 | 方法与路径 | 签名 | 必须检查的结果 |
|---|---|---|---|
| 1 | `GET /api/v2/shop/get_shop_info` | Shop | `status=NORMAL`、`region`、`is_cb`、`is_upgraded_cbsc`、`merchant_id` |
| 2 | `GET /api/v2/product/get_category` | Shop | 只能选择 `has_children=false` 的叶子类目 |
| 3 | `GET /api/v2/product/get_attribute_tree` | Shop | 按目标类目读取当前属性；一次最多 20 个类目 ID |
| 4 | `GET /api/v2/product/get_brand_list` | Shop | 使用 `status=1` 的可用品牌；无品牌时按当前类目规则使用 `brand_id=0` |
| 5 | `GET /api/v2/product/get_item_limit` | Shop | 标题、描述、价格、库存、图片、重量、尺寸、DTS、GTIN、尺码表等限制 |
| 6 | `GET /api/v2/logistics/get_channel_list` | Shop | 只使用 `enabled=true && mask_channel_id=0` 的渠道，并按 `fee_type` 补完整参数 |

旧 `v2.product.get_attributes`、`v2.product.get_dts_limit`、
`v2.product.support_size_chart` 已不在当前目录；分别改用
`get_attribute_tree` 和 `get_item_limit`，不能因社区 SDK 仍有方法就继续调用。

官方来源：[Product creation preparation](https://open.shopee.com/developer-guide/209)。

### 4.2 商品图片

商品图片使用：

```text
POST /api/v2/media_space/upload_image
Content-Type: multipart/form-data

image=<binary file>       # 必填
scene=normal|desc         # 可选，默认 normal
ratio=1:1|3:4             # 可选；3:4 仅白名单
```

限制为 JPG/JPEG/PNG、单张最大 10 MB、一次少于 9 张。商品主图应使用
`scene=normal`，并把响应 `image_id` 写入商品请求。

不要使用 `/api/v2/media/upload_image` 替代商品图片接口；后者当前属于 Returns 场景
（`business=2`）。

当前官方 API Reference 把 `media_space.upload_image` 标成 **Public**，但其错误说明及
部分社区实现仍按 Shop Token/Shop 签名调用。此处是官方资料内部不一致项：代码必须支持
`PUBLIC` 与 `SHOP` 两种签名范围配置，正式环境的值必须由对应 App 在 Sandbox 的 A/B
结果决定，不能凭社区示例猜测。

官方来源：[Upload Image](https://open.shopee.com/documents/v2/v2.media_space.upload_image?module=91&type=1)。

### 4.3 创建商品

```text
POST /api/v2/product/add_item
```

当前 Schema 的无条件必填顶层字段：

```text
original_price
description
weight
item_name
logistic_info
category_id
image.image_id_list
```

条件必填字段包括当前类目的 mandatory attributes、品牌、尺寸、物流费用、预售 DTS、
GTIN、认证与税务信息等；`condition` 从 2026-09-01 起对 BR 必填。

- 首次创建建议 `item_status=UNLIST`，后续回读校验成功再上架。
- 顶层 `seller_stock` 在当前 `add_item` Schema 中是可选字段。无规格商品可按 Schema
  省略，但产品层应要求运营明确填写库存或采用明确、可审计的默认值。
- 准备后续规格初始化时，官方没有要求必须省略顶层 `seller_stock`；无论是否传入，最终
  每个规格的库存以 `init_tier_variation.model[].seller_stock` 为准。
- `logistic_info` 是必填而非可选。若没有至少一个经店铺接口确认可用且字段完整的渠道，
  必须在调用 `add_item` 前阻断。

官方来源：[Add Item](https://open.shopee.com/documents/v2/v2.product.add_item?module=89&type=1)。

### 4.4 初始化规格

`add_item` 成功后至少等待 5 秒，再调用：

```text
POST /api/v2/product/init_tier_variation
```

当前标准化请求结构如下：

```json
{
  "item_id": 43464493923,
  "standardise_tier_variation": [
    {
      "variation_id": 0,
      "variation_group_id": 0,
      "variation_name": "Color",
      "variation_option_list": [
        {
          "variation_option_id": 0,
          "variation_option_name": "Blue",
          "image_id": "uploaded-image-id"
        }
      ]
    }
  ],
  "model": [
    {
      "tier_index": [0],
      "model_sku": "SKU-BLUE",
      "original_price": 99.9,
      "seller_stock": [{ "stock": 10 }]
    }
  ]
}
```

字段规则：

- 最多两层规格。
- `variation_id`、`variation_option_list`、`model`、`tier_index`、
  `original_price`、`model[].seller_stock` 为当前 Schema 的必填项。
- `variation_group_id`、`variation_name`、option 内的
  `variation_option_id/variation_option_name/image_id` 单独标记为可选；自定义规格的官方
  请求样例使用 ID `0` 并同时传名称，标准化规格应使用目标类目返回的真实 ID。
- `location_id` 在无仓店可省略；多仓店必须使用
  `v2.shop.get_warehouse_detail` 返回的合法 location ID。
- 旧 `tier_variation` 于 2025-09-12 标为 deprecated；旧 `normal_stock` 于
  2022-10-31 下线。禁止发送旧子结构
  `name / option_list / option / image.image_id`。

当规格组合超过 50 个时，不要把全部 model 一次传给初始化接口。推荐的完整顺序是：

1. 在本地生成完整规格笛卡尔积，先校验 `tier_index` 唯一、价格和库存完整。
2. `init_tier_variation` 只携带前 `min(50, 总数)` 个 model 和完整
   `standardise_tier_variation`。
3. 剩余 model 按每批最多 50 个调用 `add_model`；任一批失败即保持商品 `UNLIST`。
4. 所有批次成功后调用一次完整回读（允许短间隔有限重试）：

```text
GET /api/v2/product/get_model_list?item_id=...
```

该接口请求只有 `item_id`，响应直接返回完整 `response.model[]`，当前官方 Schema 没有
`offset/page_size/cursor/has_next`，因此不分页。必须确认回读数量等于本地期望数量、
`model_id` 与 `tier_index` 均唯一，再保存 `tier_index -> model_id` 映射并校验 SKU、
价格和库存。回读不完整时不得上架。

如需向已有规格结构增加组合，可调用：

```text
POST /api/v2/product/add_model
```

其请求为 `item_id + model_list`，每批 `model_list` 限制 `[1,50]`。每项必填
`tier_index`、`original_price`、`seller_stock[{stock,location_id?}]`；可选
`model_sku`（不超过 100 字符）、GTIN、重量、尺寸和预售信息。

### 4.5 校验、价格、库存与上架

| 操作 | 方法与路径 | 要点 |
|---|---|---|
| 回读基础资料 | `GET /api/v2/product/get_item_base_info` | 校验状态、类目、图片与必填字段 |
| 回读规格 | `GET /api/v2/product/get_model_list` | 保存 `tier_index -> model_id` |
| 更新资料 | `POST /api/v2/product/update_item` | 不代替价格/库存专用接口 |
| 更新价格 | `POST /api/v2/product/update_price` | `price_list[{model_id?,original_price}]` |
| 更新库存 | `POST /api/v2/product/update_stock` | `stock_list[{model_id?,seller_stock:[...]}]`；一次只更新一个 item |
| 上下架 | `POST /api/v2/product/unlist_item` | `item_list[{item_id,unlist}]`；`false` 上架，`true` 下架 |

推荐顺序是
`UNLIST 创建 -> 等待至少 5 秒 -> init 前 50 个 -> add_model 每批最多 50 个补余量 -> 完整回读 -> 上架`。

官方来源：[Stock & Price Management](https://open.shopee.com/developer-guide/223)。

## 5. TikTok Shop 官方授权

### 5.1 卖家授权链接

最新 Seller authorization 使用 Partner Center 中应用的 `service_id`：

```text
美国   https://services.us.tiktokshop.com/open/authorize?service_id={service_id}&state={state}
其他   https://services.tiktokshop.com/open/authorize?service_id={service_id}&state={state}
```

卖家同意后，Partner Center 登记的 Redirect URL 收到 `code` 与 `state`。拒绝授权时
可能收到 `code=null&error=auth_denied`。

- `code` 作为 `auth_code` 交换 Token；30 分钟失效且只能使用一次。
- `state` 虽为推荐项，本平台强制使用并原子消费。
- 开发阶段线上卖家不能授权未发布应用；使用 Partner Center 的 Development Shop 和
 测试 Access Token。上线前还必须以已审核应用完成一次真实授权回调验证。
- 订阅 Upcoming authorization expiration webhook（到期前 30 天触发）与 Seller
  deauthorization webhook；收到解授权后立即停用店铺并清除/吊销本地凭证。

`https://auth.tiktok-shops.com` 是 Token Server，不是卖家授权页。

官方来源：

- [Authorization overview](https://partner.tiktokshop.com/docv2/page/authorization-overview-202407)
- [Seller authorization guide](https://partner.tiktokshop.com/docv2/page/seller-authorization-guide)

### 5.2 Token 与店铺发现

```text
GET https://auth.tiktok-shops.com/api/v2/token/get
  ?app_key=...
  &app_secret=...
  &auth_code=...
  &grant_type=authorized_code
```

`authorized_code` 是 TikTok Shop 官方的准确枚举值，不要“修正”为 OAuth 常见的
`authorization_code`。

刷新接口：

```text
GET https://auth.tiktok-shops.com/api/v2/token/refresh
  ?app_key=...
  &app_secret=...
  &refresh_token=...
  &grant_type=refresh_token
```

Access Token 默认约 7 天；实际到期以响应 `access_token_expire_in` 为准。Token 请求
把 `app_secret` 放在 HTTPS query 中，因此 HTTP 客户端、网关、APM 和 access log 必须
对完整 query 脱敏。

Seller Token 的 `user_type=0`。取得 Token 后调用：

```text
GET https://open-api.tiktokglobalshop.com/authorization/202309/shops
Header: x-tts-access-token: {access_token}
Query: app_key, timestamp, sign
```

保存每个返回项的 `id`、`cipher`、`code`、`name`、`region`、`seller_type`。
产品写接口需要对应店铺的 `shop_cipher`；跨境店使用错误 cipher 会得到错误结果，而不一定
只表现为清晰的权限错误。

官方来源：[Call Get Authorized Shops](https://partner.tiktokshop.com/docv2/page/call-get-authorized-shops)。

### 5.3 TikTok 请求签名

API `202309+` 的 Access Token 放 `x-tts-access-token` Header，不参与签名。

1. 从 query 排除 `sign` 与 `access_token`，按参数名升序排列。
2. 拼接为 `key1value1key2value2...`。
3. 前置请求 path。
4. 非 multipart 请求追加**实际发送的原始 body 字节**；禁止签名后重新序列化 JSON。
5. 得到 `app_secret + pathAndParamsAndBody + app_secret`。
6. 使用 `app_secret` 为密钥计算 HMAC-SHA256，输出小写 hex。

multipart 请求不把 body 加入签名。时间戳为 10 位 Unix 秒并且五分钟内有效。

官方来源：[Sign your API request](https://partner.tiktokshop.com/docv2/page/sign-your-api-request)。

## 6. TikTok Shop 最新商品发布链路

TikTok 的版本号按接口固定选择：

| 顺序/用途 | 方法与路径 | 关键点 |
|---|---|---|
| 店铺准备检查 | `GET /product/202312/prerequisites` | 先解决 onboarding、仓库、税务或发布资格问题 |
| 获取类目 | `GET /product/202309/categories` | 使用目标店铺可用叶子类目 |
| 类目规则 | `GET /product/202309/categories/{category_id}/rules` | 包裹、COD、尺码表、资质等规则 |
| 类目属性 | `GET /product/202309/categories/{category_id}/attributes` | 补齐所有 `is_required` 属性与销售属性 ID |
| 品牌 | `GET /product/202309/brands` | 只能使用该店铺/类目可见并已授权的品牌 |
| 仓库 | `GET /logistics/202309/warehouses` | SKU 库存必须引用本店有效 warehouse ID |
| 上传图片 | `POST /product/202309/images/upload` | multipart；返回 TikTok Shop `uri` |
| 预检 | `POST /product/202309/products/listing_check` | 使用与正式创建完全相同的字段，先修完所有 issues |
| 创建并提交审核 | `POST /product/202309/products` | `save_mode=LISTING`，保存 `product_id`、SKU IDs、`request_id` |
| 回读 | `GET /product/202309/products/{product_id}` | 校验远端实际字段与状态 |
| 搜索 | `POST /product/202502/products/search` | 不要继续把搜索路径固定为旧版本 |
| 完整编辑 | `PUT /product/202509/products/{product_id}` | 使用当前 API Reference 的 202509 编辑结构 |
| 部分编辑 | `POST /product/202509/products/{product_id}/partial_edit` | 只修改明确字段；以对应页面 Schema 为准 |
| 更新价格 | `POST /product/202309/products/{product_id}/prices/update` | 使用远端 SKU ID |
| 更新库存 | `POST /product/202309/products/{product_id}/inventory/update` | 提交每个 SKU 的 warehouse inventory |
| 恢复已停用商品 | `POST /product/202309/products/activate` | 仅用于 Seller/Platform deactivated，不是“发布草稿”接口 |

`Create Product` 当前必填顶层字段至少包括 `description`、`category_id`、
`main_images`、`skus`、`title`；包裹重量默认必填（虚拟商品类目例外），其他字段由地区与
类目规则决定。每个 SKU 应有价格、币种和目标店铺的
`inventory[{warehouse_id,...}]`；`quantity` 当前为可选，发送时合法范围是 1–99,999。
运营填写 0 库存时保留 `warehouse_id` 并省略 `quantity`，不能向 Create Product 发送
`quantity=0`。多 SKU 还必须使用目标类目返回的销售属性 ID。

US 与 SEA 店铺调用 Create Product 时必须传 `category_version=v2`；其他地区按官方返回
选择，不能只由来源商品地区推断。商品内容语言必须符合目标市场语言。

`save_mode` 可为 `AS_DRAFT` 或 `LISTING`。平台采用
`listing_check -> LISTING` 作为可自动化发布路径。不要假设创建为 `AS_DRAFT` 后可直接用
`products/activate` 发布，因为 Activate 官方定义仅适用于已处于 Seller/Platform
deactivated 状态的商品。

商品创建后会进入 TikTok 审核；API 成功只表示已接收，不表示已上架。订阅 Product
status change webhook，并以回读状态作为最终结果。

### 6.1 TikTok 图片上传

```text
POST /product/202309/images/upload
Header: x-tts-access-token
Content-Type: multipart/form-data

data=<binary file>          # 必填
use_case=MAIN_IMAGE         # 按用途选择
```

当前支持 JPG/JPEG/PNG/WEBP/HEIC/BMP，最大 10 MB，尺寸范围 100x100 到
20000x20000；`MAIN_IMAGE` 要求 300x300 到 4000x4000。商品中必须使用响应的
TikTok Shop `uri`，不能直接写外站图片 URL。

`use_case` 可为 `MAIN_IMAGE`、`ATTRIBUTE_IMAGE`、`DESCRIPTION_IMAGE`、
`CERTIFICATION_IMAGE`、`SIZE_CHART_IMAGE`、`CUSTOMIZATION_IMAGE`。主图和规格图超出
3:4 到 4:3 的宽高比会被自动转为 1:1。

官方来源：

- [Upload Product Image](https://partner.tiktokshop.com/docv2/page/upload-product-image-202309)
- [Check Product Listing](https://partner.tiktokshop.com/docv2/page/check-product-listing-202309)
- [Create Product](https://partner.tiktokshop.com/docv2/page/create-product-202309)
- [Activate Product](https://partner.tiktokshop.com/docv2/page/activate-product-202309)

### 6.2 TikTok 跨境/Global Product

Create Product 官方说明：已经迁移到 local replication listing 的 Global Seller 可以调用
本地 `Create Product`；尚未迁移的 Global Seller 应继续使用 `Create Global Product`，
再以 `Publish Global Product` 转换到目标本地店铺。

因此 `user_type=4/5` 或 `seller_type=CROSS_BORDER` 只能说明跨境身份，不能独立证明
本地发布路径可用。若系统未保存并验证该卖家的 listing mode，应停止自动发布并提示选择
Global Product 流程或完成 local replication 迁移。

官方来源：

- [Create Global Product](https://partner.tiktokshop.com/docv2/page/create-global-product-202309)
- [Publish Global Product](https://partner.tiktokshop.com/docv2/page/publish-global-product-202309)

## 7. Shopee 跨境升级店 `global_product` 阻断策略

`GET /api/v2/shop/get_shop_info` 当前官方字段含义：

- `is_cb`：是否跨境店。
- `is_upgraded_cbsc`：商家是否已升级 CBSC，包含 CNSC 与 KRSC。
- `merchant_id`：店铺所属 Merchant；无归属时可为 null。

本地 Product 发布前必须执行如下决策：

```text
get_shop_info 失败
  -> 阻断，不调用任何商品写接口

is_upgraded_cbsc 缺失/null
  -> 阻断，重新拉取或人工确认；不得按 false 处理

is_upgraded_cbsc == true
  -> 标记 SHOPEE_GLOBAL_PRODUCT_REQUIRED
  -> 禁止 add_item/init_tier_variation/update_stock 等本地写链路
  -> 仅在 global_product 功能开关、字段映射与 Sandbox 用例通过后进入全球商品链路

is_upgraded_cbsc == false
  -> 可进入本地 Product 流；is_cb 仅作为跨境上下文继续记录
```

阻断检查必须发生在下载/上传图片及 `add_item` 之前，避免产生孤儿媒体或半成品商品。
店铺路由结果需短 TTL 缓存，但每次真实发布前仍应重新确认；一旦从 false 变成 true，立即
熔断该店所有排队中的本地写任务。

上线 global_product 前至少需要独立完成：全球类目/属性映射、Global Item/SKU ID 与本地
Item/Model ID 映射、目标店发布、价格与库存同步、失败补偿、幂等和回读校验。没有这些能力
时应明确返回可操作错误，不能悄悄回退本地接口。

## 8. GitHub 项目对比

以下数据取自 2026-09-04 GitHub API；活跃度不等于协议正确性。

| 项目 | 快照 | 可借鉴 | 不能直接照搬的风险 |
|---|---|---|---|
| [congminh1254/shopee-sdk](https://github.com/congminh1254/shopee-sdk) | TS；MIT；193 stars / 70 forks；v2.3.0 于 2026-09-01；2026-09-04 仍推送 | 新 `/auth` 地域路由、签名封装、Manager 分层、Schema 自动同步、测试 | 默认单 Token/文件明文存储；Refresh 无可靠跨节点锁/CAS；把宽泛 `error_auth` 当 Token 失效；身份签名与 `media_space` 认证范围仍需审计 |
| [EcomPHP/shopee-php](https://github.com/EcomPHP/shopee-php) | PHP；Apache-2.0；95 stars / 19 forks；v1.7.0 于 2026-07-14；2026-08-28 推送 | Guzzle middleware、Resource 分层、multipart 写法 | 仍使用 legacy `auth_partner`、已不存在的 `getAttributes`、旧 `tier_variation`；Token 主体和 Merchant 签名覆盖不足 |
| [EcomPHP/tiktokshop-php](https://github.com/EcomPHP/tiktokshop-php) | PHP；Apache-2.0；213 stars / 71 forks；v2.6.0 发布于 2024-11-26；2026-07-10 推送 | 202309+ 路径、签名/Token/Resource 分层、Webhook 示例 | Release 较旧；最新 Seller authorization 域名及 2025/2026 混合版本端点须逐项核对；无权替代官方 Schema |
| [tudinhacoustic/tiktok-shop](https://github.com/tudinhacoustic/tiktok-shop) | TS；5 stars / 7 forks；2026-07-27 推送；无正式 Release | 小型签名与 Token 工具、类型示例 | 非完整商品 SDK；示例含 legacy query token，生产使用前必须核对 202309+ Header 规则 |
| [tiktok/ttspc-sample-shared-types](https://github.com/tiktok/ttspc-sample-shared-types) | TikTok 官方组织；MIT；0 stars / 1 fork；2 commits；无 Release | 官方教学架构、响应 envelope、授权/类目基本类型 | 示例覆盖很窄且 README 含旧 `/product/202312/products/create` 路径，只能教学，不能作为最新接口清单 |

TikTok Shop 正式 SDK 应从 Partner Center 根据已生效应用生成/下载：
[SDK overview](https://partner.tiktokshop.com/docv2/page/tts-api-sdk-overview)。官方没有一个可
直接替代在线 API Reference 的统一 GitHub SDK。

推荐做法是保留本项目的薄适配层，只借鉴上述项目的签名测试、传输层和 Schema 生成思路；
每个正式接口仍以官方页面和 Sandbox Contract Test 为准。

## 9. 配置项

### 9.1 当前项目已有配置

```dotenv
# 通用
APP_SHOP_AUTH_STATE_TTL=10m
APP_SHOP_CREDENTIAL_KEY=<独立的至少32位随机密钥>
APP_SHOP_AUTH_CALLBACK_BASE_URL=https://api.example.com
APP_SHOP_AUTH_FRONTEND_BASE_URL=https://app.example.com
APP_SHOP_TOKEN_REFRESH_SKEW=5m
APP_PRODUCT_PUBLISHING_PROVIDER=DRY_RUN
APP_SHOP_LIVE_PUBLISHING_ENABLED=false

# Shopee
APP_SHOPEE_ENABLED=false
APP_SHOPEE_PARTNER_ID=
APP_SHOPEE_PARTNER_KEY=
APP_SHOPEE_AUTH_BASE_URL=https://open.shopee.com
APP_SHOPEE_API_BASE_URL=https://partner.shopeemobile.com
APP_SHOPEE_IMAGE_UPLOAD_SIGN_SCOPE=SHOP

# TikTok Shop
APP_TIKTOK_SHOP_ENABLED=false
APP_TIKTOK_SHOP_SERVICE_ID=
APP_TIKTOK_SHOP_APP_KEY=
APP_TIKTOK_SHOP_APP_SECRET=
APP_TIKTOK_SHOP_AUTHORIZATION_BASE_URL=https://services.tiktokshop.com
APP_TIKTOK_SHOP_TOKEN_BASE_URL=https://auth.tiktok-shops.com
APP_TIKTOK_SHOP_API_BASE_URL=https://open-api.tiktokglobalshop.com
```

`APP_SHOPEE_IMAGE_UPLOAD_SIGN_SCOPE` 必须在 Sandbox 验证后明确设置为 `PUBLIC` 或
`SHOP`；不得依赖默认值进入生产。

### 9.2 全球商品链路的保留边界

本项目当前仅实现东南亚本地店铺 Product 链路。若以后接入全球商品，必须新增独立且默认
关闭的配置，不能复用本地商品开关：

```dotenv
# 未完成全球商品适配前始终关闭
APP_SHOPEE_GLOBAL_PRODUCT_ENABLED=false
APP_TIKTOK_SHOP_GLOBAL_PRODUCT_ENABLED=false
```

Shopee 授权 URL 使用独立 `APP_SHOPEE_AUTH_BASE_URL`，不复用 API Host。TikTok 授权页、
Token Host 与 Open API Host 同样独立配置。TikTok API 版本按接口写入固定路径；项目不提供
会把单一版本错误套到全部接口的全局 `APP_TIKTOK_SHOP_API_VERSION`。

## 10. Sandbox / Development Shop 验证清单

任何一项未通过都保持 `DRY_RUN`。

### 10.1 通用授权与凭证

- [ ] Partner Console 中的应用、权限范围、回调 HTTPS 地址和 IP 白名单均已审核。
- [ ] `state` 随机、哈希存储、十分钟过期、回调原子消费；错误平台、过期、重放均失败。
- [ ] 用户拒绝授权时不交换 Token，并向前端返回安全且不含第三方原始秘密的错误。
- [ ] 多店授权不会把一个店铺的 Token、shop ID、merchant ID 或 shop cipher 写到另一店。
- [ ] 并发触发至少 20 次过期 Token 请求，只发生一次 Refresh，其他请求读取轮换结果。
- [ ] Token 和完整 query 不出现在应用日志、反向代理日志、APM、审计响应或异常栈。
- [ ] 授权到期、解授权、店铺冻结/封禁后，排队中的发布任务在写请求前被拦截。

### 10.2 Shopee Sandbox

Sandbox API Host：`https://partner.test-stable.shopeemobile.com`。

- [ ] 新 `/auth` 链接的 region、参数名、URL 编码和回调字段均与官方一致。
- [ ] Public、Shop、Merchant 三类签名各有固定向量测试；时间偏差超过五分钟会失败。
- [ ] Token 获取、并发刷新、Refresh Token 轮换、旧 Access Token 重叠窗口均已验证。
- [ ] `media_space.upload_image` 分别用 Public 与 Shop 签名实测，记录 App、环境、日期、
      HTTP 状态、Shopee error/message/request_id，并锁定生产配置。
- [ ] 图片成功、超 10 MB、错误格式、`scene=normal/desc`、非白名单 `ratio=3:4` 均验证。
- [ ] 类目、属性、品牌、`get_item_limit` 和物流均按目标店铺获取；非叶子类目被本地阻断。
- [ ] `logistic_info` 缺失、空数组、无效 channel、各种 `fee_type` 分支均有负向用例。
- [ ] 无规格商品分别验证显式 `seller_stock` 与按 Schema 省略的响应，并固化业务规则。
- [ ] 规格商品使用完整 `standardise_tier_variation` 新子字段；旧字段请求必须测试失败或被
      本地拦截。
- [ ] `add_item` 后不足 5 秒不调用初始化；五秒后成功，并以不分页的
      `get_model_list` 保存完整 `tier_index -> model_id`。
- [ ] `add_model` 的 1、50、51 条边界已验证；超过 50 条在本地分批或阻断。
- [ ] `UNLIST -> 回读 -> unlist=false` 成功；中途失败保持未上架且可人工恢复。
- [ ] `is_upgraded_cbsc=true` 和字段缺失两种情况都在任何媒体/商品写请求前 fail closed。
- [ ] 所有响应 `request_id` 与远端 item/model ID 可从发布审计追溯。

### 10.3 TikTok Development Shop

- [ ] Development Shop 测试 Token 能成功调用 `GET /authorization/202309/shops`。
- [ ] 已审核应用用 `services*.tiktokshop.com` + `service_id` 完成一次真实授权回调；旧
      `/oauth/authorize` 不再出现。
- [ ] `grant_type=authorized_code` 成功，误传 `authorization_code` 明确失败。
- [ ] Token 的绝对/相对到期值解析正确；刷新并发、解授权 Webhook 与到期提醒已验证。
- [ ] 每店保存并使用正确 `shop_cipher`；交换两个店铺 cipher 的负向用例失败且不落脏数据。
- [ ] 签名固定向量覆盖 GET、JSON POST、multipart；JSON 使用发送时的同一字节数组。
- [ ] 图片格式、尺寸、10 MB 边界、各 `use_case` 及返回 URI 持久化均验证。
- [ ] `prerequisites`、叶子类目、category rules、required attributes、品牌与仓库均通过。
- [ ] US/SEA 发送 `category_version=v2`；错误版本、错误语言、外站图片 URL 被预检拦截。
- [ ] 单 SKU、多 SKU、不同仓库库存、无效 warehouse ID，以及 0 库存省略 `quantity`、
      1/99,999 边界和 100,000 被拒绝均有用例。
- [ ] `listing_check` 无阻断问题后才发送 `save_mode=LISTING`；相同幂等键不会产生重复商品。
- [ ] 创建成功后保存 product/SKU IDs 与 `request_id`，通过 Get Product 和 Product status
      change webhook 确认审核最终状态。
- [ ] `AS_DRAFT` 不会误走 Activate；Activate 只用于官方允许的 deactivated 状态。
- [ ] Global Seller 未确认 local replication listing mode 时，自动发布 fail closed。

## 11. 当前实现状态与剩余验证

本节是 2026-09-04 对测试分支的静态实现快照；所有“已落实”都仍须用对应平台的
Sandbox / Development Shop 做 Contract Test，不能解释为真实店铺联调已经通过。

已经落实、仍需 Contract Test 证明的 Shopee 项目：

- 授权链接已切换到独立 Auth Host 的 `/auth`，并发送 `partner_id`、
  `auth_type=seller`、`redirect_uri`、`response_type=code` 与 `state`。
- 规格请求已使用完整的
  `variation_id/variation_group_id/variation_name/variation_option_list` 结构，option 使用
  `variation_option_id/variation_option_name/image_id`；第 4.4 节示例中每个 option 只出现
  一次 `variation_option_id`。
- 多规格 `add_item` 已移除顶层 `seller_stock`，并强制非空 `logistic_info`；随后
  `init_tier_variation` 的每个 model 使用必填 `seller_stock`。
- 多规格提交已按 `init` 首批最多 50 个、`add_model` 后续每批最多 50 个执行，并在全部
  写入后用不分页的 `get_model_list` 完整回读；回读不足保持未上架。
- `is_upgraded_cbsc` 缺失或为 true 时，会在商品写链路前阻断本地 Product 发布。

Shopee 仍是明确生产阻断项或 Sandbox 决策项：

- `media_space.upload_image` 的 Public/Shop 签名范围必须以真实 Shopee App A/B 结果锁定。
- 当前发布审计尚未逐次保存图片上传、`add_item`、规格初始化/追加与上架响应的全部
  `request_id`；上线前必须补齐每个官方写请求的脱敏摘要和关联 ID。
- 新 Shopee `/auth`、Token 轮换、标准化规格、50 条批次边界、完整回读与上架恢复必须跑完
  第 10.2 节，不以单元测试替代。

已经落实、仍需 Contract Test 证明的 TikTok Shop 项目：

- TikTok 卖家授权已切换到 `services*.tiktokshop.com/open/authorize?service_id=...`，
  Token Host 与授权 Host 分离；仍须用已审核应用完成 Development Shop 与真实回调验证。
- TikTok 图片已按 `MAIN_IMAGE` 与 `ATTRIBUTE_IMAGE` 用途分别上传，规格图 URI 注入首层
  `sales_attributes[].sku_img`；`x-tts-access-token` Header、202309 Create Product 与签名算法
  总体方向符合当前文档。
- TikTok Create 前已调用 202309 `listing_check`；除该接口不支持的 Create-only 字段外，
  使用同一个业务 payload，任何失败原因、阻断警告或缺失结果均停止创建。
- TikTok Create 后会有限重试 Get Product，并核对 product ID、SKU 数量、
  `seller_sku -> sku_id`、销售规格和规格图；创建响应出现 warning 或最终无法回读时记为失败，
  并明确提示远端商品可能已经存在、禁止盲目重试。
- TikTok 仅对 `seller_type=LOCAL` 放行本地 Create Product；`CROSS_BORDER` 或未知值在上传图片前
  即阻断，直到系统能持久化并验证 local-replication listing 资格。
- TikTok 发布库存使用人工输入，不依赖源商品库存；0 时省略 `quantity`，1–99,999 才发送，
  小数、负数、100,000 及未知 SKU Key 均在服务端拒绝。

仍是明确生产阻断项或 Development Shop 决策项：

- TikTok `prerequisites`、目标类目的动态必填规则与权限必须在发布前由 Development Shop
  验证；当前前端要求人工填写官方类目、属性、仓库与包裹字段，不会伪装成已自动补齐。
- Upcoming authorization expiration 与 Seller deauthorization Webhook 尚未接入；当前只能人工
  解绑本地凭证，平台解授权后不能依赖事件自动停用店铺。
- 当前发布审计已保存预检、创建和回读的 `request_id`，但尚未逐次保存图片上传响应的
  `request_id`；上线前必须补齐每个官方写请求的脱敏摘要和关联 ID。
- Product status change Webhook 尚未接入；当前只能确认 Create 已受理并完成即时回读，不能把
  `PENDING` 当作审核通过或买家端已经上架。
- 第 10.3 节中的 OAuth、签名、图片、预检、幂等、库存边界和跨境路由仍需真实凭证验证。

代码静态审查通过不能替代第三方平台 Sandbox/Development Shop Contract Test。
