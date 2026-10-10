# 结构识别临时故障恢复

同步参考任务「检查正式分支拆解脚本」2026-09-17 的诊断：初始结构识别的限流和不完整响应在两次尝试后被统一标记永久暂停，后台跳过，导致生图无法继续。

当前工作区修复：
- 新错误保留 runtime 的显式 retryable 决策，并持久化 STRUCTURE_ANALYSIS_RETRYABLE 标记。
- 传输临时错误返回调度器，按单款 updatedAt 冷却 60 秒，不在图片恢复循环热重试。
- 兼容历史 CHAT_RATE_LIMITED、CHAT_INCOMPLETE_RESPONSE、CHAT_TIMEOUT、CHAT_NETWORK_INTERRUPTED、CHAT_EMPTY_RESPONSE、UPSTREAM_HTTP_429 暂停错误。
- 结构 JSON 校验、持久化错误及显式不可重试的配置错误继续暂停；已有结构直接复用。
- 视频与图片并行批次保留自动接续标记，临时结构错误不提前转为最终视频失败。等待中的单款不被当成已完成。
- 图片结果下载维持原恢复节奏。

已按用户要求部署到本地后端并重启，健康状态 UP，镜像 ai-commerce-platform:structure-recovery-20260917（同时更新 :local）。64 项回归测试通过。保留已有供应商受理的视频任务。未部署正式环境，也未操作参考任务中正在恢复的线上批次。
