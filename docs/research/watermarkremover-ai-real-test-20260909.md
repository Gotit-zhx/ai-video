# WatermarkRemover-AI 商品图实测

日期：2026-09-09。结论：模型可以运行并完成局部修补，但本次商品图效果不合格，不能将原版流程直接替换到现有商品去水印业务。没有切换线上引擎，没有调用付费生图接口，没有修改商品记录。

## 样本与方法

测试三张真实采集的 1024×1024 商品原图（粉色、白色、灰色猫咪 T 恤），均有横跨衣服和图案的半透明 SUKISILK 叠加水印；另加一张用户已确认无叠加水印的蓝猫图作为负例。蓝猫图来自旧生成结果，只用于验证误擦，不是粉色商品恢复的标准答案。

上游提交：`2a15d4d51a483a9fdc8437ec52b1146319d9bda9`。调用未修改的上游 `detect_only` 和 `process_image_with_lama`，按原版整数坐标与面积判断生成整块矩形蒙版，保持原版 RGB/BGR 转换。输出使用 PNG；输入数组复制，避免 IOPaint 裁剪原地写入污染对比。

执行了两组全自动测试，每组四张，以及一张人工位置诊断，共九次输出：

| 组别 | 定位提示词 | 最大单框面积 | 目的 |
|---|---|---:|---|
| baseline | `watermark` | 10% | 原版默认参数 |
| targeted | `SUKISILK watermark` | 100% | 检查指定文字、放宽面积后是否改善；两项同时变化，不作单因素归因 |
| manual（仅粉色） | 人工已知框 `[0,495,1000,585]` | 不适用 | 隔离检查 LaMA 修补能力，不是自动识别，也不是精确字形蒙版 |

## 视觉结果

| 样本 | 默认自动结果 | 调参结果 |
|---|---|---|
| 粉色 | 横向大水印保留，主猫下方 MiowTime / SukiSilk 实体刺绣及爱心被抹掉 | 检测框和输出 PNG 与默认完全一致 |
| 白色 | 横向大水印保留，同样误删下方实体刺绣及爱心 | 检测框和输出 PNG 与默认完全一致 |
| 灰色 | 横向大水印保留，同样误删下方实体刺绣及爱心 | 检测框和输出 PNG 与默认完全一致 |
| 无水印蓝猫对照 | 误删原有蓝色 MiowTime / SukiSilk 文字及爱心 | 框略有变化，仍误删原有图案 |

默认框分别为粉色 `[391,705,642,788]`、白色 `[385,728,640,813]`、灰色 `[387,722,643,807]`、负例 `[421,586,622,655]`。所有框面积仅约 1.3%–2.1%，均被接受。因此这次失败的直接原因是**定位把实体刺绣当成水印**，不是超过默认 10% 阈值被跳过。原图、原尺寸结果以及独立检查均确认了这一点。

人工指定粉色图中真实水印所在的宽矩形后，横向水印明显消失，纯色布料修补较自然；但袖子小猫的帽子、眼睛和脸几乎被擦掉，主猫嘴部、下巴和胸部被拉长重构，部分胡须和尾巴上缘失真。区域外的文字和背景保持不变。这张图仍不适合商品展示。

![粉色图的原图、默认自动处理、人工框修补对比](/Users/heyi/Documents/ChatGPT/code-ai-pla/output/watermark-remover-ai-test-20260909/pink-test-overview.jpg)

上述结果只覆盖三张同款不同颜色的有水印图和一张负例，不能外推所有水印的准确率。人工矩形诊断也不能代表使用精细像素蒙版后 LaMA 的最佳可能效果。没有与现有付费生成模型做同输入的本轮对照，不能据此宣称哪种模型普遍更好。

## 实测性能

Apple M4、16 GiB 内存，原生 Python 3.12.14，CPU，Torch 4 个线程。按上游代码使用 CPU，没有启用 MPS 或 CUDA。单个进程顺序处理，批内复用模型。

| 默认样本 | 检测 | 修补 | 合计（不含加载、文件读写） |
|---|---:|---:|---:|
| 粉色 | 3.938 秒 | 0.843 秒 | 4.781 秒 |
| 白色 | 3.952 秒 | 0.538 秒 | 4.490 秒 |
| 灰色 | 3.960 秒 | 0.425 秒 | 4.385 秒 |
| 无水印对照 | 3.906 秒 | 0.389 秒 | 4.295 秒 |

默认这轮模型加载合计 4.942 秒，进程峰值 RSS 约 4.97 GiB。调参轮单张合计约 4.13–4.75 秒。人工宽框的 LaMA 修补为 2.596 秒，不含自动定位，处理面积也不同，不能与自动流程直接比较速度。所有九次推理均运行完成，但完成状态不等于视觉质量合格。

输出蒙版外变化像素均为 0，证明本次 PNG 输出的未处理区域得以保留；无法证明蒙版内商品图案被正确恢复。没有测试批量吞吐、GPU 性能、视频稳定性或 500 并发。

## 可复现记录

- [样本来源与 SHA256](/Users/heyi/Documents/ChatGPT/code-ai-pla/output/watermark-remover-ai-test-20260909/samples.json)
- [运行脚本](/Users/heyi/Documents/ChatGPT/code-ai-pla/output/watermark-remover-ai-test-20260909/run_evaluation.py)
- [固定依赖](/Users/heyi/Documents/ChatGPT/code-ai-pla/output/watermark-remover-ai-test-20260909/runtime-freeze.txt)
- [模型来源与校验](/Users/heyi/Documents/ChatGPT/code-ai-pla/output/watermark-remover-ai-test-20260909/model-manifest.json)
- [默认结果与耗时](/Users/heyi/Documents/ChatGPT/code-ai-pla/output/watermark-remover-ai-test-20260909/results/baseline/summary.json)
- [调参结果与耗时](/Users/heyi/Documents/ChatGPT/code-ai-pla/output/watermark-remover-ai-test-20260909/results/targeted/summary.json)
- [人工框诊断](/Users/heyi/Documents/ChatGPT/code-ai-pla/output/watermark-remover-ai-test-20260909/results/manual/summary.json)

Florence 使用 `florence-community/Florence-2-large`，固定 revision `4271c66b88cdbc05735372ec13b2360108de5317`，权重 SHA256 `7715423d6549bf1e71188bdd84f4ac960cc0597886af24a5ef7b66f128660685`。LaMA 下载自 `Sanster/models` 的 `add_big_lama` release，SHA256 `344c77bbcb158f17dd143070d1e789f38a66c04202311ae3a258ef66667a9ea9`。模型权重不纳入 Git。

## 对本项目的决定

保留现有线上引擎。若继续研究本地低成本方案，优先解决叠加水印与实体商品图案的区分，以及精确蒙版生成，再用独立样本验证；单纯增大检测框或改这个文字提示词没有解决本次问题。纯色区域小水印仍可作为后续候选场景，本轮尚未验证，不能承诺效果。

保留现有“生成前检测、明确无水印保留原图、判断不清或检测异常交生图、不做生成后复核”的流程。负例误擦来自上游独立全自动测试；若接入平台，前置检测明确判无水印时应当绕过修复，不能把这次负例结果直接当成当前线上行为。此次人工看图属于开发效果评估，没有给用户流程新增复核步骤。
