# Grok 图片备用路线

> 历史发布记录；镜像、测试与“当前/最新”均指记录当时。 当前入口见[业务说明](../../business/README.md)与[运维指南](../../guides/operations.md)。

2026-09-09 已在当前本地部署生效。

## 配置与行为

- 原默认图片模型保持 `gpt-image-2`。
- 新增启用、非默认的 `grok-imagine-image-2.0`，配置 ID `8a1a915c-b4d1-4c89-afd0-424fce5d377b`，仅属于当前公司的模型配置。
- 复用原图片供应商、服务地址和 OpenAI 兼容协议；按照用户后续提供的 Key 单独配置备用连接，AES-GCM 加密保存，主模型 Key 未覆盖。没有将明文密钥写入源码或测试报告。
- 主模型明确返回版权拒绝（HTTP 400/403/422、`COPYRIGHT`、`REJECTED`）时，使用原提示词和参考图切换 Grok 一次。Grok 自身拒绝不会再形成主备循环。
- 超时、结果下载失败、鉴权失败、普通安全/内容策略拒绝、没有配置备用路线时，不触发此版权切换。原有重试逻辑保留。
- 路由只读取同租户、启用的 IMAGE、同服务地址/供应商/协议的指定 Grok 配置。停用备用模型即可关闭此备用路线。
- 接入商品设计、样图、去水印、视频图片和助手图片调用。前置水印检测及队列行为未改，没有添加生成后复核。
- 输出记录真实 `grok-imagine-image-2.0` 模型 ID。运行进度跨主备累计请求次数，切换阶段显示“切换备用生图模型”。备用失败会带有已经切换的说明。
- 视频待下载结果按实际模型选择密钥；Grok 配置缺失时保留已收到的结果并提示恢复配置，不改用主模型 Key 下载。

## 验证

1. 新 Key 的模型列表请求返回 HTTP 200，精确模型 ID 可见。
2. 实际 Grok 图生图调用一次，使用自制蓝色圆形参考图和原有 multipart `image` / `input_fidelity=high` 参数。11.4 秒返回 HTTP 200；成功下载 90,580 字节 JPEG，解码为 1024×1024，目视保留蓝色圆形并更换浅灰背景。没有修改商品记录或发起批量生图。
3. Java 相关回归 84 项通过，覆盖配置隔离、备用独立 Key、请求透传、结果 modelId、备用结果下载、原水印预检测流程。
4. Node 及图片进度相关测试共 84 项通过，包含使用真实请求构造函数和 multipart 编解码的 HTTP 契约测试。
5. 部署后的运行中图片接口，使用本机模拟上游触发 HTTP 400 版权拒绝，验证恰好两次 POST、备用独立 Key、原图及描述不变、实际结果模型为 Grok。64 字符任务 ID 的进度返回 `RESPONSE_RECEIVED`、attempts=2。此项未调用外部付费模型。
6. 已部署 app、pi-runtime 及进度文案；应用健康检查 HTTP 200 / UP，前端 HTTP 200，数据库中的主模型仍默认、备用仍非默认。前端部署保留原运行版本的其他静态文件及 nginx 配置。

这证明备用接口可用、自动切换线路生效。蓝色圆形测试不是去水印质量评估，也没有声称对版权拒绝的每张商品图都一定生成成功。

## 记录

- [配置检查](/Users/heyi/Documents/ChatGPT/code-ai-pla/output/grok-image-fallback-20260909/configuration-check.json)
- [供应商真实图生图测试](/Users/heyi/Documents/ChatGPT/code-ai-pla/output/grok-image-fallback-20260909/provider-smoke.json)
- [部署检查](/Users/heyi/Documents/ChatGPT/code-ai-pla/output/grok-image-fallback-20260909/deployment-check.json)
- [备用路由模块](/Users/heyi/Documents/ChatGPT/code-ai-pla/pi-runtime/src/image-model-fallback.js)

最终应用 JAR SHA256：`67ba6eeaa6557b135258c9f9b3a37e927d14c331e79092b25353ff71faeb0fca`。本次未自动重新提交历史任务；后续正常生图请求按上述路线执行。
