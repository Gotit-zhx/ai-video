# TikTok Shop 上传补齐与验收（2026-09-10）

> 历史发布记录；镜像、测试与“当前/最新”均指记录当时。 当前入口见[业务说明](../../business/README.md)与[运维指南](../../guides/operations.md)。

## 已部署修改

本地 Compose 运行环境使用 `APP_PRODUCT_PUBLISHING_PROVIDER=OFFICIAL`，因此 Shopee 与 TikTok 分别路由到对应官方 Provider。此前固定使用 `SHOPEE_OFFICIAL_V2` 导致 TikTok 直接报“官方商品发布接口未配置”。

TikTok LOCAL 店铺商品流程：读取目标类目属性与规则 → 准备尺码表 → 上传视频、主图、规格图及详情图 → listing_check → 持久化商品创建边界 → Create Product（`save_mode=AS_DRAFT`）→ Get Product 回读确认。SKU 不套用 Shopee 沙箱的 50 个裁剪规则。

- 规格：保留全部原 SKU、各自售价与图片，颜色规格优先，默认库存 999；原售价使用服务器汇率快照转换到目标国家货币。
- 媒体：处理归档图片可直接读取；详情图单独使用 `DESCRIPTION_IMAGE`；采集视频通过官方文件上传获取 ID，成功结果持久化复用。
- 尺码表：优先采集中的明确图片标注、明确用途 alt/角色；没有图片标注时自动从文字说明提取完整真实尺寸，生成 `SIZE_CHART_IMAGE`。每次上传自动执行，不绑定某件商品。支持单行、换行、中文、表头单位和 2XL 等尺码；缺失数值/单位时不编造。
- 属性：使用当前目标类目官方属性和值 ID，支持来源成色等事实的中英马来文映射。
- 状态：已回读确认的 TikTok `DRAFT` 显示“已上传 · 待上架”。

## 本轮实测发现并修复的问题

1. MY 女士 T 恤类目 `601302` 官方规则要求尺码表。71 SKU 来源商品的 DETAIL 27/28 确有真实表图，已保存核验后的图片标注；96 SKU 来源说明含 S–XXXL 六行真实测量，可自动生成尺码图。
2. 96 SKU 商品曾被匹配到 `862096 = Pre-Owned > Womenswear > Women's Tops`。源类别为 Tanks & Camisoles，但描述明确写了 short sleeved / Round neck。新规则在完全匹配和历史偏好之前排除无依据的二手分支，并用精确服装属性纠正这类冲突。完整 2746 个官方类目下现在稳定匹配 `601302`；真正背心仍匹配 `843400`。
3. 店铺凭证 `SELECT ... FOR UPDATE` 曾加入整个上传事务，长时间持锁。改为独立短事务；若外层事务已标记 rollback-only，保留真实异常，避免被 `UnexpectedRollbackException` 覆盖。现场日志没有记录最初异常，不能断言那两次一定都是数据库锁超时。
4. 同商品并行上传不同店铺时，草稿乐观锁冲突曾可覆盖已经确认的远端成功回执。现在保留成功审计并读取最新草稿，不覆盖用户编辑；真正数据库错误继续抛出，创建边界仍阻止重复创建。

用户确认 96 SKU 商品是全新，已保存 `normalizedData.condition=NEW`。这不是所有来源商品的无条件默认值。

## 验证与部署

- 后端 190 项相关测试通过，包括 52 项采集 Service、11 项店铺授权、19 项工作流 Processor、13 项尺码表等。
- 前端 120 项相关测试通过，涵盖批量上传、类别匹配、默认值、待上架展示。
- 事务修复做过反证：临时撤销修复后，4 个针对性回归全部失败，修复后通过。
- Dockerfile 与 Dockerfile.local 添加中英文字体，保证文字尺码表能在正式构建和本地容器中排版。
- 后端 JAR SHA256：`ac9abfdae12682026fd0867565fe1df68b85e0fd5f5bee6bfa4fc676c06b72e7`。
- 后端镜像：`ai-commerce-platform:tiktok-normal-20260910`；前端镜像：`heyi-ai-frontend:tiktok-normal-20260910`。
- 健康检查 UP，运行 JAR 与构建产物哈希一致，V93 视频上传账本迁移已应用。

实际上传测试已完成：MY LOCAL 沙箱两件商品均为 SUCCEEDED，官方 Get Product 回读为 DRAFT。71 SKU 商品 ID `1737445649025893589`，回执保留 9 张主图、18 张规格图、8 张详情图、1 个视频及源尺码表；96 SKU 商品 ID `1737445534308009173`，回执保留全部 96 个 SKU、16 张规格图及 SOURCE_MEASUREMENTS 自动生成的尺码表。第一轮失败均发生在创建之前；修复后的新批次分别于北京时间 15:30:45、15:31:58 完成。用户也确认本土测试可用。

当前 Provider 只放行已验证的 LOCAL 店铺；CROSS_BORDER / Global Seller 不在本轮已验证范围。分类规则不会保证所有来源文案均准确，信息不足时会要求明确分类。

相关证据：`output/tiktok-provider-route-20260910/`、[尺码表说明](tiktok-size-chart-20260910.md)、[详情图说明](tiktok-description-images-20260910.md)、[视频说明](tiktok-product-video-upload-20260910.md)。
