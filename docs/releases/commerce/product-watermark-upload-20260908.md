# 批量商品去水印上传：用户要求与实施状态

> 历史发布记录；镜像、测试与“当前/最新”均指记录当时。 当前入口见[业务说明](../../business/README.md)与[运维指南](../../guides/operations.md)。

用户要求勾选两个商品，一次启动去水印及上传；每张图片先经大模型判断是否存在叠加水印，仅有水印才处理，不能破坏原商品结构和形态。此流程不做印花二创，不应用此前允许印花裂变的规则。

## 图像处理合同

1. 固定选中商品、来源版本及完整待上传图片集（包含 SKU 图），保留原图与内容哈希。
2. 使用视觉模型逐图检测：PRESENT、ABSENT、UNCERTAIN。衣服印花、刺绣、尺码标签、品牌和实物文字不等同照片叠加水印。检测失败不能按无水印放行。
3. ABSENT 使用原图字节，无编辑模型调用。PRESENT 使用明确且有限的水印范围作局部编辑；框外原图像素保留，禁止整图重绘作为最终结果。覆盖过大的区域进入复核。
4. 再以原图和候选图双图复检水印残留、商品轮廓、结构形态、比例、颜色材质、纹理、印花文字及标签。不确定或不通过就暂停该商品，不能自动上传该候选。
5. 每图持久化检测/编辑/复检调用及结果；提交未知不重复付费调用。两个商品各自记录进度，单项失败不重跑已成功项。
6. 发布前确认目标店铺、站点/币种、类目、必填属性、SKU价格库存及物流参数；水印检查成功不等同商品上架资料完整。

置信度阈值仅是保守筛选，不证明绝对保真。局部范围内仍可能产生结构变化，必须双图复检。原始文件必须保留。

## 本轮已写代码

- services/pi-runtime/src/product-watermark-analysis.js：独立叠加水印检测与双图复检提示词、结构化结果检查、矛盾字段拒绝、低置信度转不确定、过大修复范围待复核。
- services/pi-runtime/src/server.js：/v1/product/analyze 根据 WATERMARK_DETECTION_V1 / WATERMARK_VALIDATION_V1 分派。
- services/pi-runtime/test/product-watermark-analysis.test.mjs：5项针对性测试通过。覆盖无水印、不确定、越界/矛盾框、残留水印、结构变化、缺少复检图片及网络错误不自动重试。
- scripts/qa/test-product-watermark-model.mjs：只读模型验证脚本，逐调用先落盘意图；不调用生图、不写商品、不上架。样本为猫咪原图（叠加水印）、独立印花（文字误判对照）、原图与改过印花的套版（应拒绝的反例）。这些是三个图像判定用例，不是“两商品上传完成”。

## 尚未完成的接入

现有 WatermarkRemovalRegistry 只有 DryRunWatermarkRemovalProvider；新分析能力尚未接入正式图片处理提供方、批量勾选或上传工作流，也未部署 runtime。当前上传代码还把 processedUrl 等于 originalUrl 视为 Dry Run，后续必须改为持久化处理来源证据，才能正确支持“检测无水印、原图保留”。当前 APPROVED 商品会跳过图片处理，批量新流程必须避免由旧 Dry Run 结果绕过检测。

现有局部编辑器 CommerceArtworkLocalEdit 使用1024方形归一化；不能宣称它保持任意原图尺寸和形态不变。此任务需要原尺寸局部合成或明确的可逆坐标映射，并检查框内结构。

## 待用户补充

用户已明确目标：发布到 Shopee MY 沙箱店铺。只授权沙箱测试，不把生产店铺开关一并打开。上一轮本地管理员登录失效仍待恢复；不重置用户密码。现有发布开关保持关闭，待仅限沙箱的执行配置及水印管线验证通过后再启用。

真实视觉分析分别从本机及运行容器的网络调用当前公司默认 DRIVER（gpt-5.4，monotoo.shop），两条路径的三个用例均返回 HTTP 503，未得到任何有效判定。输出记录位于 output/watermark-detection-20260908/；本轮不能宣称大模型检测已实测通过或两个商品已上传。已请求恢复视觉模型线路或提供可用配置。没有生成或修改商品图片、没有发布商品。


## 连接诊断更新：网络正常，原默认型号不可用

