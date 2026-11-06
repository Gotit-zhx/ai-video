# 服装几何 v2.1：升级与复测

> 研究或实验记录；样本、限制及未确认项按文中日期适用，不作为生产能力承诺。 当前入口见[业务说明](../../business/README.md)与[运维指南](../../guides/operations.md)。

日期：2026-09-11。几何升级已实现；**正面、背面通过原轮廓门槛，右侧仍未通过，完整服装还原未验收通过**。本轮不包含材质还原。

[查看新版](http://127.0.0.1:8767/garment-reconstruction/viewer.html?version=2) · [查看 v1](http://127.0.0.1:8767/garment-reconstruction/viewer.html?version=1)（需要本机 HTTP 服务运行）

## 实际改动

- 肩部改为 Hermite 曲线，肩线与上袖边在投影中共用切线，减少折角；这不等于完整三维曲面达到 G1 连续。
- 袖口平面随袖子轴向倾斜，袖根继续与衣身共享顶点，保留领口、两袖口、下摆四个真实开口。
- 胸、腰、下摆分别控制体积，增加收腰、下摆展开和身体倾斜；袖子参数修改不会移动任何衣身顶点。
- 用参考图领口标注固定宽度、前领深度，增加局部前后位移，避免轮廓优化把领口缩小。
- 新增袖子与衣身三角面求交检查，并用实际向内翻折的袖子验证检查器会拒绝错误网格。
- 拟合改为保留可行当前解的坐标搜索，防止狭窄可行区中的失败候选影响后续维度；记录所有唯一尝试参数。
- 查看器支持新版/旧版、灰模/白线框、前后左右/俯视和拖动。切换版本保持同一相机与显示尺度。

最终预览网格为 **5630 顶点、11040 三角面**，版本 `structured-panels/0.2.1`。粗拟合使用 1022 顶点、1952 面。

## 用同一目标对比

基线是上一轮已经拟合完成的模型。参考图、512 像素掩膜、近似正交相机、投影算法和阈值均沿用 v1；重新计算的基线分数与上一轮一致。

| 视角 | v1 IoU | v2.1 IoU | v1 边界 P95 | v2.1 边界 P95 | 原轮廓门槛 |
| --- | ---: | ---: | ---: | ---: | --- |
| 正面 | 0.86267 | 0.97329 | 0.06330 | 0.01194 | 通过 |
| 背面 | 0.86485 | 0.97430 | 0.06596 | 0.01356 | 通过 |
| 右侧 | 0.80444 | 0.94953 | 0.05506 | 0.01835 | 未通过 |

门槛为每视角 IoU ≥ 0.95，边界 P95 / 目标框对角线 ≤ 0.015。右侧的 **0.94953 小于 0.95**；对照图标题保留三位小数，舍入显示 0.950 不代表通过。

IoU 只衡量投影外轮廓重叠，不是还原率。三张参考都是此前 AI 生成的目标图，全部参与拟合；没有独立留出的实拍验证图。掩膜依赖奶油色衣物与白背景的颜色分割，并填充内部区域，不能验证褶皱、领边内壁或布料。

![同一参考下，左侧为上一版，右侧为最终升级版](/Users/heyi/Documents/ChatGPT/code-ai-pla/output/garment-upgrade-20260911/real-comparison.png)

## 领口约束为什么必要

中间版本仅优化外轮廓时，三个视角的轮廓门槛都能过，但领口宽被缩到 0.18，明显偏离参考，因此没有选用。

最终版依据 1024 像素正面图的视觉标注：左点 `(424,100)`、右点 `(623,100)`、前领低点 `(522,169)`，估计标注误差 12 像素。在冻结相机尺度下固定领宽为 **0.29444839**、前领深度为 **0.10209517**，允许领口局部 Z 位移。

这些是助手对 AI 图的视觉标注和相对尺寸，既非实物测量，也非已实现的通用自动关键点提取服务。保留正确领宽后，侧面分数低于那个缩领口的中间版本；报告和页面采用最终真实结果。

## 验证证据

**57 项自动测试通过**，覆盖原有几何/结构检查、新几何局部性、可行域搜索及三角面求交。最终源码、参考、相机、网格和报告哈希已交叉核对，按最终参数重建得到相同网格。

正常模型的袖身候选面经过包围盒筛选和共享顶点邻域豁免后，实际求交 **126 对，0 对相交、0 对未决**。对同一最终网格制作向内翻袖负例，保留拓扑和袖根，实际检测 1491 对，其中 **190 对相交**，命令按预期非零退出。

碰撞只覆盖袖子与衣身的非共享索引三角面对。共享顶点邻域、袖子自身相交、袖袖相交、衣身自身相交、完整体积包容、布料厚度和动态碰撞未覆盖；无相交报告不能扩大解释为完整衣物碰撞安全。

真实浏览器使用最终网格和冻结相机进行独立 WebGL 投影。WebGL 与软件掩膜 IoU 分别为 **0.99179 / 0.99195 / 0.99048**；WebGL 对目标为 **0.96744 / 0.97119 / 0.94809**，记录了栅格边界差异。显示网格与文件一致，灰模、白线框、四个开口、视角切换和拖动已检查。

![最终灰模预览](/Users/heyi/Documents/ChatGPT/code-ai-pla/output/garment-upgrade-20260911/browser-gray.png)

- [完整摘要与最终参数](/Users/heyi/Documents/ChatGPT/code-ai-pla/output/garment-upgrade-20260911/summary.json)
- [领口观测记录](/Users/heyi/Documents/ChatGPT/code-ai-pla/output/garment-upgrade-20260911/collar-observations.json)
- [结构报告](/Users/heyi/Documents/ChatGPT/code-ai-pla/output/garment-upgrade-20260911/real-after-structure.json) · [袖身求交报告](/Users/heyi/Documents/ChatGPT/code-ai-pla/output/garment-upgrade-20260911/real-after-collision.json)
- [翻袖负例报告](/Users/heyi/Documents/ChatGPT/code-ai-pla/output/garment-upgrade-20260911/defect-inward-sleeve-collision.json)
- [浏览器投影核对](/Users/heyi/Documents/ChatGPT/code-ai-pla/output/garment-upgrade-20260911/browser-raster-check.json) · [相机和交互核对](/Users/heyi/Documents/ChatGPT/code-ai-pla/output/garment-upgrade-20260911/browser-framing-check.json)

## 复现与当前边界

从项目根目录执行本次最终实验命令：

```bash
/tmp/heyi-watermark-test-env/bin/python experiments/garment-reconstruction/run_upgrade.py \
  --initial-params output/garment-upgrade-20260911/resume-params.json \
  --coarse-budget 800 --volume-budget 900 --refine-budget 1200
```

本机最终一轮约 **62.12 秒、2445 次目标评估、0 次外部 AI/三维 API 调用**。此前尝试不包含在该耗时中；该结果不代表生产吞吐量。依赖和测试命令见 [README](/Users/heyi/Documents/ChatGPT/code-ai-pla/garment-reconstruction/README.md)。

中间实验保存在输出目录的 `runs/`，原 v1 结果保留。重跑会覆盖升级目录的同名文件；这些是实验文件，尚非生产不可变存储。

剩余问题包括右侧边缘误差、未标定的相机、真实领边和布料厚度、褶皱与 PBR 材质、真实尺寸、UV/印花/导出验收及生产接入。相机假设也可能影响侧面误差，目前不能把偏差全部归因于几何。最终 `real_garment_accepted` 保持 **false**。
