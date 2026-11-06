# 生图结果下载恢复与自动重试（2026-09-21）

> 历史发布记录；镜像、测试与“当前/最新”均指记录当时。 当前入口见[业务说明](../../business/README.md)与[运维指南](../../guides/operations.md)。

## 原因和实单结果

capture ae62c043-2c13-4e8d-93d4-2711113d116f 有 25 张图片，20 READY、5 RESULT_RECEIVED。生成结果 JSON 已持久化，失败发生在下载 imgen.x.ai。

服务器系统 DNS 返回与独立加密 DNS 不一致的公网地址；即使使用正确解析地址，直连仍有 TLS 中断/超时。维护客户端从原结果 URL 下载 5 张图片，验证格式及 SHA-256，写入原企业的内容寻址归档，以事务将对应账本切到 EDITED，仅重新排队最新任务的 5 个失败项。没有重发生成请求。最终图片任务 59da9e69-e3e8-4e3c-bf9e-3486347c96e2 SUCCEEDED，25/25，所有账本 READY，capture READY。

## 持久修复

- Grok grok-imagine-image-2.0 生成和编辑请求增加 response_format=b64_json，通过生图接口直接接收图片数据，减少单独访问临时图片域名。兼容提供方仍返回 URL 的情况，仍先持久化原响应。
- PublicImageDnsResolver 对精确 imgen.x.ai 使用带证书校验的公共 DoH；仍按公网 IP、缓存 TTL 和响应大小规则校验。没有硬编码图片服务 IP 或关闭 TLS 校验。此改动不能解决所有跨境链路阻断，因此结合直接返回数据模式。
- 已生成结果的下载 Socket/timeout/EOF、HTTP 408/425/429/500/502/503/504 作为专用异常，队列最多自动重试 6 次，间隔 10/30/60/120/300/300 秒。不重新生图，不清除 generation_json。认证错误、不支持格式和证书校验失败不盲目重试。
- 队列等待与重试次数持久化，耗尽后保留原结果并显示明确错误。获取任务锁后再更新队列，保持与完成路径一致的锁顺序。

测试：Java 40 项通过，Pi HTTP 契约回归 43 项通过。生产使用原 jar 的最小类叠加，Pi 只替换 Grok 请求参数；健康检查 UP，环境及挂载不变。尚未额外发起付费新生图来测试上游 Base64 返回，参数支持已由官方生成和编辑接口文档确认。

官方：https://docs.x.ai/developers/rest-api-reference/inference/images
