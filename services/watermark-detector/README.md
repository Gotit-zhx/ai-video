# 本地水印检测（商品图原型）

维护说明更新：2026-11-05。服务仍是可选本地证据提供方，根 `application.yml` 的 `APP_PRODUCT_WATERMARK_LOCAL_ENABLED` 默认 false；不能由默认值推断生产开关。主系统部署与验收见[运维指南](../../docs/guides/operations.md)、[采集/上传记录](../../docs/releases/commerce/product-capture-listing-20261010.md)。

`Dockerfile.custom` 构建自写的 OpenCV 多尺度水印指纹匹配服务，不需要 YOLO 权重、OCR 或远程模型。`Dockerfile` 保留原 YOLO 基线供比较。HTTP 接口兼容后端 LocalWatermarkDetector。

当前能力：
- 对已标注的 SUKISILK 横向水印，用两个独立笔画片段的相关性及相对位置共同判断，输出候选区域。
- 仅单片段匹配不通过。支持测试覆盖的缩放、平移、亮度变化和 JPEG 压缩。
- 用户已确认蓝猫图无叠加水印，`references/confirmed-absence.json` 保存原文件 SHA256。只有完全相同文件可跳过有无水印的模型检测。该确认不代表商品形态保持一致，不会跳过编辑后原图对比。
- 其余 NO_CANDIDATE 一律需要复核；绝不解释为确定无水印。

限制：两个笔画来自同一张标注图，因此原图命中是校准结果，不是独立测试集准确率。尚未验证其他商品、字体、旋转、裁切、不同透明度和不同底纹上的泛化。模板相关分数不是概率，整行候选框不是像素级擦除蒙版。大框不能直接擦除，否则会损坏刺绣。几何扫描和 Tesseract 原型误报/漏检，未纳入正式路径。

## 构建与测试

```sh
docker build -f services/watermark-detector/Dockerfile.custom -t heyi-watermark-custom:local watermark-detector
docker run --rm --network none -v "$PWD:/workspace" -w /workspace/services/watermark-detector heyi-watermark-custom:local python -m unittest -v test_local_overlay test_server
```

服务监听 8091，必须配置至少 24 字符的 WATERMARK_DETECTOR_TOKEN，请使用内部网络或隔离回环测试，不公开端口。POST /detect 的 data 字段为原图 base64，认证头 X-Internal-Token。进程只读图、不编辑图、不发远程请求。

后端已接入本地候选证据和严格的人工确认 SHA256 放行分支。仓库保留 V80 及后续至 V146 的迁移，早期“V80 尚未部署”只描述当时实验，不能作为当前数据库状态；实际是否启用本地服务及生产迁移状态需现场核对。