用户要求重新核对连接后，读取同一线路 `/v1/models` 得到 HTTP 200，列表包含 gpt-5.5、gpt-5.6-sol、gpt-5.6-terra，不包含默认 gpt-5.4。最小 gpt-5.4 请求返回 HTTP503，正文 error.code=model_not_found；此前只报告503并要求用户恢复线路不够准确。

本次水印测试通过 WATERMARK_TEST_MODEL=gpt-5.5 单次覆盖模型名称，不修改系统全局配置。独立印花用例返回 ABSENT（0.98），将印花自身文字正确识别为应保留内容；原图与改过印花候选的双图复检返回 valid=false、structurePreserved=false（0.99），指出猫咪图案改色及拼接痕迹。带水印原图首次HTTP500，单独重试该用例，已成功的用例不重跑。记录见 output/watermark-detection-20260908/gpt-5.5/container-transport/。

模型连接已证实可用，不再把503作为整个任务的网络阻断。批量选择、原尺寸局部修复、每图持久化及实际Shopee沙箱发布仍需实现与联调。

### 续跑校验（2026-09-08，本轮）
- 修复 ProductCaptureService 逐图续跑分支缺失 Objects import；相关后端测试再次通过。
- ShopeeOfficialClient 增加 `APP_SHOPEE_SANDBOX_ONLY` 写入地址校验：开启后，商品 POST 和图片 multipart 仅允许精确 HTTPS 沙箱主机，拒绝正式站、伪装域名及带 query/userinfo 的地址。默认 false，尚未在运行环境启用；正式发布开关仍未打开。
- application.yml 补齐 `APP_PRODUCT_WATERMARK_MODEL_ID` 配置入口。
- 去水印复核失败也持久化完整 validation_json，便于查明商品结构变化或残留水印。
- runtime 水印分析 5 项测试通过；新增沙箱写入守卫 2 项测试通过。
- 浏览器任务空间 83 仍显示登录页，需要用户本人完成系统登录；未重置密码或绕过认证。
- 本轮未部署，未发生图片编辑或 Shopee 发布。批量勾选入口、全部原图读取、真实含水印图片模型 500、MYR 定价及类目/物流预检仍待完成。

### 用户切换 gpt-5.5 后复测（2026-09-08 15:45–15:48 CST）
- 从当前数据库配置读取默认模型，确认已变为 gpt-5.5，无测试覆盖参数。
- /models HTTP 200；文本 Chat Completions HTTP 200，返回 OK。
- 水印原图 Chat Completions HTTP 502（request id 202609080745253605116998268d9d6F6Z6HnfR）。
- 同图 Responses 格式 HTTP 500 do_request_failed（request id 202609080746409892645918268d9d6Wi370WIU）。
- 无叠加水印的刺绣文字对照图也 HTTP 500 do_request_failed（request id 202609080747448028948268d9d6oedFaiJx）。这次无法复现此前成功的对照图判定，不能把文本连通视为视觉连通。
- 诊断记录位于 output/watermark-detection-20260908/container-transport/user-config-*；未盲目重试相同请求，未进行图片编辑及发布。
- 官方图片输入文档已核对：https://developers.openai.com/api/docs/guides/images-vision；Base64 data URL 与 Responses input_image 格式受支持，但第三方网关具体兼容情况仍未知。
- 增加归档访问隔离测试：跨商品/跨图片、非 READY、跨租户均不能读取候选图；局部合成拒绝模型改变正方形参考画布比例。
- 浏览器 /api/v1/auth/me 实测 HTTP 401，需在任务空间 83 登录。

