# 广告消耗工作台上线计划

按 executing-plans 在当前隔离功能分支内执行；用户已于 2026-11-11 明确授权上线。运维会话负责串行窗口，本会话只增加 V150 专用维护入口、最小旧版本兼容判断及测试，不修改其他控制面模块。

**目标：** 验证并发布三表广告消耗工作台，保留全部已有账本、凭据、进度和回滚证据。

**方案：** 继承已发布 CI/CD，通过专用 systemd 维护先追加 V150，实际重启旧应用验证兼容，登记真实回执后走 Gitflow 候选及正式发布。V149 为并行任务预留但未进入当前代码及生产，不能虚构或填补迁移。

## 执行步骤

- [x] 在 `ops/heyi-ops/tests/test_schema_compatibility.py` 增加 V150 准确 SQL 摘要及完整回执的接受/拒绝测试；保留 V148 原规则。先确认失败，再实现 `Host.additive_compatibility` 最小支持。
- [x] 新增 `FinanceAdSpendSchemaMaintenance.java`、`finance_ad_spend_schema_maintenance.py` 及专用测试。只允许 V148→V150，精确校验 SQL，历史仅增加一行；保留账户、店铺关系、同步状态/分页/限流，迁移异常保留门控。恢复已验收操作不得重复迁移或重比过期业务快照。
- [x] 隔离 MySQL 验证 Java 迁移、重复执行、旧 Flyway 校验和原始数据保留；重跑 Python 控制面、财务后端和全部前端检查。真实旧应用重启兼容属于生产维护验收，不用合成测试替代。
- [ ] 按运维交接的最新基线 no-ff 合并，创建功能 PR→develop→release；核对完整 CI、候选来源证明和 V150 SQL 摘要。准备受信任控制面安装与独立迁移输入，不绕过迁移指纹门禁。
- [ ] 获取串行窗口，核对服务器容器、Compose、配置、卷、任务和双锁；安装并验收受信任控制面，systemd 完成 V150 维护、旧应用兼容与门控恢复，保留原 V148 及本次回执。
- [ ] 按 Gitflow 正式合并、标签、发布及合回，验收健康、鉴权、三表静态资源、新接口及真实已入库广告数据。缺资金分项保持未知，不把未导入工作簿宣称为生产数据。

## 固定输入与边界

V150 SQL SHA-256：`a8dfff7b4f3b722e137dd7bc39aebc0a8b5df9201b75a29199dc55211e7c1e13`。只 CREATE `finance_ad_spend_imports`、`finance_ad_spend_allocation_sets`、`finance_ad_spend_allocations`，不触及权限表、加密、旧财务事实或同步租约。保留 10 GiB 安全余量及两个近期合格回滚组合；不导入用户真实工作簿，不清理无关镜像或业务数据。
