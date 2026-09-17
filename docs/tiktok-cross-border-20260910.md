# TikTok Shop 跨境商品发布：官方依据与测试边界

核查日期：2026-09-10。通过 ego-browser 打开官方文档并展开相关请求、响应字段；本文件记录已核查的契约与本轮实测状态，不表示跨境代码已部署或跨境建品已成功。

## 当前结论

本土 MY 店铺已经真实上传成功。跨境店铺应接入当前的 **LOCAL_REPLICATION** 流程，确认目标店铺能力后使用 `Create Product` 的 `save_mode=AS_DRAFT` 保存待上架商品。

不能仅凭店铺类型为 `CROSS_BORDER` 就假设请求参数、仓库规则和本土完全相同；也不能直接移除现有跨境限制。旧 Global Product 文档仍能访问，但后续公告已经宣布该流程退役，不应将它作为新的替代发布路线。

## 官方流程更新与退役公告

### 1. GS POP 新流程

[GS POP product listing workflow revamp](https://partner.tiktokshop.com/docv2/page/a5crrkzs)，发布日期 **2025-08-13**，宣布 **2025-08-19** 上线；适用范围为 `Platform Open Plan (POP) - All markets`、版本 `202309 (and later)`。

官方原文：

> Previously, this endpoint was used exclusively for local sellers to create products. In the new workflow, GS POP sellers will also need to use this endpoint.

这段文字对应 `POST Create Product`。公告同时明确：

- `GET Global Listing Rules`：查询 GS POP 店铺的商品发布方式及仓库库存规则。
- `POST Check Product Listing`：扩展给 GS POP 店铺校验商品资料。
- `GET Product`：新增 `return_draft_version=true` 读取草稿版本；新增 `is_replicated` 标记新流程创建或迁移的商品。
- `POST Update Inventory`：用于新流程的跨境库存更新。
- `POST Replicate Product`：把某国本地店铺商品复制到其他目标国家店铺。
- `GET Global Replicated Products`：查询相关复制商品。
- `Global listing method change`、`Global replication status change`：通知发布方式及复制结果变化。

公告链接的详细 use-case guide 位于官方 Lark 文档。此次打开后跳转到 `accounts.feishu.cn` 并返回 HTTP 504，未将无法读取的内容作为已验证依据。

### 2. 旧 Global Product 流程退役

[[Important] Legacy Global Product Management Flow Decommission Notice](https://partner.tiktokshop.com/docv2/page/fapus8ql)，发布日期 **2025-12-17**。

官方原文：

> All cross-border merchants are now supported and can use this new workflow to complete product management–related operations.

> Starting January 31, the platform will progressively shut down the legacy global product management flow.

> Cross-border merchants will be supported only via the new product management workflow for product publishing.

> All legacy global product management–related APIs will be officially deprecated and fully retired.

公告正文写的是 `January 31`，未在该句重复年份；发布日期为 2025-12-17。影响范围明确为 **跨境商家及相关开发者，所有市场**。列出的退役接口包含 Get Global Categories/Rules/Attributes、Create/Publish/Edit/Delete/Get/Search Global Product、Update Global Inventory、Partial Edit Global Product，版本为 `202309 (and later)`。

`Get Global Listing Rules` 不在该退役列表中。“所有跨境商家支持新流程”是平台流程声明，不是对当前测试账号授权、沙箱连通性或具体请求响应的证明。仍需读取当前目标店铺的官方能力，不能把网络错误当成可用能力。

## 当前店铺能力查询

官方文档：[Get Global Listing Rules 202507](https://partner.tiktokshop.com/docv2/page/get-global-listing-rules-202507)。

已通过官方测试器选择器核对的直达入口：

[Get Global Listing Rules API Testing Tool](https://partner.tiktokshop.com/dev/api-testing-tool?apiId=7522783358108878608&pkgId=430148&versionId=202507)

```text
GET /product/202507/global_listing_rules
Required scope: seller.product.basic
Header:
  content-type: application/json
  x-tts-access-token: 卖家授权令牌
Query:
  app_key: string
  sign: string
  timestamp: int
  shop_cipher: string, required
```

官方接口说明：

> Retrieves the global listing rules for a local shop that belongs to a global seller. E.g. The supported listing methods or the inventory allocation rules. The rules differ by the markets they serve.

`listing_methods` 的字段说明为：

> The methods at which sellers can list products in this shop.

因此结果属于请求 `shop_cipher` 对应的店铺，不能把一个店铺的结果套用到同一卖家的其他国家店铺。

### 响应字段

| 字段 | 官方表格类型 | 含义 |
|---|---|---|
| `code` | `int` | API 成功或失败状态码 |
| `message` | `string` | API 结果说明 |
| `request_id` | `string` | 官方请求跟踪编号 |
| `data` | `object` | 业务响应 |
| `data.listing_methods` | `[]string` | 当前店铺支持的商品发布方式 |
| `data.inventory_rules` | `[]object` | 与当前店铺关联的所有仓库的库存分配规则 |
| `data.inventory_rules[].local_warehouse_id` | `string` | 当前店铺关联的仓库 ID |
| `data.inventory_rules[].allocation_mode` | `string` | `SHARED`、`DYNAMIC`、`MANUAL` |
| `data.inventory_rules[].associated_warehouses` | `[]object` | 共享或动态分配库存所关联的仓库；适用于 `SHARED`、`DYNAMIC` |
| `data.inventory_rules[].associated_warehouses[].warehouse_id` | `string` | 关联仓库 ID |
| `data.inventory_rules[].associated_warehouses[].region` | `string` | 仓库所在国家 |

`listing_methods` 的官方枚举：

- `GLOBAL_PUBLISHING`：`Create a global product, then publish it to target local markets.`
- `LOCAL_REPLICATION`：`Create local product, then replicate it to other target local markets.`

**官方文档存在类型不一致**：字段表格写 `[]string`，但 Response Example 使用标量字符串：

```json
{
  "code": 0,
  "data": {
    "listing_methods": "GLOBAL_PUBLISHING",
    "inventory_rules": [
      {
        "local_warehouse_id": "7068517275539719942",
        "allocation_mode": "SHARED",
        "associated_warehouses": [
          {"warehouse_id": "7068517275539718842", "region": "DE"}
        ]
      }
    ]
  }
}
```

以上编号是官方文档示例，不是当前测试店铺响应。实现应兼容明确的字符串和字符串数组；仅在真实成功响应明确包含 `LOCAL_REPLICATION` 时放行当前跨境草稿流程。空值、异常结构、请求失败及只有 `GLOBAL_PUBLISHING` 都不能当作已确认新流程能力，也不能因此回退到已退役的写入接口。

## 草稿契约

[Create Product 202309](https://partner.tiktokshop.com/docv2/page/create-product-202309) 当前说明：

> Global sellers who have migrated to use the local replication listing method can use this API to create products. Otherwise, they can continue to use the Create Global Product API to create global products.

其中推荐旧接口的后半句必须结合更新的退役公告阅读，不能据此恢复旧发布路线。

本接口支持：

```text
POST /product/202309/products
save_mode = AS_DRAFT | LISTING
default = LISTING
```

[Draft status available for Create Product API](https://partner.tiktokshop.com/docv2/page/draft-status-available-for-create-product-api)，官方显示发布日期 **01-12-2024**，明确：

> When save_mode is AS_DRAFT, the API will save the product information as draft in the Seller Center without listing.

该公告声明适用于所有市场及所有卖家，当前接口的适用性仍应结合跨境迁移能力判断。测试时必须明确发送 `AS_DRAFT`，不能依赖默认值。创建后应通过官方 `Get Product` 回读目标商品，确认商品 ID、草稿状态及 SKU/媒体信息；接口响应丢失时不可盲目创建第二件商品。

### 全球未发布对象不能代替店铺草稿

[Get Global Product](https://partner.tiktokshop.com/docv2/page/get-global-product-202309) 提到可读取 `DRAFT`、`UNPUBLISHED`、`PUBLISHED` 的全球商品；[Search Global Products](https://partner.tiktokshop.com/docv2/page/search-global-products-202312) 也提供这些状态过滤。

但本轮核对的 [Create Global Product](https://partner.tiktokshop.com/docv2/page/create-global-product-202309)、[Publish Global Product](https://partner.tiktokshop.com/docv2/page/publish-global-product-202309)、[Edit Global Product](https://partner.tiktokshop.com/docv2/page/edit-global-product-202309) 请求字段未提供 `save_mode`。Publish Global Product 原文明确：

> After publishing, the product is sent for review by TikTok Shop in the respective markets.

未找到明确官方依据证明“只创建全球对象”会在 MY 店铺的待上架目录产生本地商品。全球 `UNPUBLISHED`、全球 `DRAFT` 与目标店铺 `DRAFT` 不能混同；旧接口也已经在退役公告范围内。本轮不调用任何跨境 Create 或 Publish 来试探未确认行为。

## 仓库与库存

新流程 Create Product 的库存字段仍为：

```text
skus[].inventory: required []object
  warehouse_id: required string
  quantity: int, [1, 99999]
```

`quantity` 官方定义是仓库中可以立即发货的现货数量。仓库 ID 从目标店铺可用仓库列表获得，并应与 Global Listing Rules 返回的 `local_warehouse_id` 对照。

库存分配模式的官方含义：

- `SHARED`：各覆盖市场共用库存池；一个市场扣减，会影响所有覆盖市场。
- `DYNAMIC`：库存按比例分到覆盖市场；某市场扣减只影响该市场。
- `MANUAL`：各市场库存由卖家独立配置；某市场扣减只影响该市场。

`SHARED` 不改变 `warehouse_id`、`quantity` 的字段形状，但不能把默认 999 当作“每个国家互不影响的 999”。本轮准备的首个新商品草稿和将来复制已有商品的库存操作必须分别核对仓库规则；不能向关联国家重复独立分配共享 SKU 库存。

[Replicate Product 202507](https://partner.tiktokshop.com/docv2/page/replicate-product-202507) 使用 `POST /product/202507/products/{product_id}/global_replicate`。这是复制商品操作，不属于本轮只创建一个跨境草稿的必要步骤；不能自动附带执行。其 EU 专用 `inventory_mode=SHARED/EXCLUSIVE` 也不应与 Global Listing Rules 的三种 `allocation_mode` 混为同一个字段。

## 旧流程的历史差异，仅供理解

以下来自仍可访问的旧接口文档，**不是新增实施路线**：

- Global Create/Publish 是卖家级接口，权限为 `seller.global_product.write`；使用全球商品/全球 SKU ID，发布目标按 `publish_target[].region` 指定。
- 类目及属性使用 Global Categories/Rules/Attributes；SEA 在 Create Global Product 中要求 `category_version=v2`。
- 全球 SKU 上限在 EU、JP、UK、US 为 300，其他地区为 100。
- 全球基准价格对 global sellers 使用 USD，对 intra-EU sellers 使用 EUR；发布时平台按当地汇率及税费等计算本地价格。JP/US 使用中国仓库有单独售卖价要求。
- 全球仓库接口为 [Get Global Seller Warehouse](https://partner.tiktokshop.com/docv2/page/get-global-seller-warehouse-202309)：`GET /logistics/202309/global_warehouses`，权限 `seller.shop.info`。它与目标店铺本地仓库列表不是同一个数据范围。

## 2026-09-10 当前实测边界

已确认的跨境沙箱账号信息：

| 项目 | 值 |
|---|---|
| 测试账号显示名 | `my-kua` |
| 店铺类型 | `CROSS_BORDER` |
| 基地区域 | `CN` |
| 目标市场 | `MY` |
| 商家 ID | `7494887747929212239` |
| 店铺 ID | `8682681900016567631` |
| 系统店铺 ID | `5a98cfac-d15c-4ac8-9efa-39007124cc01` |

授权过程与当前事实：

- **仅本土店铺已经真实上传成功**，不能将该结果写成跨境验收通过。
- 最初在跨境测试账号的授权应用窗口选择 Heyi AI 并点击授权后，窗口关闭，但没有成功回调系统；当时店铺尚未入库。
- 跨境沙箱测试功能页面曾出现“网络错误/错误”，测试器“获取店铺授权”按钮曾无反馈；本土测试此前正常。
- 随后通过“开始测试 → 前往商家中心”自动登录 `my-kua`，官方商品创建页面显示沙箱店铺及“另存草稿”入口。这是界面能力证据，不代表已经调用 API 建品。
- 从本地合法授权状态进入跨境授权页面后，已识别正确店铺，但一度出现“连接失败 / 网络错误”，重试一次仍相同。重新加载后恢复安装页面，**用户已手动完成最终确认授权**。
- **数据库已确认该跨境店铺入库且状态为 `ACTIVE`**：系统店铺 ID `5a98cfac-d15c-4ac8-9efa-39007124cc01`，外部店铺 ID `8682681900016567631`，区域 `MY`，类型 `CROSS_BORDER`。
- 只读的 Global Listing Rules、Categories、Warehouses 请求均已返回成功。已确认店铺支持 `LOCAL_REPLICATION`，库存规则为 `MANUAL`，规则仓库与默认销售仓库 ID 一致；具体结果见下节。
- 当前阻碍是店铺的主营类目权限：官方返回的可用类目仅覆盖 Home Supplies，女式 T 恤 `601302` 为 `NON_MAIN_CATEGORY`。因此尚未验证这件服饰商品能在跨境店铺建为草稿。
- **未调用任何跨境 Create Product、Create Global Product、Publish Global Product 或 Replicate Product；未提交任何跨境商品。**

本文件不保存令牌、密钥、密码或可复用授权链接。

### 已返回的真实只读结果

原始回执保存在：

- [listing-rules.json](../output/tiktok-cross-border-20260910/listing-rules.json)
- [warehouses.json](../output/tiktok-cross-border-20260910/warehouses.json)
- [categories.json](../output/tiktok-cross-border-20260910/categories.json)

Global Listing Rules 实际响应：

```json
{
  "code": 0,
  "data": {
    "inventory_rules": [
      {
        "allocation_mode": "MANUAL",
        "local_warehouse_id": "7683646352276309778"
      }
    ],
    "listing_methods": ["LOCAL_REPLICATION"]
  },
  "message": "Success",
  "request_id": "202609101606122E5E793E1A2B2801AE8F"
}
```

仓库结果包含默认 `SALES_WAREHOUSE`，ID 为 `7683646352276309778`，`sub_type=CB_DIRECT_SHIPPING_WAREHOUSE`，与规则响应匹配。该仓库返回的显示名为 `Sandbox PH CrossBorder Sales warehouse`；当前已授权店铺的区域仍为 MY。不能依据这个显示名擅自切换目标国家，应以已授权店铺身份及官方仓库 ID 为依据。

类目接口实际返回 2444 条记录：

- 144 条标记 `AVAILABLE`，路径属于 Home Supplies。
- 2300 条标记 `NON_MAIN_CATEGORY`。
- 目标服饰类目 `601302`，`local_name=Women's T-shirts`，`parent_id=842248`，`is_leaf=true`，`permission_statuses=["NON_MAIN_CATEGORY"]`。

### 非主营类目的官方限制与草稿边界

[Get Categories 202309](https://partner.tiktokshop.com/docv2/page/get-categories-202309) 的 `permission_statuses` 明确定义：

> AVAILABLE: You have the permission to create products in this category.

> NON_MAIN_CATEGORY: This category is out of scope for this shop, and you do not have permission to use it. Contact your account manager for assistance.

[Create Product 202309](https://partner.tiktokshop.com/docv2/page/create-product-202309) 要求必填 `category_id`，并注明使用 Get Categories 获取可用类目；其错误码 `12052225` 定义为：

> You are not authorized to sell in this category because it does not fall under your shop's main category. Contact your account manager for help or select another category.

`AS_DRAFT` 的定义只规定保存为草稿，官方字段说明没有给予 `NON_MAIN_CATEGORY` 权限豁免。因此应按现有官方契约阻止该店铺提交女装类目，不能通过换成 Home Supplies 来发布实际服饰商品。**本轮未提交这件跨境草稿，未获得实际的 `AS_DRAFT` 类目拒绝回执；上述是契约结论，不能表述为已实测该错误码。**

已核对的公开 [Seller Center development shops 指南](https://partner.tiktokshop.com/docv2/page/seller-center-development-shops) 只介绍 Core 测试账号类型、市场、账号昵称、授权和测试功能，未提供修改测试账号主营类目的配置步骤。本轮未找到明确官方依据证明 MY Core 跨境沙箱可以自行将主营类目从 Home Supplies 改为女装；这不等于已证明平台界面绝无此能力。官方目前给出的非主营类目处理方式是联系 account manager。此研究没有修改测试账号配置或卖家中心主营资料。

### 沙箱授权的官方依据

[Generate a test access token](https://partner.tiktokshop.com/docv2/page/generate-test-access-token) 明确要求完整授权链路：测试账号的 `Authorize App` → 选择应用 → 测试 Seller Center 确认安装 → 勾选授权确认并点击 `Authorize` → 回调 URL 返回 `code` → 后端兑换令牌。仅选择应用、打开卖家中心或具备浏览器登录状态，都不能替代最终 API 授权。

[API testing tool](https://partner.tiktokshop.com/docv2/page/api-testing-tool) 提供测试器内授权，或通过正常 OAuth 获取 access token 后填入测试器的两种方式。[Seller Center development shops](https://partner.tiktokshop.com/docv2/page/seller-center-development-shops) 说明 MY 支持 Core Function 跨境测试账号；Full Function 的跨境支持当前不可用。本次未通过浏览器登录 Cookie 冒充 Open API 令牌。

## 待实现及验收计划

相关代码与部署由主任务继续验证；以下进度不表示跨境建品已经通过验收：

1. 已完成跨境店铺授权回调，系统记录的目标店铺状态为 `ACTIVE`。
2. 已用真实成功响应确认 `LOCAL_REPLICATION`、`MANUAL` 和匹配的默认销售仓库。类目检查发现女装不在当前店铺主营范围，待取得该类目权限或选择真实属于可用类目的测试商品后再验收建品。
3. 对已验证新流程能力的跨境店铺复用当前 Create Product、Check Product Listing 和 `AS_DRAFT` 路线；能力不明确时保留限制并报告官方错误。
4. 保留目标市场的类目、属性、币种、仓库及语言适配，避免复用其他店铺或其他国家的结果。
5. 在真实授权、能力查询、建品请求和 Get Product 回读均成功后，才记录该跨境店铺通过验收。保持只进入草稿，不附带上架或跨国复制。
