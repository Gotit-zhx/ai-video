## 0.2.7 原商品尺码表与批量上传

采集 Shopee 商品独立的 Size Chart 弹窗及结构化尺码图片，保留为 `SIZE_CHART` 原始资产。图片数量达到上限时优先保留尺码图；读取失败会提示不完整，避免显示“完整采集”后发布才报错。颜色、尺码、原价与库存仍以对应商品的真实 SKU 数据为准。

批量上传弹窗可选上传本批尺码图；未上传时使用每件商品自己的原尺码表。覆盖插件目录后在扩展程序页点击“重新加载”，再刷新商品页面，无需退出登录。

## 0.2.6 官网连接与强制升级

固定连接赫逸官网，无需填写平台地址。插件每次请求报告当前运行版本，服务端拒绝低于最低版本或无法识别版本的采集请求，并返回更新说明；已登录旧版同样受限制。更新后保留原登录，退出和撤销凭证始终可用。新版支持商品页和弹窗内的更新提示与下载入口。

## 0.2.5 TikTok 移动商品页 SKU 与内容

补齐移动商品页的 sku_sale_props、sale_prop_value_ids、SKU 内价格字段、desc_blocks、specifications 和尺码表图片。保存全部真实 SKU 的颜色、尺码、售价、原价、库存及对应图片，保留状态值 0；不会用当前选中规格替代全部 SKU。更新后重新加载扩展并刷新商品页。

## 0.2.4 TikTok 实页类目与详情图

支持商品组件旁的 category_info.recommended_categories 类目路径；从已匹配商品的富文本 description 中采集详情图，保留主图、SKU 图并排除评论与推荐内容。MY 商品 1730135047201523374 真实数据已验证 71 个 SKU 和 8 张详情图。

## 0.2.3 TikTok 类目与商品属性完整性

支持页尾 /国家/c/ 类目路径，包含翻译后的页面；补齐结构化类目和商品属性字段。更新的同商品、同 SKU 价格响应不再抹掉已获得的类目。类目未取得时明确提示可重试，不伪装为完整成功。不会从推荐商品或类目菜单猜测分类。

升级后在 chrome://extensions 重新加载“赫逸商品一键采集”，再刷新商品详情页。

## 0.2.2 视频资产去重

修复同一Shopee视频不同CDN/清晰度被保存两次的问题。按视频资产ID合并，独立视频保留，视频计数按去重结果计算。

## 0.2.1 完整商品采集

修复已登录Shopee页面因接口403而漏采SKU的问题；支持当前商品组件数据恢复、最多120张图片、视频失败重试。已实测商品47962221805完整96SKU、16图、1视频。重新加载扩展后刷新商品页生效。

# 0.2.0 登录与视频采集更新

本版不再要求用户填写绑定码。可直接输入赫逸平台手机号与密码；多公司账号可用“企业代码/手机号”。密码只发送给所选平台，不持久化。浏览器已有平台会话时优先复用会话；受 Cookie 策略限制时，通过同源已登录的平台标签页自动连接。插件退出后暂停自动连接，主动登录或打开平台后恢复。

视频：从商品播放器、Shopee 商品响应和视频元数据读取来源，下载 MP4/WebM 并归档至当前公司商品，单文件上限 64MiB，最多 5 条候选链接。商品页支持播放和下载原文件。HLS/DASH 分片流与 DRM 视频暂不支持；下载失败会显示保存数量和原因。不会尝试绕过商品站点登录/验证码。

开发模式用户在 chrome://extensions 对本扩展点“重新加载”，然后刷新商品页。发行包 output/collector/heyi-collector-0.2.0.zip，解压后加载其中 heyi-collector 文件夹。

以下为旧版协议和测试背景，手动绑定码操作已被上述流程取代。

# 赫逸商品一键采集（Manifest V3）

这是赫逸平台的 Chrome/Edge 浏览器扩展。只有先在插件中登录配对的用户才能发起采集。扩展从用户正在浏览的 Shopee、TikTok Shop 页面及该页面已经加载的第一方商品 JSON 中读取商品信息，并提交到赫逸平台形成商品采集记录。

## 第一版范围

