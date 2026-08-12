# MiniMax H3 视频任务交接清单

## 1. 已完成视频

- 版本：V17 背景刚性锁定版
- 模型：`minimax-h3`
- 规格：9:16，720×1296，24 FPS，12 秒
- 公网视频：https://api.dayan.design/media/video/24f1447f-9501-43f2-a2e2-a84bc620b5d7
- 本地加 BGM 成片：`minimax_h3_v17_background_locked_output_12/minimax-h3-v17-background-locked-cc0-bgm-12s.mp4`
- 本地原片：`minimax_h3_v17_background_locked_output_12/minimax-h3-v17-background-locked-silent-12s.mp4`
- 任务 ID：`24f1447f-9501-43f2-a2e2-a84bc620b5d7`
- 实际费用：¥1.80

## 2. 提示词文档

- V17 完整提示词：`minimax_h3_v17_background_locked_output_12/prompt_v17.txt`
- V16 近景连续动作基础提示词：`scripts/dayan_minimax_h3_v16_closer_continuous_prompt.txt`
- V17 背景刚性锁定补充规则：`scripts/dayan_minimax_h3_v17_background_rigidity_addendum.txt`

## 3. V17 参考图

- 文件夹：`minimax_h3_v16_closer_continuous_output_11/`
- `01.png` 至 `09.png`：九个连续姿态参考图
- `archived_urls.json`：Dayan 已归档参考图 URL

## 4. V17 运行与检查材料

- 创建响应：`minimax_h3_v17_background_locked_output_12/create_response.txt`
- 最终响应：`minimax_h3_v17_background_locked_output_12/final_response.json`
- 动作/背景抽帧：`minimax_h3_v17_background_locked_output_12/body-motion-1_5fps.png`
- 面部/嘴部抽帧：`minimax_h3_v17_background_locked_output_12/face-mouth-3fps.png`
- CC0 轻音乐：`minimax_h3_v8_cc0_output_5/Chill_Beat_CC0_12s.mp3`

## 5. 已确认的根因

V17 虽然在提示词中锁定背景，但 01–09 是独立生成的参考图，拱门、灯、花瓶、沙发边缘和构图存在小幅像素差异。H3 对这些差异做时间插值，导致同场景背景卡顿或家具轻微移动。这是参考图设计问题，不能只靠负面提示词彻底消除。

## 6. V18 正在制作的修复方案

- 使用一张无人的固定背景底板。
- 九个姿势只保留人物前景层。
- 人物前景统一叠加到同一背景底板和同一坐标系。
- 保证九张参考图的背景像素完全相同，不再是视觉上大致相同。
- 固定背景底板：`minimax_h3_v18_fixed_background_refs/background-plate.png`
- V18 参考图目录：`minimax_h3_v18_fixed_background_refs/`
- 处理脚本：`scripts/build_v18_fixed_background_refs.py`

## 7. 给其他模型的核心要求

1. 同场景必须使用同一张背景底板，背景物体世界坐标固定。
2. 允许人物连续动作，不允许拱门、花瓶、灯、沙发或墙体自行移动、缩放、形变或重绘。
3. 同场景内不切镜，人物动作连续通过姿态途经点，不在每张参考图停顿。
4. 如果背景、空间关系、机位或场景确实改变，只能在边界干净硬切，不得用物体变形或背景融化过渡。
5. 全片静音生成，BGM 后期合成，避免音频驱动嘴型和身体节拍。

> 本文档不包含 API Key。
