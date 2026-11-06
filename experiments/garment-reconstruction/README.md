# 服装重建方法验证原型

这是 [工程设计](../../docs/research/garment/garment-reconstruction-design-20260911.md) 的部分可行性实验，**不是完整实现，不是已通过实物重建验收的模型**。

已实现固定拓扑薄壳、真实共享缝线、四个开口、轮廓拟合、独立结构检查，以及灰模/白线框查看器。原型使用相对尺度，无布料仿真、领边内壁、UV 或实际印花。

已新增 [v2.1 升级与复测](../../docs/research/garment/garment-reconstruction-upgrade-20260911.md)：平滑肩袖、倾斜袖口、分区体积、领口视觉标注约束及限定范围的袖身求交。查看器默认 v2，`?version=1` 可看上一轮。最终版正面/背面轮廓通过，侧面仍有偏差，完整服装未验收通过。

[多方位补检](../../docs/research/garment/garment-multiview-validation-20260911.md) 增加左侧参考与十方向投影，检查真实领口、袖口和下摆边界。左、右轮廓未通过；上下参考为斜视，正上下及无参考斜角保留未评估。

[袖子细节与 AI 补图](../../docs/research/garment/garment-sleeve-detail-20260911.md) 新增薄翻边、短内壁、落肩缝线和局部内缝交点。查看器 `?version=2&model=sleeve-detail` 选择独立候选，仍未达到一比一或完整单壳验收。

[跨平台计算加速](../../docs/research/garment/garment-compute-acceleration-20260911.md) 将 NumPy 批量几何计算接入 v2 拟合，并验证标准 WebGPU 路径。同机完整配对为 61.5 秒 → 28.8 秒，2,445 次候选评分及最终轮廓保持一致；这是计算加速，不是新增服装细节。

[GPU 实际拟合与胸前修复](../../docs/research/garment/garment-gpu-chest-repair-20260911.md) 已将 GPU 加权评分接入实际参数搜索，并修正腋下侧缝端点场造成的前片凹带。`python experiments/garment-reconstruction/gpu_fit_server.py` 启动本地计算服务；查看器新增“GPU 重新拟合”，默认显示最新通过独立检查的修复产物。

[胸腔支撑与后背顺形](../../docs/research/garment/garment-torso-support-20260911.md) 进一步解除胸前中线冻结，收回后腰及后下摆鼓胀，并在 GPU 最终选择中加入胸部支撑、后背平顺和结构约束。

## 复现

需要 Python 3.10+。建议使用单独虚拟环境安装依赖；几何与结构测试只需标准库。

```bash
python3 -m venv .venv-garment
.venv-garment/bin/python -m pip install -r experiments/garment-reconstruction/requirements.txt
.venv-garment/bin/python -m unittest discover -s garment-reconstruction -p 'test_*.py' -v
.venv-garment/bin/python experiments/garment-reconstruction/run_validation.py --max-evaluations 750
```

实验将重新生成 `output/garment-validation-20260911/` 内同名产物，重跑前如需保留旧实验，请先复制该目录。此目录只是实验输出，不具备设计稿中的生产版不可变存储机制。

以上 `run_validation.py` 复现的是 v1。v2 历史结果在 `output/garment-upgrade-20260911/`；`run_upgrade.py` 依赖已归档的 v1 输入与拟合结果，现默认写入 `output/garment-upgrade-new-run/` 并拒绝覆盖非空目录。可使用 `--output-dir` 指定新的实验目录，`--depth-backend scalar` 切回原计算方式。

当前验证需要工作区已有的 `output/shirt-four-view-20260911/{front,back,right}.png`。它们是此前生成的 AI 目标视图；代码不会悄悄用其他图片替代。运行脚本没有外部生图或三维 API 调用。

查看器路径为 `/experiments/garment-reconstruction/viewer.html`，需要从项目根目录提供本机 HTTP 服务，使用工作区已有的 Three.js 0.170.0。它不会修改原来 8766 端口的模型。

## 文件和实验边界

| 文件 | 用途 |
| --- | --- |
| `geometry.py` | 确定性前后衣片与袖片薄壳；默认 1434 顶点、2752 面 |
| `geometry_v2.py` | 分区体积、肩袖曲线、倾斜袖口和局部领口控制；预览 5630 顶点、11040 面 |
| `evaluator.py` | 独立进程从实际网格检查连通性、边界、退化等，不接受生成器自填的通过结果 |
| `image_fit.py` | 固定相机下的软件三角面轮廓投影和有界 Powell 拟合 |
| `run_validation.py` | 受控同族合成测试、AI 商品目标探索、两份归档负例及报告 |
| `fit_v2.py`、`run_upgrade.py` | 可行坐标搜索、完整唯一候选日志及同参考升级对比 |
| `collision_check.py` | 非共享顶点袖身三角面求交；含未决状态与范围限制 |
| `run_multiview.py` | 冻结网格的十方位检查，新增左侧参考及独立证据覆盖状态 |
| `aperture_check.py` | 重建实际开口边界并测量三维及各方向投影；不判断遮挡可见性 |
| `sleeve_detail.py`、`validate_sleeve_detail.py` | 独立袖部细节层、原几何保护与候选自身的诊断报告 |
| `generate_reference_supplement.mjs` | 使用已配置的项目图片运行接口生成并归档补充设计参考 |
| `benchmark_acceleration.py` | 同一完整拟合任务的标量/批量计算配对，独立归档并核验一致性 |
| `gpu-silhouette.js`、`gpu-benchmark.html` | 标准 WebGPU 多视图轮廓评分模块及真实浏览器短基准；尚未接管完整拟合 |
| `test_geometry.py`、`test_evaluator.py` | 参数/拓扑/负例测试，包含实际生成网格联调 |
| `viewer.html`、`viewer.js` | 实际 Three.js 网格查看与实验结果展示 |

原有结构检查通过只得到 `partial_checks_only`。v2 另有袖子与衣身的限定范围三角面求交；它不覆盖共享顶点邻域、自交、完整包容或厚度。材料厚度、真实照片还原和物理尺寸等未实现项保持 `unevaluated`，完整服装验收不通过。

合成目标和候选使用同一个几何程序，只能证明这一模型族的拟合链路。商品目标使用固定的近似正交相机和针对奶油色衣服的颜色分割，三个视角都参与优化。IoU 是像素轮廓重叠，不是物品还原率。

v1 拟合日志只记录改进点和不可行候选；v2 保存所有唯一尝试参数，缓存重复值不再渲染。v2 测试保证袖参数不移动衣身顶点，但不代表全部部位的编辑都完全隔离。独立验收器进程是实验隔离，尚无生产部署的权限保护；完整结构验收在最终候选上运行，尚未实现逐候选完整阻断。

[v1 验证报告](../../docs/research/garment/garment-reconstruction-validation-20260911.md) 与 [v2.1 升级报告](../../docs/research/garment/garment-reconstruction-upgrade-20260911.md) 分别保留各轮通过、失败和未验证内容。
