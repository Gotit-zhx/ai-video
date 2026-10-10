# 视频分析、模型输出与 watch 入口

2026-09-17 用户要求：修复拆解进度反复跳动，展示模型真实返回文字并使用绿色字符雨效果；随后明确要求视频分析全部交给 watch skill。

## 已查证的旧路径与耗时

- 曾安装并实际调用 bradautomates/claude-video 的 watch skill。运行容器路径 /app/skills/watch，Dockerfile 固定上游 commit 83da59fa78c3eee9e20f515fe75c438bb5166efd。
- 9 月 16 日恢复同步将上传拆解改为 Java 密集抽帧后直接调用 VideoScriptSession。watch helpers 仍在 server.js 中，但没有被实际分析入口调用。前后证据保存在 output/sync-video-recovery-20260916/live-server-before.js 与 docs/video-recovery-test-sync-20260916.md。
- 本轮“第六次测试”创建于 10:35:40，本地运动分析约 1.1 秒完成。15 秒片段向模型发送 45 帧、约 2.76 MB；未开启音频提取。
- 模型会话于 10:50:41 完成，elapsedMs=900021。首轮得到完整结果，强制动作复核超时，actionStateAudited=false；任务于 10:50:42 READY。现有日志不能拆分供应商排队、推理与代理缓冲各自耗时。

## 真实输出界面

- 移除前端模拟百分比递增；列表、标题、详情、弹窗共用按任务和 analysisRunId 保存的后台进度，忽略旧轮次响应。
- 模型正文经 runtime progress、Java 持久保存及详情接口传到终端。后台仅采集 assistant text，不采集 thinking 或工具参数；最多保存最近 32,000 字符，保留累计字符数。
- 界面使用深绿终端、装饰字符雨、真实文字逐字显示、复制与滚动跟随。轮询保持 DOM 与阅读位置。重试与公司切换清理旧输出，完成/失败仍能查看已记录文字。
- 新增 V101__video_script_analysis_output.sql。历史任务没有记录流式正文，不能补出过去的逐字过程。

## 验证与部署记录

第一阶段（输出与进度）已经过 32 项前端、23 项 runtime、12 项 Java 测试，以及隔离 HTTP E2E 9 项。10:56 在无在途任务时完成输出链路部署。备份和日志：output/video-matrix-output-20260917/。

## 最终 watch 入口

上传视频处理器只读取时长、整理用户要求和可选口播转写，将共享目录中的原视频路径交给 Pi Runtime。视觉选帧、读图、动作、运镜与九镜编排实际执行已安装的 watch skill。

- watch.py 使用 balanced 场景感知选择和去重，最多 30 帧、512px；薄适配器记录原始浮点时间戳，不引入另一套采样算法。
- AgentHarness.skill("watch") 加载真实技能，并通过仅允许当前任务帧的 read 工具读图。返回前检查所有帧确实被读取，缺少依据会在同一 Skill 会话纠正，失败则明确失败，无直接图片分析回退。
- 将动作自检纳入 Skill 的首次结果要求，不再固定第二次重写整个九镜。必要的 JSON、镜头数量、口播长度和时间依据纠错仍在同一 Skill 上下文中完成。
- Java 不再提前抽取密集分析帧或执行光流运镜判定，也不再用本地判定覆盖 watch 的内容。最终按真实来源时间生成 9 张预览图，旧版本缩略图保留兼容读取。
- 图片不足九帧的静态/稀疏视频可复用已有来源时间，生产分镜仍为连续九段，不编造额外帧。

部署包：output/video-watch-skill-20260917/。Java 补丁只替换 3 个类族共 12 项 class；runtime 镜像保留运行容器已有模块，只改 watch 所需四个源码文件，并通过真实镜像启动验证。

## watch 实装预检

隔离测试视频 18 秒，已安装 watch.py balanced/512 输出 18 帧，耗时 860 ms；2–11 秒片段耗时 503 ms。测试未请求供应商模型，不能用此数字推断整次模型分析速度。真实 AgentHarness.skill API 测试确认加载 Skill、执行 read 工具并把 JPEG 放入下一轮模型上下文。

watch 的人类可读报告将时间戳取整到秒，因此接口需要保存提取器内部的浮点时间戳，避免多帧被误当成同一时间点。

验证：watch 运行时镜像 42 项测试通过（真实 watch.py + 真实 AgentHarness + 本地测试模型 HTTP），Java 22 项测试通过，前端最终 33 项测试通过。完整 HTTP E2E 8 项通过：验证真实 watch Skill、读图、无直接帧回退、流式原文和 9 张真实 JPEG。浏览器确认生成中可见原文、复制成功、完成后内容与 API 一致、9 张图片加载成功。

最终发布：11:22 后端与 runtime 协同更新，发布前后 15 类在途任务均为零；随后更新前端并验证 4 个静态文件与源码逐字节一致。app-local.jar 与三类持久镜像均已同步。记录见 output/video-watch-skill-20260917/live-deployment-verification.json 和 final-verification.json。未重做用户历史任务，测试未调用付费模型。
