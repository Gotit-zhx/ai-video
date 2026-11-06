# 去水印阶段识别尺码表，并提取上传（2026-09-21）

> 历史发布记录；镜像、测试与“当前/最新”均指记录当时。 当前入口见[业务说明](../../business/README.md)与[运维指南](../../guides/operations.md)。

## 行为
- 水印模型同时返回 imageType（SIZE_CHART/PRODUCT/UNKNOWN）以及尺码表可读性、原始行、单位。尺码文字不是水印。
- AiWatermarkRemovalProvider 识别到 SIZE_CHART 后，以原始归档图片完成处理，不提交生图/重绘。即使数字不清晰也保留原图，发布时再拦截不完整的识别结果。
- Shopee 发布前根据真实店铺/类目 get_item_limit 的 size_chart_limit 检查要求。优先从商品文字读取有表头、单位的完整数据；图片路径优先复用水印检测记录，旧任务从最多 24 张非变体原始图片做编号预览定位，再读取全尺寸尺码图。
- 图像输入使用水印处理前的归档原图，不读取 AI 重绘的尺寸表。
- 模型只抄录原数字；代码执行 cm/mm/inch 换算，1 inch=2.54 cm。保留 originalRows/sourceUnit，校验行、重复尺码、可读性、正数、数值范围，不能确认时阻止发布，不编造尺寸或 US/EU 对应尺码。
- 提取结果及原图 PNG 带 SHA256 缓存在 size-chart-extractions-v1，重试复用，元数据和图片采用原子写入。
- 将原尺码图单独上传 media_space/upload_image，然后填入 add_item.size_chart_info.size_chart；普通图库不能替代这个字段。

## 验证
43 项 Java 测试通过：水印处理保存尺码原图且不调用生成、尺码解析、单位换算、无效/重复/不清晰数据拒绝、发布载荷专用字段。24 项 Pi 测试通过。
生产仅替换相关 6 个 Java class；Pi 从实时生产文件插入独立分析分支并修改水印提示，保留其他代码与配置。应用健康 UP，环境与挂载一致，部署前相关运行任务均为 0。

真实线上商品 065da755-0a2b-4e40-a832-b61b48d6a5fd 已自动定位并提取原尺码图 4320d421-3e67-4e4e-b2be-61f562b8b21f。与原图核对一致：S 76/53/11，M 80/54/12，L 84/55/13，XL 88/56/14（胸围/衣长/袖长，cm）。生成图未参与提取。原任务已按确认的尺码缺失拒绝安全接续，保留旧失败审计，不重复生成图片。

真实提交回执：尺码表已提取并提交到 size_chart_info，Shopee 不再返回缺少尺码表，而返回 `Attribute "Material" is mandatory required, please check and update`。商品尚未创建成功。本次没有编造材质或调整类目；在确认精确 ADD_ITEM 拒绝且无 external_id 后释放旧目标占位，界面提示新的材质缺失要求，保留所有提取缓存与历史审计。
