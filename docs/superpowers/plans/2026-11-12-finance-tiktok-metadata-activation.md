# TikTok 元信息 V156 源码生效计划

用户已于 2026-11-12 明确要求上线服务器。现有业务设计及 PR64 沿用；新授权和真实资金完整性不作为已验收结果。

**目标：** 在既有 V155 性能基线上安全生效 V156 和 TikTok 元信息展示，保留财务金额、来源、租约、其他会话源码及历史迁移。

**基线：** 本地 `6abe2b6` 与性能会话明确提供的已提交 `7a7b34d` 做 no-ff 集成；主机 `/opt/heyi/dev/ai-video` 的实际 WIP、运行配置和上下文以操作前重新读取及交接为准。

## Task 1: 真实 V155/V156 查询兼容

- 将正常集成夹具统一执行实际 V150、V155、V156 一次，删除占位事件表；V155 helper 测试保留 `migrateThrough(154)`，V156 helper 可使用 `migrateThrough(155)`。
- `FinanceAdSpendService.sources` 和 `buildNativeSpendFacts` 保持只读；`reportFromNative` 仅向 `FinanceAdMetadataSync.request(Scope,List<Map<String,Object>>)` 投递最小 ID 引用。
- 有事务时在 afterCommit 投递，无事务直接投递；不在 HTTP 查询或只读事务中持久化 job 或调用 TikTok。去重/有界引用只含公司、账户、系列、GMV 类型；后台门控 worker 重新校验现有账户后持久化，再按已有租约执行一页请求。
- 先用只读事务回归复现直接 enqueue 的失败，再验证新路径；实际 V155 投影及 Redis 修订须能在授权错误/成功资料的 DAY 事件后更新。金额、指纹、归属、其他期间保持。
- 运行元信息、投影、工作台和旧广告/Shopee/FX 的定向检查；本地 Maven 与可销毁 DB 由父会话串行安排。

## Task 2: 固定 V156 迁移及受控激活

- 保留 V155 激活器的默认固定 SQL、摘要及所有门控/回退行为；只将 SQL、摘要、helper、版本变为受控类属性，新增固定 V156 专用子类入口。
- V156 SQL SHA256 固定 `0a8268cd9b78cdbb24c2dee18b1ecbdf47a3f1965d4d75e06c5d098115b99abd`。新增独立 Java helper，仅接受真实旧版本155、唯一待执行156、准确脚本及摘要；验证旧历史前缀、旧 runtime validate、拒绝重放，不 repair。
- helper 保留私有 stdin 凭据、每连接所有权登记、启动撤销和有界清理；激活器保留双锁、workspace 锁、维护门控、安全点、CAS、旧 classes 恢复和晚失败围堵。
- V156 子类在 `save_before`（门控/安全点之后）和 `check_static`（释放门控之前）对原广告账目、资金导入/分配、账户/店铺授权及同步表做有界、按主键排序的摘要比对；仅落盘行数/摘要，不保存原始业务行或凭据。不能全表扫描主订单账本，广告流水使用已知账户所属公司和 AD/AD_GMV 索引范围；超出边界或不一致即保留门控。
- 跑 V155 现有故障演练及 V156 适配测试，实际可销毁 MySQL 验证155→156及拒绝重放；不修改生产配置或源码进行这些测试。

## Task 3: 串行生效与验收

- 等性能会话明确交接 V155 的 schema、context、源码和维护窗口；新基线与 manifest 不匹配即重新准备，不覆盖 WIP。
- 只读取本任务差异并逐 hunk 三方合并。保留其他会话经用户确认的最新成本/仓库状态、Shopee、导航、经典/新版入口、源码刷新及其他改动，不从历史 PR 恢复已被撤销的功能。
- 准备私有 operation、逐文件 beforeHash/targetHash、固定 SQL/helper 及运行配置摘要；只读 preflight 通过后使用维护双锁和门控执行156、原历史验证、定向保存、compile 与受控重载。
- 验证健康、鉴权、TLS、资源摘要、实际页面、元信息后台状态和财务原始数据摘要；恢复本次门控及 watcher。新商家授权仍可稍后完成，未返回金额保持未知。
- 记录实际 operation、schema/context、提交和制品文件摘要、通过与未验收项；更新业务/验收说明，完成 feature→develop PR 流程，不构建 JAR 或镜像。
