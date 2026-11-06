# TikTok 采集类目与详情图补全 0.2.4

> 历史发布记录；镜像、测试与“当前/最新”均指记录当时。 当前入口见[业务说明](../../business/README.md)与[运维指南](../../guides/operations.md)。

目标商品：1730135047201523374（MY）；原记录 cadfaf79-0b0f-49a1-b298-da9e37e3ecd8。

## 根因

Chrome 中实际运行的插件最初为 0.2.1。商品的三级类目位于匹配商品组件的 `component_data.category_info.recommended_categories`，与 `product_info` 同层，旧解析器和响应观察器遗漏此包装层。页面底部还有无 breadcrumb 标记的 /my/c/ 分类链接。商品描述区不在 main 内，DOM 类名也不包含 description，8 张描述图全部漏采；bootstrap 中该 description 是富文本 JSON 字符串。

## 改动

- 匹配 product_id 后保留相邻 category_info，读取推荐类目路径；隔离其他商品组件与推荐内容。
- 支持同站页尾紧凑类目路径，保留来源；同商品、同 SKU 集合且店铺不冲突时补缺失元数据，保留最新 SKU 价格与库存。
- 解析匹配商品 description 的图片节点，按 DETAIL 纳入图片计划，排除视频封面、评论与推荐图。
- SKU 完整但类目缺失时按钮明确提示补采，不误报完整成功。
- 后端受限补采仅填空类目、追加缺失详情图，保留原商品 ID、SKU、原图 ID、处理结果和顺序。已存在类目不覆盖；人工修改、批准/发布和处理中状态继续受保护。
- ByteDance CDN 与签名按图片资产键去重。新图 PENDING，READY 回 CAPTURED；已有处理图保持原状。重复请求无写入，不能回退成全量重采。
- 图片完整度按合并后的实体重新核算，清除已补足的旧缺图警告，继续报告当前仍缺失的数据。

## 验证与部署

完整插件 scripts/check.mjs 通过；后端 96 项相关测试通过：CategorySupplement 36、DetailImagePlan 10、CaptureService 47、ImagePlan 3。

Chrome 保存的真实商品数据回放：三级类目“女装与女士内衣 > 女士上装 > 女士短袖”，71 SKU，35 图（主图 1、规格图 18、商品图 8、详情图 8）。响应观察器回放保持同样类目和 SKU 数据。

2026-09-10 本地服务已部署 ai-commerce-platform:tiktok-collector-024-20260910，actuator health 为 UP。JAR SHA256：64c1676f054b585ea6999bfa868b2e38f1d5375676675db7646e1cf4e0a35130。

已通过临时本地 QA 页面，在已有登录会话下把真实解析结果提交到正式的商品采集接口，连续提交两次：首次补齐到35图和三级类目，版本4→5；第二次版本不变、不新增图片或商品。数据库逐行核对原71 SKU和27张处理图完全不变，同商品非删除记录数为1。原商品页面刷新后已显示类目、71 SKU和27/35图片处理进度。新增8图等待常规图片处理，没有触发生图或发布。临时QA网页已从运行服务移除。

插件安装目录 output/collector/heyi-collector 已同步0.2.4，源文件哈希一致，发行包 output/collector/heyi-collector-0.2.4.zip。Chrome原生自动控制后续卡在旧菜单，尚未验证浏览器加载0.2.4并点击悬浮按钮的最后一步，已请用户重新加载扩展、刷新商品页。本次真实写入验证是页面数据回放经过正常采集API，不冒充Chrome悬浮按钮端到端验证。

## 证据

- output/tiktok-collector-20260910/actual-parser-category-summary.json
- output/tiktok-collector-20260910/real-description-vs-db.json
- output/tiktok-collector-20260910/live-supplement-result.json
- output/tiktok-collector-20260910/installed-hashes.json
- output/tiktok-collector-20260910/check.log
- output/tiktok-collector-20260910/backend-build.log