### 流式诊断与 Grok 默认配置（2026-09-08 16:20 CST）
- 用户明确要求将 grok-4.6 设为默认分析模型。已将商品水印检测/保真复核的专用 `APP_PRODUCT_WATERMARK_MODEL_ID` 设为 grok-4.6；全局 DRIVER 聊天配置未修改。
- 同图非流式请求在 21.536 秒收到完整 HTTP 500，测试客户端超时为 600 秒，因此不是客户端超时导致该次失败。
- gpt-5.6-sol 流在 32.305 秒结束，HTTP 200，但无文本且缺完成标记，按失败处理。
- gpt-5.5 流在 543.835 秒结束，有完成标记但无判定文本，按失败处理。
- grok-4.6 流通过 3 个控制：水印原图 PRESENT .98（38.430 秒）；刺绣文字无水印 ABSENT .98（17.651 秒）；粉猫变蓝猫候选 valid=false .99（25.982 秒）。注意检测框仍须检查覆盖范围，不能把连通成功等同于全部图片处理完成。
- 新增流式读取模块，增量 UTF-8 解码，必须正常 stop + DONE；连接提前关闭、token 截断、空结果一律不通过。收到 DONE 后结束读取，不等待网关关闭 TCP 连接。
- 水印请求最长 600 秒，Java 等待 620 秒；逐图 worker 默认租约 3600 秒。
- 后端打包时 85 项相关测试通过；水印和流式定向测试 10 项通过。额外的既有 product-analysis.test.mjs 有 1 项测试夹具失败（VM 未注入现已被单独模块导出的 GARMENT_TOPOLOGY_VALIDATION_PROMPT），未将该既有测试报告为通过。
- 已备份数据库 /private/tmp/heyi-before-watermark-v79.sql（0600），部署 backend + runtime，V79 迁移成功，服务健康。
- 运行环境：AI_WATERMARK_V1、grok-4.6、APP_SHOPEE_SANDBOX_ONLY=true；发布仍保持 DRY_RUN 和 live=false。双商品发布尚未发生。
- 部署后首测 HTTP 503；发现实际服务 Grok 使用 max_tokens，而成功诊断使用 max_completion_tokens + reasoning_effort=low。已将仅水印流式 Grok 路径对齐成功请求格式并加回归测试，正在复测；尚不能认定参数差异是 503 根因。
- 部署后对齐参数再次测试，runtime 返回上游 503 model_not_found。随后在同一容器用原诊断直连请求也于 1.604 秒返回 503 model_not_found（request id 202609080823518542124048268d9d6fKTuCaSh）。因此当前失败不只发生于服务端实现，网关现时无法路由该型号；不能归因于客户端提前关闭，也不能认定改 token 参数已修复网关。
- 用户要求的 grok-4.6 专用默认值已部署并验证容器配置；模型分析目前受上游路由状态阻塞。未继续重复付费尝试，未进行图片生成或商品发布。


## 本地检测实测更新（2026-09-08）

已运行 JoyCaption YOLO11x 权重（SHA256 `80b022e94239a65a3b50173096523f202ca39608b9063dd391d043323a51326e`）。三张样本均被标记 SUSPECTED：原商品图只框出右上书本文字，漏掉中央 SUKISILK 半透明水印；独立刺绣图误报刺绣文字；旧蓝猫图误报衣服文字和书本文字。CPU 耗时约 13.6–23.1 秒。证据：`output/watermark-local-qa/benchmark.json`。

当前权重不能满足本项目的自动筛选要求。local-enabled 保持 false，未部署 V80；源码已取消 NO_CANDIDATE 自动放行，即使开启本地检测仍需视觉复核。不能宣称已节约模型调用或跑通两件商品发布。下一步需要有真实标签的商品样本评估专用定位模型或微调，再决定是否启用筛选。


## 自写检测器更新（2026-09-08）

实现 `services/watermark-detector/Dockerfile.custom`：OpenCV 多尺度双片段匹配，候选定位使用已标注 SUKISILK 水印指纹。原图横向水印被定位，独立刺绣图和已去水印蓝猫图无匹配；这是同源校准与负例回归，不能当作独立准确率。缩放、平移、亮度和 JPEG 变体回归已覆盖，未知水印仍需 Grok。几何/OCR 试验没有达到可用水平，未纳入最终引擎。

用户明确确认蓝猫图无叠加水印，已记录该文件精确 SHA256；仅同一文件可跳过有无水印的视觉检测，保留图案且不生成新图。该确认不改变蓝猫图相对原粉猫图的形态复检失败结果。一般 NO_CANDIDATE 仍不自动放行。候选框只作为 Grok 上下文，不能直接作擦除蒙版。

源码及自写 Docker 镜像已完成；主应用 local-enabled 仍关闭，V80 未部署，尚未发布两件商品。详见 `services/watermark-detector/README.md` 和 `output/watermark-custom-qa/benchmark.json`。
