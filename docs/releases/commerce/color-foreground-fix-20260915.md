# SKU 取色、已处理素材优先与动画区修复

> 历史发布记录；镜像、测试与“当前/最新”均指记录当时。 当前入口见[业务说明](../../business/README.md)与[运维指南](../../guides/operations.md)。

- 印花提取与各 SKU 取色共用来源解析：PROCESSED 素材优先；内部水印素材必须属于当前组织、商品、图片且检查记录为 READY。有效处理结果读取失败时不静默下载原图。无处理结果时才使用已归档/原始图。
- 取色和几何映射分离：白底图自适应阈值保留浅色高光；场景图使用本地 U2NetP alpha 遮罩，随后继续验证主体占比、连通性、各区域布色一致性和色名。alpha 只用作取色证据，不能代替成品几何映射。取色可以利用局部触边的衣物，但实际套版仍要求完整轮廓。
- 本地服务不生成 RGB 像素，不调用收费模型。Java 将图片标准化为 PNG，逐像素检查返回值的 RGB 不变。HTTP 明确发送 Content-Length，设连接和读取超时。缓存键包含颜色算法和遮罩算法版本。
- 四宫格 AI 生图不是当前必经步骤，本批次 16 张已处理 SKU 图均通过本地取色，因此没有为底图额外调用 AI。不能把减少请求数等同于确定减少积分或更可靠；如以后加入，需要记录每格对应关系、验证颜色，并遵守任务调用预算。
- 动画区桌面最小高度 370px，商品展示 190×226px；窄屏纵排。准备阶段可显示来源图，无需等 EXTRACT 完成。旧已生成任务仍显示归档的实际输入。

## 本地服务

`docker compose -p heyi-foreground -f compose.foreground.yaml up -d --build`

配置从被 gitignore 的 `.watermark.local.env` 读取，文件权限 0600，复用原内部鉴权配置。没有公开端口，只接入原 Docker 内网 `web_sdk__default`。停止的旧容器 `heyi-watermark-before-color-20260915` 保留用于回退。

U2NetP 权重来自 rembg 发布：
https://github.com/danielgatis/rembg/releases/download/v0.0.0/u2netp.onnx

SHA256: `309c8469258dda742793dce0ebea8e6dd393174f89934733ecc8b14c76f4ddd8`。
网络架构来源 https://github.com/xuebinqin/U-2-Net （Apache-2.0）；本地推理只产生显著前景候选，并非通用服装语义识别或色号测量。

## 验证

本轮 60 项 Java 定向回归测试通过，含已处理素材优先、来源归属、失败不退回原图、HTTP 请求长度、RGB 保持、浅色高光和原有套版约束。Python 2 项测试通过。16 张实际已处理 SKU 图片的取色报告：`output/color-foreground-fix-20260915/real-colors-report.txt`。

仅替换本轮编译类和前端 CSS/index，保留运行 JAR 其余内容及运行前端文件。备份和构建目录位于 `output/color-foreground-fix-20260915/`。

实际恢复验证：执行 `3943e120-31f8-49f6-a594-ed54619d02e2` 的 16 个 BASE_COLOR 已 READY；EXTRACT 已完成并进入 VARIATE。数据库引用比对确认提取图与 16 个颜色来源均取自 READY 去水印归档。动画页面已现场截图检查。
