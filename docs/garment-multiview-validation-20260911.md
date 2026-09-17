# 服装 v2.1 多方位补检

本轮冻结上一版几何，没有重新拟合或调整相机来改善旧分数。新增左侧轮廓检验，以及正背左右、正上正下、四个斜角共十方向的投影和实际开口检查。

## 当前结果

| 方向 | 轮廓 IoU | 边界 P95 / 目标框对角线 | 结论 |
| --- | ---: | ---: | --- |
| 正面 | 0.97329 | 0.01194 | 原轮廓门槛通过 |
| 背面 | 0.97430 | 0.01356 | 原轮廓门槛通过 |
| 右侧 | 0.94953 | 0.01835 | 未通过 |
| 左侧（新增） | 0.93399 | 0.02036 | 未通过 |
| 正上、正下 | — | — | 现有参考是斜视，无法直接评分 |
| 四个斜角 | — | — | 没有对应参考，未评估还原程度 |

门槛保持 IoU ≥ 0.95 且边界 P95 ≤ 0.015。左侧来自同批 AI 四视图，未参与 v2 几何拟合；按 v1 的参考包围框高度协议配准，不根据当前网格优化相机。这是新增 AI 目标视角补检，不是独立实拍验收。

![左侧实际模型投影，绿色为参考轮廓、红色为模型轮廓](/Users/heyi/Documents/ChatGPT/code-ai-pla/output/garment-multiview-20260911/left-overlay.png)

十方向均能生成有限、非空且不裁边的投影。这个检查仅证明可以正常查看，不能把十张截图算成十个还原通过项。

## 上下方向和开口

之前生成的上下参考清楚呈现长段前衣身或内壁，是斜俯视、斜仰视；原始参考清单也记录了这一点。将它们直接与 ±90° 模型投影计算分数会混入相机差异，因此保留未评估。详见 [参考图审阅](/Users/heyi/Documents/ChatGPT/code-ai-pla/output/garment-multiview-20260911/reference-audit.md)。

独立程序从实际三角面边界重新遍历领口、下摆、两个袖口，确认四个边界存在、没有数值退化，再测量三维周长、尺寸范围、拟合平面残差和十方位投影。没有依据 `openings` 元数据直接宣布通过。

领口是非平面曲线：拟合平面 RMS 残差 0.014319，最大残差 0.041033，均为相对模型单位。两个后斜角的领口投影各出现一次非相邻边交叉，属于二维投影重叠；它既不能证明三维穿插，也不能证明领口已经封闭。该报告的投影面积不含遮挡计算，不能用于声称“能透过开口看到背景”。

已有独立袖身三角面求交重新执行：限定范围内 126 对、0 相交；四开口非退化不代表有真实料厚、领边内壁、袖口车线或正确缝制结构。

## 褶皱和袖子细节

v2.1 的大褶皱、袖子缝线、领边/下摆包边和细布纹均明确记录为未评估。共享袖根顶点只证明网格连接，不能替代袖子款式或接缝还原。用户随后要求细节复刻，对应局部细节候选应使用自己的网格、近景和验证结果，不能继承这里的 v2.1 通过结论。

## 复现与证据

```bash
/tmp/heyi-watermark-test-env/bin/python garment-reconstruction/run_multiview.py
/tmp/heyi-watermark-test-env/bin/python -m unittest discover -s garment-reconstruction -p 'test_*.py' -v
```

补检实现完成时共 70 项测试通过，包含新增的证据缺失状态、六向门槛、轴向投影和解析开口/故意退化负例。脚本不调用外部 AI 服务；重新运行会覆盖多方位目录中的同名产物。

- [多方位摘要](/Users/heyi/Documents/ChatGPT/code-ai-pla/output/garment-multiview-20260911/summary.json)
- [参考与相机清单](/Users/heyi/Documents/ChatGPT/code-ai-pla/output/garment-multiview-20260911/reference-manifest.json)
- [实际开口报告](/Users/heyi/Documents/ChatGPT/code-ai-pla/output/garment-multiview-20260911/aperture-report.json)
- [结构报告](/Users/heyi/Documents/ChatGPT/code-ai-pla/output/garment-multiview-20260911/structure-report.json)
- [袖身求交报告](/Users/heyi/Documents/ChatGPT/code-ai-pla/output/garment-multiview-20260911/collision-report.json)
- [交互查看器](http://127.0.0.1:8767/garment-reconstruction/viewer.html?version=2)

模型哈希为 `4f318e80f2b02c6dda6b33e43b3a0a4a9046b0354ea7323c19745fffb0082c68`，与上一轮完全相同。**六方向参考门槛未全过，完整服装还原未验收通过。**
