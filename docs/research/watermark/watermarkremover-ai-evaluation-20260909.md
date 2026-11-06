# WatermarkRemover-AI 替代现有去水印的评估

> 研究或实验记录；样本、限制及未确认项按文中日期适用，不作为生产能力承诺。 当前入口见[业务说明](../../business/README.md)与[运维指南](../../guides/operations.md)。

核验日期：2026-09-09。上游 https://github.com/D-Ogi/WatermarkRemover-AI ，核验提交 `2a15d4d51a483a9fdc8437ec52b1146319d9bda9`（2026-03-29）。本文记录源码、依赖、许可证和接入方式的静态评估。同日已追加隔离环境实测：[商品图实测报告](/Users/heyi/Documents/ChatGPT/code-ai-pla/docs/research/watermark/watermarkremover-ai-real-test-20260909.md)。两组自动测试均误擦商品刺绣，人工指定水印位置后仍损伤猫咪图案，本批样本不支持直接替换。未切换现有线上引擎。下文标注待验证的其他场景仍未测试。

## 判断

可以抽取推理能力接入，适合作为简单叠加水印的本地修复候选；目前证据不足以支持直接全面替换。它仍是AI：Florence-2视觉模型定位，LaMA神经网络修补。自托管可减少逐图生图API费用，但仍有推理算力、前置检测及运维成本。不能据README演示断言比当前方案更快、更准或完全保留服装纹理。

## 上游实际实现

- `remwm.py:74–126` 用 `<OPEN_VOCABULARY_DETECTION>watermark` 生成检测框，并将每个框整个填白作为修复蒙版；没有逐像素水印分割。`detect_only:129–157` 返回框、面积占比、是否被面积阈值接受，没有本项目意义上的已校准水印置信度。
- `remwm.py:567` 默认最大框面积10%；单个框超过整图10%就跳过。这可能漏掉横贯衣服的大水印；简单提高阈值又会扩大重绘区域。
- `remwm.py:159–173` 调IOPaint的LaMA局部修复，采用CROP策略，margin64、crop trigger800、resize limit1600；ldm_steps配置不等于LaMA实际要运行50步扩散。
- 图片处理先convert RGB（527行），默认保存格式按源扩展名，没有显式在本仓库中将输出蒙版外像素与原图强制复合；集成时需由平台保证区域外保留，并避免无水印图重新JPEG压缩、透明图片丢失alpha。
- 视频支持逐帧检测和修复、抽帧检测、淡入淡出掩膜延展、FFmpeg音频合并。两遍处理通过跨帧复制/扩展矩形框实现，不是专门的时序视频修复模型；运动场景可能闪烁，应单独评估。
- CLI/GUI均可调用，但没有现成生产HTTP服务、持久任务队列或多租户隔离。文件夹批次串行且批内复用模型；单张调用CLI会每次冷启动模型。部分错误路径只return，不能仅凭100%进度认定成功。

## 与你的商品适配

| 场景 | 评估 |
|---|---|
| 纯色背景、角落小水印 | 值得优先试验，局部修复具有结构优势，实际效果待样本验证 |
| 叠在织物褶皱、印花、刺绣上的半透明水印 | 关键风险：整块矩形会覆盖真实图案；被遮挡内容只能估计，不能保证原样恢复 |
| 重复、斜向、大面积水印 | 可能漏检或被面积阈值过滤；公开issue也有类似用户反馈，未在本项目复现 |
| 无水印但服装自带文字或logo | 需要验证误检，不能把“watermark”提示词当成保护商品图案的保证 |
| 商品短视频 | 可作为后续候选，耗时及跨帧稳定性与图片分开评估 |

