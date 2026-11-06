# 服装建模提速与精度：可落地技术核查

> 研究或实验记录；样本、限制及未确认项按文中日期适用，不作为生产能力承诺。 当前入口见[业务说明](../../business/README.md)与[运维指南](../../guides/operations.md)。

核查日期：2026-09-11。结论是先保留已验证的 CPU 提速，再用 **WebGPU 作为 Windows/Mac 共用计算后端**；精度则需要多视图法线、缝线和局部网格自由度。仅把现有 18 参数轮廓拟合搬上 GPU，不会自动长出正确的袖口、内缝、褶皱和布料厚度。

## 已有实测与真正瓶颈

本次短测固定 `real-after.json`，未重新拟合、未修改原网格，总计时 1.57 秒。原 CPU 标量实现的中位耗时如下：

| 工作 | 耗时 |
| --- | ---: |
| 生成拟合网格，1022 顶点/1952 面 | 22.41 ms |
| 生成预览网格，5630 顶点/11040 面 | 126.85 ms |
| 160px 单视图拟合网格轮廓渲染 | 1.57 ms |
| 512px 单视图拟合网格轮廓渲染 | 1.57 ms |
| 512px 单视图预览网格轮廓渲染 | 7.83 ms |
| 512px 单视图 IoU/P95 指标 | 2.04–2.18 ms |
| 224px 三视图完整候选：建模、渲染、训练损失 | 25.94 ms |
| 上项不含网格生成 | 4.26 ms |

`_segment_distance` 每次拟合网格生成调用 **73,152 次**。主要瓶颈是 Python 中逐点、逐线段计算深度，不是显卡没有参与画画。P95 指标也没有在每个拟合候选里重算；候选损失使用阶段开始时预计算的距离场，不能把加速最终 P95 误当成主要拟合收益。

独立 NumPy 批量深度公式短测为 16.98→1.77 ms，误差最大 `5.55e-17`，这是局部 9.57 倍。随后已完成**完整配对验证**：同机、相同 2445 次候选，标量 **61.55 秒→NumPy 28.77 秒，整体 2.139 倍**；全部候选 trace、三张最终 mask 一致，顶点最大差 `8.33e-17`。这才是可报告的整体提速，且没有使用 GPU。Windows 尚无实体机复测。

证据：短测 JSON（历史产物未随源码入库：`output/garment-gpu-assessment-20260911/cpu-profile.json`）、完整配对 JSON（历史产物未随源码入库：`output/garment-gpu-assessment-20260911/cpu-comparison/comparison.json`）。短测是向量化集成前的标量瓶颈快照；当前代码已更新，不应用它冒充新版耗时。

## 四条技术路线

