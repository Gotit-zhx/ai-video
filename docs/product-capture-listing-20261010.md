# 商品采集标题与刊登模板

2026-10-10 发布。新采集商品在后端调用 Pi Agent SDK 自动处理标题，模板仅管理详情文案和包裹规格。

## 采集标题

- `ProductCaptureService.create` 调用 `ProductListingTitleService`，使用当前公司的驱动模型。
- Pi 服务通过真实 `@earendil-works/pi-agent-core` 的 `Agent` 调用模型，无工具、无自动模型重试。
- 标题依据原标题、原类目与属性生成，保留 `normalizedData.originalTitle` 和原始采集请求。
- 成功记录 `titleAi.status=SUCCEEDED`；模型不可用、配置异常或结果不完整时保留原标题及采集成果，记录 `FAILED`。
- 商品详情支持单件生成预览，核对后再保存；线上验收的预览结果未写入历史商品。

## 模板和类目

- `/api/v1/product-listing-templates` 按公司及平台保存，使用版本校验防止并发覆盖。
- 当前公司的虾皮通用模板已保存为版本 1：200 g，5 × 25 × 25 cm，CurveLuxe PH 详情文案。
- TikTok 默认不启用模板，沿用原始详情；两个平台独立保存。
- 刊登工作流使用服务器生成的模板快照，同一任务重试不读取新配置。
- 采集转换保留原始详情、原重量、SKU、价格、图片和类目。
- 类目匹配读取原始标题和原始详情；`Neckline: Others` 表示未指定，不再与明确的 `Collar: Round neck` 冲突。明确领型冲突仍然阻止匹配。

## 验证

- 前端产品测试：154 项通过。
- 后端采集、模板、标题传输、工作流及 Shopee/TikTok provider 测试：152 项通过。
- Pi SDK 标题与招聘分析传输测试：10 项通过，使用本地 SSE fixture 验证真实 SDK。
- V107 在与正式数据库相同的字符集规则下创建隔离测试表，验证真实组织外键后删除测试表；正式 Flyway V107 成功。
- 三件真实报错商品 fixture 均能匹配测试 taxonomy 的 T-shirts 可执行叶子类目。
- 线上真实模型生成成功：
  `Sukisilk Zootopia Judy Hopps Embroidered Women's Loose-Fit Short-Sleeve T-Shirt`。
- 浏览器验证了模板保存、平台切换、固定参数、完整文案及商品预览；未提交真实平台刊登。

## 发布

- Java 和 Pi：`capture-templates-20261010`。
- 前端：`capture-templates-ui-20261010`，修正标题输入框与生成按钮的标签关联。
- 在正式镜像上只覆盖本次文件和 Java 类；其他 JAR 内容逐项验证未变。
- 保留环境变量、挂载、其他容器和 BOSS connector；发布前确认无进行中的业务任务。
- 旧前端 Compose 配置要求 `HR_FRONTEND_IMAGE`；发布脚本已显式提供该变量，并保证所有回滚动作都会尝试执行。
- 服务器审计与回滚目录：`/opt/heyi/releases/capture-templates-20261010`。保留原 JAR、旧镜像及发布 manifest，清理本轮编译和暂存文件。

## 店铺物流配置

同日追加发布 `capture-logistics-20261010`。在“采集链接 → 定制模板配置 → Shopee”的包裹规格下选择目标店铺，可读取官方渠道并同时启用多条物流。

- 配置按当前公司和目标店铺保存，支持“跟随店铺所有已开通渠道”及“只启用勾选的渠道”；未配置时默认前者。
- CurveLuxe PH 的官方接口实际返回三个已开通渠道。已按用户截图保存 `SELECTED` 版本 1：Shopee Self Pick-up（40029）和 Standard Local（4000）；额外的 SPX Express（40066）未勾选。
- 渠道 ID 来自目标店铺官方接口。运费、渠道限制由平台处理，不将截图的 ₱0 / ₱32 写成固定价格。
- 单件与批量上传共用选择规则。服务器记录物流快照，并清除客户端传入的快照与物流列表；同一任务重试复用原快照。
- 创建商品前再次读取官方渠道，所选渠道已停用或属于其他公司、店铺时阻止刊登。旧任务没有物流快照时保持原有请求，不更改其历史选择。
- TikTok 模板独立保存，不显示或应用 Shopee 物流设置。

验证：前端商品测试 158 项、后端相关测试 142 项通过。测试覆盖多个渠道同时开启、未选渠道关闭、停用及外店渠道拒绝、权限隔离、并发版本冲突、任务重试和实际 mock `add_item.logistic_info`。V108 在真实 MySQL 中使用隔离测试表验证外键，测试表已删除；正式 Flyway V108 成功。

浏览器验证了官方渠道读取、按截图保存、重新打开持久化、平台切换保留选择及上传入口可用；未提交真实商品刊登。只覆盖本次 Java 类、迁移及四个前端文件，其他 JAR 内容和容器保持不变，Pi 服务无需重启。审计和回滚目录为 `/opt/heyi/releases/capture-logistics-20261010`，编译产物及暂存文件在验收后清理。
