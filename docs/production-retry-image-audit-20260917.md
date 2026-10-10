# 正式任务自动重试与图片加载核查（2026-09-17）

对象：任务 菲律宾-1，ID 2da92517-87e9-4d0f-8ade-657c5c6f6196。检查时 19 款完成、5 款供应商生成中、4 款结构分析失败。本轮只做诊断，没有重发生成请求或更改配置。

## 四款结构分析停止的真实原因

四款（detail_1_cover.png、sku-15-15、白底豹纹套装图、生成800_800带白边图片 (10)）均在 15:56:17 左右返回 CHAT_AUTHENTICATION_FAILED，无生成图片资产。

在当前运行时使用正式配置的凭证执行 GET /models（不生成内容）得到 HTTP 403、new_api_error/access_denied。供应商 monotoo.shop 的响应实际为全体用户维护公告，声明“暂停维护至18点”。这不是足以判定 Key 错误的证据，也不能保证18点确实恢复。

chat-completion-transport.js 把所有 401/403 归为不可重试认证失败；结构分析服务随后保存 STRUCTURE_ANALYSIS_PAUSED，未附加 STRUCTURE_ANALYSIS_RETRYABLE。图片恢复器拒绝此错误，条目最终 VIDEO_FAILED。视频提交重试器也无法接手：这些条目尚未进入视频生成阶段。供应商恢复后，现有错误记录不会自动改成可重试。

需要的修正是保留维护/限流与真正认证失败的区别，带冷却恢复；不可无条件重试所有403。还需对维护期间已经暂停的结构任务提供有界恢复。

## 五款视频上一轮停止的原因

五款当前均 RUNNING/ACCEPTED，provider task 已受理且 updated_at 持续推进。上一轮错误都是 VIDEO_REVIEW_PAUSED + CHAT_INCOMPLETE_RESPONSE，视频已经生成，失败的是首帧审核。

VideoFirstFrameAuditService 捕获异常后写入 UNKNOWN 的 audit-result.json；后续读缓存直接返回，audit-request-started 也防止再次请求。AiVideoGenerationService 将该 job 标 FAILED，同时其自动重试入口明确排除 VIDEO_REVIEW_PAUSED；所以等候不会触发再次审核。

应对保存的视频/截图重试审核本身，区别网络故障与真正未知布局，不能把审核接口失败直接等同于需要重新付费生成视频。当前新一轮已在供应商执行，本轮诊断没有再次提交。

## 图片链路实测

正式 OSS 环境变量及挂载与发布前一致。该任务所有生成主图、九宫格、首帧和视频资产均记录为 OSS 地址。

- 生成图片：浏览器访问平台权限接口 → 平台检查 OSS 对象并返回307 → 浏览器直接从 hoyee.oss-cn-guangzhou.aliyuncs.com 取图。应用不转发图片正文。
- 预览使用 OSS 960px/WebP/q82 处理。一次实测14,222,242字节的九宫格预览为102,680字节，平台鉴权跳转约39ms，OSS取预览约676ms；两张首帧预览为28–37KB。
- 商品原图：/items/{id}/source 返回200 FileSystemResource，图片正文经过应用服务器；最大原图2,906,301字节。28款原图共约17.1MB，前端小卡片仍请求原文件（lazy加载），没有专用缩略图。

以上计时由正式服务器发起，用于确认路由和数据大小，不代表用户网络耗时。原图通道应改为OSS缩略图直连，放大/下载才取原图；生成图的每图一次鉴权/OSS存在性检查也有优化空间。