| 路线 | 在当前项目里的用途 | Windows/Mac 通用 GPU 情况 | 来源与许可证 |
| --- | --- | --- | --- |
| **1. WebGPU/WGSL 批量计算** | 批量生成候选顶点、同时渲染多视图、在 GPU 内归约损失；后续也可执行局部约束迭代。 | 优先路线。Chromium 的 Dawn 使用 D3D12/Metal/Vulkan 等后端，不绑定 NVIDIA，可覆盖受支持的 Intel/AMD/NVIDIA/Apple GPU。必须运行时检测 adapter、limits 与 device loss，不能承诺每台旧显卡可用。 | [W3C WebGPU](https://www.w3.org/TR/webgpu/)；[Dawn 官方说明](https://github.com/google/dawn)、BSD-3；[Three.js WebGPURenderer 源码](https://github.com/mrdoob/three.js/blob/dev/src/renderers/webgpu/WebGPURenderer.js)、[MIT](https://github.com/mrdoob/three.js/blob/dev/LICENSE)。 |
| **2. 多视图法线＋局部自适应网格** | 在粗轮廓后加入法线图、袖根/袖口曲线、地标、平滑及形变正则项，让局部鼓度和缝线路径参与损失；必要时在误差大的区域细化网格。 | 算法可在 WebGPU 实现；现成论文示例不等于浏览器版本，需要移植。先做固定拓扑局部位移，后做自适应重网格，避免把已有袖口/袖根索引打散。 | [Continuous Remeshing For Inverse Rendering 官方代码](https://github.com/Profactor/continuous-remeshing)、[论文 DOI](https://doi.org/10.1002/cav.2101)，代码 [MIT](https://github.com/Profactor/continuous-remeshing/blob/main/LICENSE)。其 [example.py](https://github.com/Profactor/continuous-remeshing/blob/main/example.py) 明确从多相机 normal/alpha 图反传优化顶点并 remesh。 |
| **3. 可微渲染优化** | 从软轮廓、法线与局部几何损失计算梯度，降低逐坐标反复试探的成本；可先将 18 参数生成器张量化，再扩展局部控制点。 | PyTorch3D 的完整加速路线主要是 CUDA；官方仅部分算子有 CPU 实现。不能当作 Intel/AMD/Apple 浏览器通用 GPU 后端，也不能因为 PyTorch 支持 MPS 就假定其自定义光栅算子支持 MPS。作为可选服务器路线，或借鉴算法自行实现 WebGPU 后端。 | [PyTorch3D 官方项目](https://github.com/facebookresearch/pytorch3d)、[安装支持说明](https://github.com/facebookresearch/pytorch3d/blob/main/INSTALL.md)，[BSD-3](https://github.com/facebookresearch/pytorch3d/blob/main/LICENSE)；[可微渲染技术报告](https://arxiv.org/abs/2007.08501)。 |
| **4. XPBD，必要时评估 Projective Dynamics** | 为局部布料增加拉伸、弯曲、连接、碰撞约束，让袖筒与腋下形状更自然；它是布料形变约束层，不负责从照片直接识别真实裁片。 | XPBD 局部约束适合 WGSL 的 graph coloring 或分阶段 Jacobi 更新；无需 CUDA。PD 可用局部投影＋全局线性求解，固定拓扑/权重/步长时复用系统分解，但通用 GPU 稀疏求解比 XPBD 更复杂。 | [XPBD 作者论文页](https://matthias-research.github.io/pages/publications/publications.html)、[论文](https://matthias-research.github.io/pages/publications/XPBD.pdf)；[PositionBasedDynamics 官方实现](https://github.com/InteractiveComputerGraphics/PositionBasedDynamics)含 XPBD 与图着色并行，[MIT](https://github.com/InteractiveComputerGraphics/PositionBasedDynamics/blob/master/LICENSE)。[Projective Dynamics，ACM 2014](https://doi.org/10.1145/2601097.2601116)；PD 在这里是算法候选，未审定某个 PD 实现包许可证。 |

WebGPU 已实现为独立轮廓评分模块，详见 [计算加速与实测](garment-compute-acceleration-20260911.md)，尚未接管完整拟合。XPBD（2016）、PD（2014）和 Continuous Remeshing（2022）是可实现的成熟方法，不把它们包装成 2026 年刚发布的论文。当前 W3C 页面是 2026-09-01 Candidate Recommendation Draft。本次核对了官方项目、源码及许可证，没有安装这些新依赖。

### 许可证上会影响选型的一点

`nvdiffrast` 的当前官方许可证**不是 MIT/BSD**。[LICENSE.txt](https://github.com/NVlabs/nvdiffrast/blob/main/LICENSE.txt) 标题为 `Nvidia Source Code License (1-Way Commercial)`，第 3.3 条要求使用限于研究/评估，不得带来直接或间接金钱收益，NVIDIA 及关联方例外。因此不把该依赖直接放进商业系统；需另获许可或替换。其 [README](https://github.com/NVlabs/nvdiffrast) 也明确链接该许可证与商业咨询入口。

Continuous Remeshing 本体是 MIT，但官方示例把 nvdiffrast 列为可选示例依赖；不能因为顶层 MIT 就忽略示例渲染器的限制。可采用 MIT 的重网格算法配另一个后端。Three.js 的 WebGL2 渲染回退也不代表自写 WGSL compute 会自动回退；计算模块应有独立 CPU 路径。

## 具体接入次序与验证

1. **现在的 CPU 优化保留**。下一步缓存固定分辨率的三角形、语义组和边界拓扑模板，避免每个候选重新构建不变邻接。候选仍检查参数合法性和三角形方向，最终独立跑结构/穿插验证。阶段中不变的 target bool、权重 `1+abs(field)` 和面积也可以缓存。
2. **WebGPU 先做 batch 接口，不只换查看器**。`fit_stage` 每个坐标的约 7 个全区间采样加局部探针可一起评估；不同坐标更新和标量细化仍有依赖。顶点、faces、参考距离场留在 GPU，按候选×视角批量渲染、归约，每批只读回标量损失。每次候选回传全部 mask 会抵消收益。保留缓存、预算、无效参数和一致的平分择优规则。
3. **Windows 要实测，不只标“支持”**。同一 WGSL 内核分别在 Intel/AMD/NVIDIA Windows 与 Apple GPU 上检验；记录浏览器/adapter、编译首耗时、批吞吐、端到端耗时及 device loss 回退。先用 FP32 基线，不依赖可选 FP16。WebGPU 和 OpenCV 的像素覆盖规则可能不同，最终胜者继续用冻结 CPU 渲染器和原阈值复核，不能悄悄换评分尺子。
4. **精度单独立项验收**。先加入可见袖根缝线与袖口边缘约束，再加入带置信度及遮挡处理的多视图法线，最后增加局部可控位移/布料约束。对补生图和旧参考的冲突明确降权，不把 AI 法线当实测表面；缝线粗标误差目前约 15px，需精修后才用于严格量化。可微训练用软损失，最终仍用不变的硬轮廓、局部地标及结构检查。

## GPU 不能补的缺口

当前模型是有界的 18 参数薄壳族，左右主要共享控制，缺少局部袖型、连续内壁、真实包边、针脚、布纹、缝制连接和材料力学；原训练损失只看三个外轮廓，分割还会填掉内部孔洞。把这些运算并行化只会更快得到同一类答案。

更准确需要增加可表达的局部几何和相应观测损失；布料求解能提高自然程度，仍不能从缺少标定的 AI 图唯一恢复真实布厚、裁片与隐藏接缝。新法线、补图和局部视角应建立独立证据版本，不能用拟合它们之后的漂亮渲染冒充独立一比一验证。现有右侧轮廓未达原阈值的结论也不会因提速而改变。
