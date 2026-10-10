# 通用视频提交连接修复

最新通用任务「生成这个带货视频」在大衍提交时发生 SSL peer shut down incorrectly，任务无 provider_task_id，原记录为 ACCEPTED_OR_UNKNOWN。复刻「第五次测试」使用同一 DAYAN/minimax-h3/720p 线路，已取得任务 ID。时长分别为 5 秒、15 秒；未发现参数拒绝证据。

共用 ProviderJsonSupport 改为可复用、显式 HTTP/1.1 的 Java HTTP 客户端，连接超时 15 秒，读取超时 120 秒。只有明确连接/握手阶段失败允许一次重试；普通 SSL 断开、EOF 和读取超时仍视为受理状态未知，不自动重放。原有 HTTP 响应重试逻辑未更改。

无凭据 GET 探测返回 HTTP 401，证明同容器网络可建立 TLS 并到达 API，不代表真实视频提交已经验证成功。上游带鉴权列表返回空列表，无法据此恢复旧任务 ID，也未据此重复提交旧任务。

测试覆盖握手重试成功、最多一次重试、未知 TLS/读取中断不重放，并运行 Fast/H3 provider 既有测试。补丁只替换生产 JAR 中 ProviderJsonSupport 类，保留上一轮校验次数修复。
