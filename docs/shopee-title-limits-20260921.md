# Shopee 标题按目标店铺适配（2026-09-21）

线上目标店铺的 `/api/v2/product/get_item_limit` 返回 `item_name_length_limit={min_limit:20,max_limit:100}`。视频上传账本已 READY，后续失败是采集标题直接透传 `add_item.item_name`，超过标题上限。

新增 `ShopeeProductTitle`：发布前携带目标店铺授权、目标 category_id 查询官方限制；缺少或异常限制时在媒体上传和创建边界前阻止提交。标题过长时仅删除明确促销前缀，再按 Unicode 词边界缩短，避免英文半词、断裂代理对、末尾未闭合括号。短标题仅尝试补充采集记录中的真实类目信息；不足时提示补充真实属性，不编造商品卖点或重复凑字。合法标题保持原文（仅规范空白和 NFC）。

采集原标题不改；发布回执增加 publishedTitle、titleAdjusted。本次接入范围为 Shopee 官方发布器，按官方店铺/类目规则适配；TikTok 的标题规则与发布器未改动。

验证：4 项标题算法测试，4 项视频/商品创建边界测试，5 项上架状态验证测试，共 13 项通过。修改前的本地发布器重新编译后，其 javap 方法字节码与线上完全一致；发布基于当前生产 jar，仅替换发布器及相关 5 个 class，保留上一轮视频预处理修复和所有配置。

部署目录：`/opt/heyi/deployments/shopee-title-20260921`。生产镜像：`ai-commerce-platform:shopee-title-20260921`。