- 支持 Shopee 新加坡、马来西亚、菲律宾、泰国、印度尼西亚、越南站。
- 支持 `shop.tiktok.com` 以及 `www.tiktok.com/shop/`、`/view/product/`、`/pdp/` 下的 TikTok Shop 页面。
- 商品详情页显示悬浮的“一键采集”。
- 商品列表中，能识别真实商品链接的商品卡片显示“一键采集”。点击后先跳转到该商品详情页，再自动读取完整详情并提交；绝不从卡片规格做笛卡尔积猜测。
- Shopee 详情页优先请求当前页面同源的 `/api/v4/pdp/get_pc`，失败时读取页面内嵌 bootstrap JSON。响应中的商品 ID、店铺 ID 必须与详情链接严格一致，才会按 `models + tier_variations + tier_index` 生成真实 SKU。
- TikTok Shop 优先读取 `__NEXT_DATA__`、`__UNIVERSAL_DATA_FOR_REHYDRATION__` 等 SSR/bootstrap JSON；必要时使用仅限当前商品、同源且 URL 含 product/detail/pdp/sku/price/promotion 的 JSON 响应观察器。观察器不读取或传递请求 Cookie、请求头、响应头或认证信息。
- 每个 SKU 保存平台 SKU/model ID、卖家 SKU（平台有返回时）、全部规格属性、售价、原价、SKU 图，以及页面能够明确提供的库存。SKU 自身图优先，其次为带图规格选项，最后明确继承商品主图；不会混入评价图、头像、Logo 或验证码图片。
- 同时提取页面可见的标题、现价、原价、货币、销量、评分、商品图、店铺、折扣和商品属性；DOM、JSON-LD 与 Open Graph/meta 只作为字段降级数据源。
- 遇到 Security Check、拼图验证码、Shopee `/verify/traffic/error` 或登录验证页时停止注入采集按钮，只提示先完成验证/登录后刷新。
- 只调用赫逸平台的配对、身份检查、退出和商品采集接口。

第一版**不调用 ReefAPI、TMAPI 或其他第三方商品 API，因此没有这类 API 费用**。若无法取得与当前 productId 匹配的真实 SKU 数据，或 SKU 数量、ID、规格属性、价格、图片任一必需项无法完整验证，`skuCompleteness.complete` 必须为 `false`；扩展会明确提示页面验证/规格未加载，不会凭空生成数据。库存是可选字段：页面能提供时原样采集并上传，无法取得时保留为空且不阻断 SKU 完整性。这里的 `complete` 只代表浏览器取得的数据结构与字段自洽，不是服务端对商城数据的独立证明；发布前仍必须由已登录用户人工核对。扩展不调用 AI 去水印接口，也不自动发布商品。去水印、字段整理、审核和“一键发布”都由平台后续流程执行。

## 安装

1. 打开 `chrome://extensions`（Edge 使用 `edge://extensions`）。
2. 开启“开发者模式”。
3. 点击“加载已解压的扩展程序”。
4. 选择本目录 `browser-extension/`。
5. 安装或更新后，刷新已经打开的 Shopee/TikTok Shop 页面。

扩展没有构建步骤和第三方前端依赖。检查全部 JavaScript 语法：

```bash
cd browser-extension
npm run check
```

## 登录与配对

扩展不接收或保存企业代码、手机号、平台密码。配对过程如下：

1. 在扩展窗口填写赫逸平台地址。生产地址必须为 HTTPS；仅 `localhost`、`127.0.0.1` 允许 HTTP 调试。
2. 点击“打开平台生成绑定码”，浏览器会打开 `${baseUrl}/#product-capture`。
3. 用户在平台完成正常登录，进入“商品采集/发布”页生成一次性绑定码。
4. 将绑定码粘贴到扩展。扩展用它换取独立、短期、仅限插件用途的 Bearer Token。
5. 配对后扩展显示当前用户、企业、权限和 Token 有效期。
6. “退出并撤销本设备”调用平台退出接口、清除本地 Token，并移除该平台域名的可选访问权限。

一次性绑定码短时有效、仅可使用一次，并绑定用户、企业与允许的权限范围；换取 Token 时会记录扩展 ID、版本和设备名称，便于后续审计与撤销。Token 到期或平台返回 `401` 时，扩展立即清除本地 Token，并用红色徽标及页面按钮提示重新登录。平台返回 `403` 时保留登录状态，明确提示当前账号缺少采集权限。

## 后端接口契约

平台网页在已有 Redis Session 登录态下生成绑定码：

```http
POST /api/v1/product-captures/extension/pairing-code
```

扩展端使用以下接口：

```http
POST /api/v1/extension-auth/exchange
Content-Type: application/json

{
  "code": "一次性绑定码",
  "deviceName": "Chrome · macOS",
  "extensionId": "Chrome 分配的扩展 ID",
  "extensionVersion": "0.1.0"
}
```

