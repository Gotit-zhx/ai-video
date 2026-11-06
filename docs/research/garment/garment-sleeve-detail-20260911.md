# 袖子细节与 AI 补充参考

> 研究或实验记录；样本、限制及未确认项按文中日期适用，不作为生产能力承诺。 当前入口见[业务说明](../../business/README.md)与[运维指南](../../guides/operations.md)。

当前已完成可查看的袖部细节候选：薄袖口翻边、短内壁、落肩接缝、袖口沿缝，以及 AI 补图驱动的内侧纵缝/沿缝交点。**尚未达到一比一复刻**，真实褶皱、精确鼓度、袖根深部过渡和完整内侧工艺仍未还原。

[打开袖子细节候选](http://127.0.0.1:8767/garment-reconstruction/viewer.html?version=2&model=sleeve-detail)。页面提供十方位及左右袖口、袖根的局部观察；旧 v2 的结论与该候选分开显示。

## 已落入代码和网格的细节

- 沿原袖口共享边界增加薄折返和短内壁，不加端盖；袖口面积保留约 97.09%。
- 根据原四视图的落肩线与袖口窄带增加低起伏的三维缝线。已修复腋下端折带的小折返及袖根线尖顶。
- 根据新 `cuff-interior.png` 中约 `(485,720)` 的交点，在两袖短内翻壁的 10%–90% 深度范围内增加纵缝与短沿缝，交点位于深度 0.62。新表面的基面点落在既有内壁三角形上，采用圆缓收尾；没有将袖口封住或延伸到映射不明的袖根。
- 原 v2 的 5630 顶点、11040 面全部保留。最终候选为 **12306 顶点、24384 面、8 个细节层**，网格哈希 `a83224a0dc37eeb87f1a43c3a9f0b8931c8c7662aff2fa6d384b54a92cb8d148`。

具体尺寸是相对建模假设。缝线为独立闭合细节，尚未与衣片完整缝合；原单壳检查实际运行后保留 `connected_surface` 失败，不能把分层原型当作已验收的单壳实体。新增层的完整接触/穿插语义仍未验证。

## 补图及用途

沿用项目已配置的 `gpt-image-2` 与现有图片运行接口，共执行 **两次生成请求**，以原四视图及真实裁切作身份依据。

第一张是 2048×2048 袖子细节四宫格。外侧、袖口内侧、反向外侧三格可作局部设计参考；其中腋下格仍是遮挡的外侧图，已排除该用途。第二次只补单张内侧腋下近景，能看清连续布面与接缝汇合，准入为局部设计参考。第二次请求 1024×1024，实际返回 **1254×1254**，已按实际尺寸审阅。

![袖子外侧、内翻边及接缝补充参考](/Users/heyi/Documents/ChatGPT/code-ai-pla/output/garment-reference-supplement-20260911/sleeve-detail-sheet.png)

![单独补生成的腋下内侧接缝参考](/Users/heyi/Documents/ChatGPT/code-ai-pla/output/garment-reference-supplement-20260911/underarm-v2/sleeve-detail-sheet.png)

补图中可见针脚、布厚和隐藏接缝都是 AI 提出的设计假设，不是实物测量。袖口参考已驱动上述小交点几何；腋下近景暂用于局部设计审阅，尚未确定与当前袖根的三维映射，未强行加入内衬、三角片或完整袖窿结构。

原正背左右轮廓评分图和相机没有被这些新补图替换。

## 验证结果与缺口

当前 **80 项自动测试通过**，其中袖部细节有 10 项针对测试，覆盖：原几何保留、真实四边界、袖口不封盖、折边平行距离、无退化/重复反向面/非流形边、细节闭合、内缝基面位于旧内壁、起伏范围，以及新增内缝前后四视图投影不变。

完整候选用同一冻结相机重新计算：正面 IoU 0.97374、背面 0.97387、右侧 0.94953、左侧 0.93399。前背轮廓门槛通过，左右仍未通过。**这些是外轮廓诊断，不是缝线或内部细节准确率。** 正上下和四斜角的参考不足状态保持不变。

实际浏览器近景与拖动另行检查；参考和模型可同时查看。基底袖口仍有多边形边缘，袖内深处短 V 转折仍存在，未用猜出的内衬遮盖。物理尺寸、真实内缝、逐针织纹、自然褶皱和材料尚未验收。

## 文件与复现

- [细节实现](/Users/heyi/Documents/ChatGPT/code-ai-pla/garment-reconstruction/sleeve_detail.py)
- [具体几何和来源说明](/Users/heyi/Documents/ChatGPT/code-ai-pla/output/garment-detail-20260911/sleeve-detail-notes.md)
- [独立候选验证](/Users/heyi/Documents/ChatGPT/code-ai-pla/output/garment-detail-20260911/detail-validation.json)
- [AI 参考资料包](/Users/heyi/Documents/ChatGPT/code-ai-pla/output/garment-reference-supplement-20260911/reference-bundle.json)
- [四宫格审阅](/Users/heyi/Documents/ChatGPT/code-ai-pla/output/garment-reference-supplement-20260911/reference-admission.json) · [腋下补图审阅](/Users/heyi/Documents/ChatGPT/code-ai-pla/output/garment-reference-supplement-20260911/underarm-v2/reference-admission.json)
- [四宫格最终提示词](/Users/heyi/Documents/ChatGPT/code-ai-pla/output/garment-reference-supplement-20260911/prompt.txt) · [腋下最终提示词](/Users/heyi/Documents/ChatGPT/code-ai-pla/output/garment-reference-supplement-20260911/underarm-v2/prompt.txt)

```bash
/tmp/heyi-watermark-test-env/bin/python experiments/garment-reconstruction/sleeve_detail.py --no-render
/tmp/heyi-watermark-test-env/bin/python experiments/garment-reconstruction/validate_sleeve_detail.py
```

补图使用项目 API 路径；执行器保留请求 ID、输入哈希、输出和状态，重试附着于相同运行请求。密钥不写入输出。重建会覆盖细节目录同名文件，原 v2 保留。
