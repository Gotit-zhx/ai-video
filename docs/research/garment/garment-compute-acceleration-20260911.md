# 服装拟合的跨平台计算加速

> 研究或实验记录；样本、限制及未确认项按文中日期适用，不作为生产能力承诺。 当前入口见[业务说明](../../business/README.md)与[运维指南](../../guides/operations.md)。

本轮把 CPU 批量计算接入现有拟合程序，并验证浏览器 WebGPU 能力。原有已评审模型保持不变，新性能实验输出在 `output/garment-gpu-assessment-20260911/`。

## 已经生效的加速

原先每个候选形状都逐点计算到四条衣身缝线的距离。性能剖析表明，构造低精度拟合网格约 22.4 ms，而三方向投影和误差计算约 4.3 ms；仅替换渲染器不会消除主要瓶颈。

`geometry_v2.py` 新增 NumPy 批量深度计算，分块控制临时内存，使用相同的连续几何公式。原始标量实现保留作对照，基础 `build_mesh` 默认仍使用该实现；`run_upgrade.py` 默认选择批量路径，最终独立检查和 OpenCV 评分保持原样。

同一台 Apple M4、同一初值/相机/参考图、相同预算，顺序运行完整任务：

| 项目 | 原标量计算 | 批量计算 |
| --- | ---: | ---: |
| 实际候选数 | 2,445 | 2,445 |
| 全流程耗时，含最终检查 | 61.546 秒 | 28.774 秒 |
| 三阶段所有候选轨迹 | 与右侧相同 | 与左侧相同 |
| 最终三方向参考轮廓掩码 | 逐像素一致 | 逐像素一致 |

本机完整流程提速 **2.139 倍**。顶点最大数值差约 `8.33e-17`，拓扑一致。该速度来自 CPU 优化，没有调用 GPU 或外部生图。一次配对运行不能代表所有设备的速度；它证明当前公式不必逐点慢算，也没有以改变轮廓换速度。完整原型测试 84 项通过。

证据：`output/garment-gpu-assessment-20260911/cpu-comparison/comparison.json`，包含两次完整输出、候选日志和环境。`evidence-audit.json` 另核对了两次计算模块、相机、掩膜和实际初值一致。复现程序拒绝覆盖非空输出目录，并阻止缺失掩膜或拓扑差异误报成功。独立复现使用空输出目录：

```bash
python experiments/garment-reconstruction/benchmark_acceleration.py --output-dir output/garment-speed-check
```

日常使用的快速路径：

```bash
python experiments/garment-reconstruction/run_upgrade.py \
  --initial-params output/garment-upgrade-20260911/resume-params.json \
  --coarse-budget 800 --volume-budget 900 --refine-budget 1200 \
  --depth-backend numpy --output-dir output/garment-fast-new-run
```

## 通用 GPU 路径

使用标准 **WebGPU + WGSL**，由浏览器选择底层图形驱动，不绑定 Apple API 或 NVIDIA CUDA。Windows 的 Chrome/Edge、Mac 上支持 WebGPU 的浏览器都可使用同一份计算模块；能否启用依赖浏览器、操作系统、驱动及实际适配器。核显、集显和独显都按同一能力检测路径处理，不根据显卡名称作硬编码分支。

| 环境 | 计算策略 |
| --- | --- |
| WebGPU 适配器/设备可用 | GPU 绘制多视图并归约误差；只回读分数 |
| 不支持 WebGPU、驱动拒绝或设备丢失 | 返回明确状态，调用方使用保留的 CPU 拟合路径 |
| 较弱设备 | 使用较少候选、低分辨率粗拟合；最终仍运行高分辨率检查 |

设备能力不能靠操作系统字符串推断。正式接入时需要在 HTTPS 或 localhost 下检测 `navigator.gpu`、`requestAdapter`、`requestDevice` 与实际 limits，并处理 `device.lost`；设备切换后旧 GPU 分数不能继续使用。本轮保留独立验证入口，尚未将完整 Python 优化循环迁移到浏览器。

本机已实际检测到 Apple M4 的非软件 WebGPU 适配器。当前 12,306 顶点、24,384 三角形模型，在 512×512 分辨率绘制 10 个方向，预热后 20 批测得：

- GPU 时间戳覆盖的绘制跨度中位数约 0.459 ms。
- 从提交到 GPU 完成的浏览器等待中位数约 1.30 ms。
- 另外回读 10 MiB 像素约 0.80 ms，所以后续归约应留在 GPU。

这些是渲染阶段的实测，不是完整建模耗时；Windows 和其他显卡未在本机实测。OpenCV 与 GPU 的边缘覆盖规则不同，当前约相差 1–2% 前景像素，不能将新渲染器的分数直接冒充旧基线分数。最终质量检查继续使用冻结的旧评分协议。

### 已写入的 GPU 评分模块

`experiments/garment-reconstruction/gpu-silhouette.js` 提供 `createGpuSilhouetteEvaluator`，接收顶点、三角索引、固定相机和目标掩膜；`evaluate` 可上传同拓扑的新候选，执行绘制与两级 compute 归约；`destroy` 释放设备资源。它计算 IoU 和归一化像素异或误差，暂不提供轮廓 P95、距离场损失、布料模拟或优化器。异常返回可读状态；独立页面不会擅自另起 CPU 拟合任务。

实际浏览器验证页面：`/garment-reconstruction/gpu-benchmark.html`。相同复杂模型、512²、10 个方向，绘制、归约、回读分数的稳态耗时中位数 **4.30 ms**，范围 1.7–8.1 ms；每个候选仅回读 **320 B**。五个顺序上传的内存候选中位共 26.60 ms，每次顶点上传 147,672 B。这里没有并行候选优化或收敛速度测试，不能换算成完整拟合倍数。

同一张 GPU 掩膜上的 CPU/GPU 整数计数，10 个方向全部精确一致，浮点分数最大差约 `3.70e-8`。空目标、全目标、完全出画面候选和 NaN 输入也作了检查，空并集 IoU 为 0。证据在 `output/garment-gpu-assessment-20260911/gpu-compute-poc-benchmark.json`。这一步验证计算模块正确性；目标使用既有 CPU 渲染掩膜，未据此声明真实照片拟合精度提升。

## 同时提高精度的方向

加速后仍需扩展几何和目标函数。当前只有 18 个形状参数，主要匹配外轮廓；没有在目标里表达的缝线和真实褶皱，增加显卡算力也不会自动出现。

后续结构应分三层：先用可编辑衣片及明确缝合关系确定领口、袖口、衣片连接；再用多视图轮廓、局部关键点、缝线和可靠的表面方向约束拟合；最后用布料约束求解器调整局部垂坠与褶皱。参考不足的局部可使用已授权的生图功能补充设计目标，并保存其来源。模拟产生的是满足约束的合理形态，生成的参考视图仍需检查跨视角一致性。

具体开源方法与一手来源见 [技术核查](garment-gpu-method-research-20260911.md)。本轮已实现计算加速，不将尚未加入的衣片求解、布料仿真或局部特征拟合标为完成。