成功结果：

```json
{
  "success": true,
  "data": {
    "accessToken": "opaque-short-lived-token",
    "tokenType": "Bearer",
    "expiresAt": "2026-09-04T12:00:00Z",
    "user": {
      "userId": "...",
      "displayName": "...",
      "organizationId": "...",
      "organizationCode": "heyi",
      "organizationName": "赫逸智能化",
      "permissions": ["product:collect"]
    }
  }
}
```

身份与退出：

```http
GET  /api/v1/extension-auth/me
POST /api/v1/extension-auth/logout
Authorization: Bearer <token>
```

采集：

```http
POST /api/v1/product-captures
Authorization: Bearer <token>
Idempotency-Key: capture-<sha256(platform|shop|product)>
Content-Type: application/json
```

示例请求（缺失字段使用 `null` 或空数组，不进行猜测）：

```json
{
  "sourceUrl": "https://shopee.ph/example-i.123.456?x=y",
  "sourcePlatform": "SHOPEE",
  "title": "页面上的商品标题",
  "description": "页面公开描述",
  "categoryName": null,
  "brandName": null,
  "currency": "PHP",
  "priceMin": 199,
  "priceMax": 299,
  "sourceProductId": "456",
  "sourceShopId": "123",
  "skus": [],
  "images": [
    {
      "url": "https://example-cdn/image.webp",
      "originalUrl": "https://example-cdn/image.webp",
      "alt": "商品主图",
      "position": 0,
      "role": "MAIN"
    }
  ],
  "fieldSources": [
    { "field": "title", "source": "DOM", "path": "main h1", "confidence": 0.9 }
  ],
  "enrichmentProvider": null,
  "domData": {
    "pageType": "DETAIL",
    "pageUrl": "完整的当前页面 URL",
    "canonicalUrl": "页面 canonical URL",
    "priceText": "₱199 - ₱299",
    "originalPriceText": "₱399",
    "salesText": "1.2k sold",
    "ratingText": "4.8",
    "shopName": "店铺名称",
    "skuCompleteness": {
      "expectedCount": 96,
      "capturedCount": 96,
      "variantDimensionCount": 2,
      "complete": true,
      "source": "SHOPEE_PDP_API",
      "issues": []
    },
    "variantHints": [
      { "name": "页面可见规格", "values": ["Black", "White"] }
    ],
    "extractionStrategies": ["VISIBLE_DOM", "JSON_LD", "META"]
  }
}
```

平台响应可返回 `id` 或 `captureId`、`productId`、`status`、`completeness`、`missingFields` 和 `message`。当前扩展把成功请求显示为“已采集”；重复点击由按钮状态和 `Idempotency-Key` 双重防护。

## 权限与隐私

Manifest 没有申请 `<all_urls>`、`cookies`、`webRequest`、`debugger`、下载、剪贴板或浏览历史权限。由于 Shopee/TikTok 的最小响应观察器使用 Manifest V3 的 `world: "MAIN"`，最低要求 Chrome/Edge 111。

- Shopee/TikTok Shop 站点只匹配买家商品页面；Shopee Seller Centre 被显式排除，TikTok 也不匹配 Seller Center。除受限响应观察器外，采集逻辑均运行在隔离世界。
- Shopee PDP 的主动请求只访问当前页面的同源 `/api/v4/pdp/get_pc`；浏览器可按正常同源规则附带该站点会话，但扩展代码不读取 `document.cookie`、Cookie 头或商城 Token，响应也只在商品 ID 校验后用于当前采集。
- Shopee/TikTok 的 MAIN-world 观察器在 `document_start` 安装但默认不激活；隔离世界确认插件已有有效登录后才激活。它只 clone 同源、商品相关 URL 的 JSON 响应，在严格找到当前 shopId/itemId 或 productId 且对象自身包含真实 SKU 数组后，才通过每页随机事件通道传递最大 2 MB 的商品片段。它不查看或传送 Cookie、请求头、响应头和完整网络日志；后台仍把桥接内容当作不可信输入再次校验。
- 平台域名放在 `optional_host_permissions` 中。用户保存或配对时，只请求其填写的精确主机，例如 `https://hoyee.org.cn/*`；Chrome 的主机权限不区分端口，但实际请求仍固定发往配置的完整 origin。
- 为支持自建部署，Manifest 声明了可选的 `https://*/*` 范围；它不会在安装时自动获得任何站点权限。HTTP 可选权限只覆盖本机调试地址。
- Bearer Token 仅写入 `chrome.storage.local`。后台启动时调用 `setAccessLevel({accessLevel: "TRUSTED_CONTEXTS"})`，内容脚本不能读取存储。
- Token 只由扩展后台加入 `Authorization` 请求头。内容脚本、商城页面和采集 JSON 永远拿不到 Token。
- 请求使用 `credentials: "omit"`，不会混入赫逸网页 Session Cookie。
- 页面 DOM 被当作不可信输入。后台重新执行长度、类型、URL、平台与数量校验后才提交。
- 扩展不保存平台密码、商城登录信息、商城 Cookie、第三方 API Key 或 AI 模型 Key。
- 扩展不执行远程代码；所有 JavaScript 随扩展发布并受 Manifest V3 CSP 限制。

