# 平台必填参数约定

商家要求（2026-09-21）：Shopee 与 TikTok Shop 的必填项应由系统记住并自动补全，不依赖聊天或逐次接口失败。按平台、站点和目标叶子类目读取官方规则，不共享属性编号，不把当前类目清单硬编码成全平台清单。

- Shopee：get_attribute_tree 读取 mandatory、选项与数量限制；get_item_limit 读取标题、价格、尺码表限制。ShopeeAttributeResolver 在图片/视频提交前补齐缺失必填属性；依据原始采集文字、SKU 和原图进行识别，校验官方选项并缓存证据。材质空缺时使用商家指定的 Cotton 默认值。
- TikTok Shop：目标站点 category_version 对应的 categories/{id}/attributes、categories/{id}/rules；现有 TikTokProductAttributeResolver 处理 is_requried/is_required、条件必填、单多选和源属性映射，发布前 listing_check 校验。不能用 Shopee 属性 ID 代替 TikTok 属性 ID。
- 不覆盖显式已有参数；保留原价、源尺码表及既有媒体结果。未知的资质、产地、技术规格不能为绕过校验而虚构。
- 仅补齐有依据的商品属性。仓库、物流、重量、价格等分别按平台和店铺配置校验。
- 平台创建超时不盲目重试；只在确定未创建商品时恢复，防止重复商品。

当前 Shopee PH 类目 100352：Material、Cropped Top、Neckline、Sleeve Length、Plus Size。此清单仅为当日接口观察，实际发布仍实时查询。

## 本次上线与验证

Shopee 自动补全已接入正式 provider，在媒体上传及 create boundary 前执行。识别使用归档原图，不用去水印生成图推测原商品属性。结果按企业、商品、目标类目、源事实、官方定义及原图标识缓存，缓存读取后仍验证官方选项。单选/多选限制、重复/未知选项、缺失证据均会拦截。

视觉服务网络错误、HTTP 429/5xx 最多尝试 3 次；业务输入错误不重试。原价、视频、尺码表逻辑保留。相关 Java 回归测试 11 项通过。部署由线上原 jar 编译叠加，仅发布相关类，Pi 仅插入属性识别分支；环境变量与存储挂载保持一致。

加速改进：ShopeeAttributeFacts 优先精确匹配采集字段（含马来语领型、袖长、Size type），仅缺失时使用明确标题词。冲突源值不任意挑选；缺少“短款”一词不会默认为非短款。只有剩余必填项调用视觉模型，最多 6 张原图压缩 JPEG 拼图，缓存结果。当前商品 4 个缺失项中 3 个可直接匹配，只需看图判断 Cropped Top。模型总时限 180 秒、调用端 200 秒，网络/429/5xx 至多 3 次尝试。14 项相关 Java 测试通过。

实单识别证据：捕获 065da755-0a2b-4e40-a832-b61b48d6a5fd，采集 Garis Leher=Leher Krew → Round Neck；Panjang Lengan Baju=Lengan pendek → Short Sleeves；Size type=Biasa → Plus Size=No。剩余 Cropped Top 经原图与采集衣长信息识别为 No（官方 attribute_id=100150，value_id=1359），已保存识别证据缓存。编号为当前类目响应，非跨平台通用常量。

## 实单最终结果

2026-09-21 当前任务已 SUCCEEDED / COMPLETED，审计 PUBLISHED，Shopee 商品 50218235763 保持 UNLIST（待上架）。官方回读 73/73 SKU、9 张主图、1 个视频、尺码表，全部必填属性齐全；每个 SKU 的远端价格与换汇原价按平台取整差值 <=0.5 PHP，库存均为请求的 999。

另外发现 init_tier_variation 刚成功后立即 add_model 会出现明确的空索引校验拒绝。等待同步后对同一商品追加缺失 23 SKU 成功，未重建商品。系统已加入初始化型号回读与仅针对精确 tier_index 空索引校验拒绝的最多 4 次追加尝试；不重试未知创建结果或超时。相关测试共 15 项通过。部署 ai-commerce-platform:shopee-model-repair-20260921，生产健康检查 UP。

参考官方文档：https://open.shopee.cn/documents/v2/v2.product.add_model?module=89&type=1
