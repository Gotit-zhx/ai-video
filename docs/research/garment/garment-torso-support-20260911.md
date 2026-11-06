# 胸腔支撑与后背顺形

> 研究或实验记录；样本、限制及未确认项按文中日期适用，不作为生产能力承诺。 当前入口见[业务说明](../../business/README.md)与[运维指南](../../guides/operations.md)。

本轮根据“胸前要有支撑、后背不要乱鼓”的反馈，将前胸和后背作为同一个衣身体积来处理。

前一轮只修复前片两侧凹槽，并明确冻结胸前中线和全部后片，因此没有解决整体胸腔支撑。原参数中 chest_depth 为 0.955、waist_depth 为 1.319、hem_depth 为 1.500，前后共用的深度场还会让后腰和后下摆过度膨出。

现在 `geometry_v3.py` 提供独立的 `chest_support` 和 `back_drape` 参数。前胸使用宽椭圆胸腔支撑，允许中线向外移动，向领口、腋下和腰部平滑过渡；后背使用向内收回的椭圆包络，后下摆同时顺形。领口与袖子保持原有连接和开口。这是几何形态先验，不是真实布料动力学模拟，也不声称从图片恢复了真实人体尺寸。

GPU 仍负责实际候选的多视图评分。最终选择同时要求前胸支撑、后背截面平顺、拓扑结构和参考轮廓相对支撑初稿不退化。原参考分数和阈值继续公开；为了满足新的支撑形态，不再强迫侧面维持旧的鼓胀轮廓。正背面轮廓评分不代表胸背内表面的正确性。

实际复测发现一个轮廓分数更好的候选重新产生后背横向起伏，已加入后背截面二阶差分约束及对应回归测试。原候选和失败记录保存在独立运行目录，没有将失败改写成成功。

当前产物由 `output/garment-gpu-repair-latest.json` 指向。每次运行有独立网格、参数、选择记录、真实 GPU 评分和十方位检查。浏览器最终复检记录位于 `output/garment-torso-support-20260911/browser-check.json`。

最终实测运行 `20260911-163934-c25c11`：WebGPU 评分 240 个候选，14.41 秒，无 CPU 回退。最终保留 `structural_repair_start`，没有采用产生新起伏的 GPU 搜索结果。111 项 Python 测试通过。

运行方式：`python experiments/garment-reconstruction/gpu_fit_server.py`，打开 `http://127.0.0.1:8768/garment-reconstruction/gpu-fit.html`。只生成支撑初稿可执行 `python experiments/garment-reconstruction/run_supported_chest.py`；页面会明确显示该操作没有执行 GPU 搜索。

局限：侧面参考轮廓仍未通过原阈值；肩袖、褶皱、布厚和材质尚未达到实物还原。现有碰撞检查仅覆盖袖子与衣身的限定范围。