`chrome.storage.local` 是浏览器配置文件中的持久存储，并不是硬件密钥库。因此后端必须让插件 Token 短期有效、可撤销、权限最小化，且每次请求重新校验账号状态、企业隔离与 RBAC 权限。

## 解析与降级策略

商品详情按以下顺序组合字段：

1. 与当前 URL 商品 ID 严格一致的第一方结构化商品数据：Shopee 同源 PDP JSON；TikTok SSR/bootstrap 或受限同源响应桥。
2. 当前可见 DOM：平台提供的 `data-testid`、`data-e2e`、语义标签、`h1`、价格/评分/销量区域。
3. `application/ld+json` 中 `@type=Product` 的结构化字段。
4. `og:title`、`og:image`、`og:description`、Twitter meta。
5. 当前站点默认货币，仅在页面有价格文本但没有 ISO 货币码时使用。

商品卡片先从链接识别商品 ID，再寻找最近的 `article`、`li`、`data-testid/data-e2e` 商品卡片或合理尺寸父容器。点击卡片按钮后，后台在当前标签页登记一个三分钟有效、绑定 tab/platform/productId/shopId 的 pending 采集，再导航到详情；详情页再次核对身份后自动采集。MutationObserver 配合 URL 轮询支持 SPA 重渲染；扫描有节流和可视区域限制。

CSS 类经常变化，因此类名只作为低优先级降级。所有价格同时保留原始文本和数值候选，平台应在发布前复核。Shopee 价格只对 PDP 返回的已知微单位价格字段按 `100000` 缩放；TikTok 只接受十进制数值或带明确小数位的价格对象，不按金额大小猜缩放。扩展不会读取商城认证头，也不会把页面 Cookie、请求头或模型密钥传给赫逸平台。

## 手工测试清单

1. 未配对时点击页面“一键采集”：显示“请先登录”，不产生平台请求。
2. TikTok Security Check/拼图页和 Shopee `/verify/traffic/error`：没有采集按钮和采集请求，只显示完成验证后刷新的提示。
3. 输入非法 HTTP 外网地址：插件拒绝；本机 HTTP 可用于调试。
4. 拒绝平台 origin 权限：不发送绑定码。
5. 使用过期/重复绑定码：显示“绑定码无效或已过期”，本地不保存绑定码。
6. 配对成功：显示用户、企业、权限和有效期；检查内容脚本无法读取 `chrome.storage.local`。
7. Shopee 六个站点的详情页和列表页：按钮只出现一次；列表按钮先进入详情再自动采集，成功后为“已采集”。
8. TikTok Shop SPA 切换列表与详情：按钮随路由更新，无重复按钮。
9. 同一商品在两张卡片出现：一次请求期间全部同 URL 按钮锁定；成功后全部显示“已采集”。
10. 后端返回 `401`：清 Token、徽标显示 `!`、按钮提示登录。
11. 后端返回 `403`：Token 保留、按钮显示“无采集权限”。
12. 页面缺少 SKU、价格、规格或 SKU 图，SKU 数量不符或 modelId 重复：请求明确携带 `skuCompleteness.complete=false` 和 `issues`，按钮显示“SKU待确认”，不伪造数据；仅缺库存时仍可视为完整，库存保持为空。
13. 运行 `npm run check`：96 SKU Shopee fixture、单 SKU Shopee fixture、TikTok 多规格 fixture 和风控页守卫全部通过。
14. 点击退出：平台 Token 撤销、本地 Token 删除、平台 origin 权限移除。

## 后续扩展

后续如果确实需要 ReefAPI/TMAPI 补全，只能由赫逸后端持有 Key、计算额度并调用。禁止把供应商 Key、模型 Key 或店铺授权 Token 下发到本扩展。
