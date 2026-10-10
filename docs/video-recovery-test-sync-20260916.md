# 正式视频修复同步到本地测试版

来源：任务「检查正式分支拆解脚本」，干净工作树 code-ai-pla-prod-video-20260915，提交 5289d86、acee0cd、1ebd884、7ff9fc9（HEAD 384c8f8）。

已合并并部署：并行脚本创建、原子重试/worker 认领、模型流式兼容与完整结束检查、900 秒共享会话预算、结构识别有界失败、保存自定义视频提示词、独立九帧参考模式、首帧审核和最多两次同线路质量重做、事务化审核结果发布。

保留测试版：视频裁剪、音频转写及口播语义压缩、通用视频任务、详细服装校验和阶段进度、全部电商/POD/透明印花及草稿上传重试。Java 已有精确抽帧结果直接送入完整流式会话；避免 runtime 再次抽帧并经不同 Agent 请求链路分析。口播与动作复核共用同一模型会话预算。独立九帧模式仍须选择支持的 DAYAN/minimax-h3 路线，不擅自改写历史任务配置。

验证：后端 289 项全通过，其中真实 MySQL 隔离库并发认领 1 项、审核事务 5 项实际执行；runtime 243 项全通过（含真实 localhost HTTP/SSE 与音频语义压缩回归）；frontend/tests 23 项全通过。登录页面、新建拆解弹窗、15 秒裁剪及提取口播入口人工浏览器验证正常。

部署：仅替换运行 JAR 的视频修改类及新类；其他类、资源、Flyway 迁移保持原样。runtime 仅更新 server.js 和 3 个新增模块；前端仅 app.js/index.html。三个本地镜像均持久化，app-local.jar 更新；服务健康检查 UP，前端 HTTP 文件逐字节匹配。部署前无在途拆解、生图、生视频、商品发布或裂变任务。未运行付费模型、未重做历史任务、未修改正式服务器。

产物和回滚备份：output/sync-video-recovery-20260916/；app-before.jar、live-server-before.js、live-app-before.js、live-index-before.html、before/。新 JAR 变更清单见 app-patch-manifest.json。运行版本 app.js?v=video-recovery-20260916。

这轮是修复同步与回归检查，不代表视频工作台所有功能已通过完整上线验收；用户随后要求逐功能验收，另行跟进。
