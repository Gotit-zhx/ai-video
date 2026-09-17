# 草稿上传：创建前网络故障自动重试

ProductCaptureService 在尚未进入 ExternalCreateBoundary 时，将带 IOException（含 HTTP 超时）原因链的 MarketplaceApiException 重新抛出，让发布事务回滚临时审计。工作流沿用原 idempotency key、图片及请求，执行既有 2/4/8 秒退避，最多追加三次尝试。永久参数错误继续保存失败审计；创建边界之后的异常仍进入结果不确定保护，不自动重放创建。

原因：旧实现吞掉传输异常，保存 FAILED 审计；工作流直接终止，且同键重试永远返回旧失败。

验证：ProductCaptureServiceTest 与 ProductPublishWorkflowProcessorTest 共 74 项通过，包括事务回滚、边界后禁止重试、重试耗尽。仅替换正在运行 JAR 的 ProductCaptureService 类族，持久化本地 Docker 镜像。

历史恢复：执行 5b01cce6-34e4-40d0-bf15-53bfa092326f 的 V2 工作流 d2f0c0fb-7508-4b66-88c4-90e013955000 经锁定核查 publication_attempted=0、审计 FAILED、external_id 为空且为明确传输失败后，保留旧失败审计并移至带 precreate-failed-1 后缀的归档键，原任务使用原键继续。脚本包含精确状态条件，不可用于未决创建请求；恢复记录写入执行事件。V1 未修改。详见 output/publish-transport-retry-20260915/recover.sql。