公开问题参考：[依赖冲突#48](https://github.com/D-Ogi/WatermarkRemover-AI/issues/48)、[MPS#67](https://github.com/D-Ogi/WatermarkRemover-AI/issues/67)、[斜向文字#38](https://github.com/D-Ogi/WatermarkRemover-AI/issues/38)、[网格水印#31](https://github.com/D-Ogi/WatermarkRemover-AI/issues/31)。这些是用户报告，不等同本项目测得的故障率。

## 当前平台与最小接入方式

当前`AiWatermarkRemovalProvider`先远程检测：ABSENT且置信度>=0.9、没有检测框，直接保留原图；PRESENT、UNCERTAIN和检测异常均交图片编辑模型；没有生成后视觉复核。当前生成请求传整图参考及提示词，未给生图模型传蒙版。

建议继续保留前置检测和现有用户流程：明确无水印保留原图；定位适合局部修复的走LaMA；判断不清、没有可靠蒙版或本地推理失败的仍走原生图模型。不增加线上生成后复核。正式切换前的小样本人工效果比较属于开发验收，不是每张图的新业务步骤。

实施点：

1. 抽取LaMA推理和必要Florence定位代码为常驻Python服务，模型启动一次，固定依赖、权重版本和SHA。保留现有Java状态、进度动效、队列、失败重试和结果归档。
2. 在`AiWatermarkRemovalProvider.java:68`编辑阶段增加可切换引擎，不直接替换共用`CommerceDesignImageGateway`，以免影响设计/生图其他业务。当前`AI_WATERMARK_V1`在队列和发布流程有硬编码，新provider code需要一并改造路由。
3. 蒙版应精确到叠加内容，保护商品原有图案；输出只复合指定修复区域，其他区域取原图。现有`/compose`仅处理已有候选的融合，不是LaMA替代品。当前主provider虽然注入LocalWatermarkDetector却未调用，本地开关开启也不代表当前去水印已经走本地。
4. 结果缓存键加入引擎、模型版本、配置和蒙版摘要，收紧`ProductWatermarkLedger.reuseReceivedGeneration`的跨引擎复用，防止测试本地引擎却拿到旧生图缓存。
5. 平台最多500个任务并行槽位与持久排队保留，本地模型要增加按设备承载能力设置的执行上限。不能把现有500个异步工作线程直接映射为500份本地推理。

## 设备和部署

当前主机Apple M4、16GiB内存，Docker Linux aarch64约7.75GiB内存。上游`remwm.py:592/669`只选cuda/cpu，没有MPS分支；虽安装脚本声称Apple Silicon支持，原版推理仍默认CPU。Mac原生MPS适配需单独验证，不能承诺性能；批量生产更适合经过容量测试的NVIDIA GPU常驻服务。

`requirements.txt`大部分版本没有上限或完全未固定；iopaint使用--no-deps后补依赖，脚本还有未引用的>=版本表达式。应重建可重复安装的服务环境，避免直接照搬桌面安装脚本。首次模型下载量与峰值显存不是同一概念，不能以权重文件大小推算500并发能力。

## 许可证

- [本仓库LICENSE](https://github.com/D-Ogi/WatermarkRemover-AI/blob/2a15d4d51a483a9fdc8437ec52b1146319d9bda9/LICENSE)：MIT，允许商用修改及分发，保留版权许可文本。
- [实际Florence模型卡](https://huggingface.co/florence-community/Florence-2-large)：标注MIT，约0.77B参数；不是本仓库针对电商水印微调的专用检测器。
- [IOPaint](https://github.com/Sanster/IOPaint/blob/main/LICENSE)、[LaMA](https://github.com/advimman/lama/blob/main/LICENSE)：源码Apache-2.0。部署时仍应记录实际big-lama.pt发布来源及适用许可；封装MIT不代表所有组件和权重都统一MIT。

## 建议验收

使用当前真实商品的原图做30–50张对比：小角标、横跨服装水印、半透明重复水印、无水印印花/刺绣图，各类都覆盖。保留固定原图和旧模型结果，分别记录水印漏检、误擦商品文字、纹理变化、耗时、峰值内存/显存及单张计算成本。验收后只为通过场景启用本地处理，再决定扩大覆盖；目前不能提供节省百分比或宣称已完成替换。

主要源码依据：[矩形掩膜](https://github.com/D-Ogi/WatermarkRemover-AI/blob/2a15d4d51a483a9fdc8437ec52b1146319d9bda9/remwm.py#L97-L126)、[LaMA调用](https://github.com/D-Ogi/WatermarkRemover-AI/blob/2a15d4d51a483a9fdc8437ec52b1146319d9bda9/remwm.py#L159-L173)、[设备与批处理](https://github.com/D-Ogi/WatermarkRemover-AI/blob/2a15d4d51a483a9fdc8437ec52b1146319d9bda9/remwm.py#L669-L703)。
