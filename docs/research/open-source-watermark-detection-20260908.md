# 商品图可见水印检测：GitHub 候选核验

核验日期：2026-09-08。目标是减少逐图调用远程大模型的时间和费用，仍保留服装印花、刺绣、商标及尺码标签。下列候选尚未在本项目两件商品的全部图片上测出准确率或耗时。

## 优先验证：专用 YOLOv11 水印权重

- GitHub：https://github.com/lrzjason/ComfyUI-Watermark-Detection
- 原始作者/模型入口：https://huggingface.co/spaces/fancyfeast/joycaption-watermark-detection
- 仓库使用的权重：https://huggingface.co/lrzjason/joy_caption_watermark_yolo
- nodes.py 明确下载 `yolo11x-train28-best.pt`，以 `imgsz=1024, augment=True, iou=0.5` 推理。置信度阈值可调，默认 0.5。
- 输出检测框与二值掩膜。源码中的掩膜是检测框填充的矩形，**不是逐像素水印分割**，不能把整个框直接视为可以任意重绘的商品区域。
- 可以抽取推理代码做独立检测服务，无须引入整个 ComfyUI。检测仅在本地进行，不需要逐图付费调用远程大模型；硬件耗时仍需实测。
- 封装代码 LICENSE 为 MIT；Ultralytics 依赖和预训练权重应分别核验许可，不能将封装的 MIT 当作整个模型链路许可。

## 备选：SLBR 定位分支

- https://github.com/bcmi/SLBR-Visible-Watermark-Removal
- ACM MM 2021 官方实现，包含水印定位与背景修复，README 提供 CLWD 预训练权重链接及自定义图片测试入口。
- README 指出训练尺寸为 256×256，建议自己的数据微调。适合作为精细定位研究候选，不宜把其整图修复结果直接代替商品原图。
- 仓库根目录未显示明确 LICENSE，商用集成前需要进一步确认。

## 固定重复水印的传统方法

- https://github.com/rohitrango/automatic-watermark-detection
- 利用一批图片中重复水印的梯度中值、Canny/Chamfer 匹配进行估计与定位。
- 适合同一店铺大量同款式、相近位置的水印，不适合任意单张商品图。README 示例使用 450 多张图片，并明确表示实现较粗糙、去除可能产生纹理失真；标注学术/研究用途。

## 排除直接接入的示例

https://github.com/santifer/watermark-remover 的默认 detector.py 加载普通 `yolov8n.pt`，没有默认加载专用水印权重，并把所有检测类别加入掩膜。CLI 还默认在没有检测结果时修补角落。这不能作为本项目可靠的水印检测器，不能直接启用默认去水印流程。

## 建议验证流程

1. 先只做检测，原图不改、不自动发布；覆盖大半透明水印、角标、无水印图、服装刺绣/文字和尺码标签。
2. 输出框、置信度和叠加预览，人工标注真实水印，测漏检及把商品图案误判为水印的情况。阈值由真实样本决定，不能把默认 0.5 当作已校准值。
3. 将本地检测用于筛选疑似区域，仅把疑似或边界不清的图片交给 Grok 复核。没有检测框不等于已证明没有水印。
4. 确认水印后才做局部修复；修改区域外保持原图像素；范围覆盖印花/刺绣或结果不确定时停住。保真复核仍独立保留。


## 本地检测实测更新（2026-09-08）

已运行 JoyCaption YOLO11x 权重（SHA256 `80b022e94239a65a3b50173096523f202ca39608b9063dd391d043323a51326e`）。三张样本均被标记 SUSPECTED：原商品图只框出右上书本文字，漏掉中央 SUKISILK 半透明水印；独立刺绣图误报刺绣文字；旧蓝猫图误报衣服文字和书本文字。CPU 耗时约 13.6–23.1 秒。证据：`output/watermark-local-qa/benchmark.json`。

当前权重不能满足本项目的自动筛选要求。local-enabled 保持 false，未部署 V80；源码已取消 NO_CANDIDATE 自动放行，即使开启本地检测仍需视觉复核。不能宣称已节约模型调用或跑通两件商品发布。下一步需要有真实标签的商品样本评估专用定位模型或微调，再决定是否启用筛选。


## 自写检测器更新（2026-09-08）

实现 `watermark-detector/Dockerfile.custom`：OpenCV 多尺度双片段匹配，候选定位使用已标注 SUKISILK 水印指纹。原图横向水印被定位，独立刺绣图和已去水印蓝猫图无匹配；这是同源校准与负例回归，不能当作独立准确率。缩放、平移、亮度和 JPEG 变体回归已覆盖，未知水印仍需 Grok。几何/OCR 试验没有达到可用水平，未纳入最终引擎。

用户明确确认蓝猫图无叠加水印，已记录该文件精确 SHA256；仅同一文件可跳过有无水印的视觉检测，保留图案且不生成新图。该确认不改变蓝猫图相对原粉猫图的形态复检失败结果。一般 NO_CANDIDATE 仍不自动放行。候选框只作为 Grok 上下文，不能直接作擦除蒙版。

源码及自写 Docker 镜像已完成；主应用 local-enabled 仍关闭，V80 未部署，尚未发布两件商品。详见 `watermark-detector/README.md` 和 `output/watermark-custom-qa/benchmark.json`。
