# 财务广告分析与账户授权 · 2026-11-06

状态：发布准备，目标版本 `2026.11.6.3`。基于已验收的 `v2026.11.6.2` 冷查询版本，保留其分段查询、同一事务快照、缓存和成本队列优化。

## 发布范围

- 财务总览与广告分析汇总平台账单广告费、两类广告佣金和独立广告消耗，分组、店铺、明细与导出使用同一口径；无记录与零金额分开。广告佣金不再次扣减利润。
- 广告账户详情请求及名称保存改用 TikTok 官方 `name`，修正 `advertiser_name` 字段错误；失败提示脱敏分阶段，保留一次性授权、公司隔离和原子保存。
- 前端仅更新 `index.html`、`finance-workspace.js`、`finance-workspace.css` 及对应 gzip，保留其他业务资源。
- Java 仅替换 FinanceAdAuthorization、FinanceAdFailures、FinanceAds、FinanceAdvertisingReport、FinanceController、FinanceFx、FinanceProfitReport、FinanceReadCache、FinanceRepository、FinanceService 类族。
- 缓存前缀 `v17-advertising-commissions`，不复用旧结构的财务快照。不新增数据库迁移、代理、DNS 配置或持久日志。

## 准备与验证

Gitflow 从功能分支经 `--no-ff` 合入个人 fork 的 `develop`，再建立本发布分支；未切换或修改共享原工作区。

已通过 141 项单元回归、19 项本机可销毁 MySQL 集成及 8 套页面回归。集成覆盖授权入库/回滚、跨周广告统计、经营排除、同一数据库快照及缓存投影。旧版本和新版本的请求协议分别验证失败/通过。所需 8 个既有类族按各自历史编译参数逐字节复现，再生成 10 个候选类族；运行依赖按实际 JAR 哈希核对。

发布 helper 为 `scripts/release/release-finance-advertising.py`，只接受本次完整 QA、现场基线和制品映射。构建前调用镜像压缩保护并使用不可变基础镜像；逐项核对无关 JAR 条目和运行配置。激活遵守共享发布锁及现有停领/恢复流程，保留业务卷、计划、游标、租约、限流和 V147。

候选和线上将分别进行只读广告金额控制核对、健康、匿名鉴权、制品和前端 gzip 检查。真实 TikTok 账号的 OAuth 授权仍需由账户管理员在官方页面完成，隔离测试不替代平台授权验收。

## 回滚与收尾

生产切换前的后端为 `ai-commerce-platform:finance-cold-queries-20261106`，前端为 `ai-commerce-frontend:brush-source-summary-20261106`；实际完整 ID、配置哈希和有序 Compose 链以本次现场回执为准。失败仅回滚本次切换的服务并恢复本次停领标记，不清理业务数据或重写迁移。

验收后保留活动镜像及两个已验证近期回滚版本，清理本次无引用的暂存输入。运行 Compose、发布 helper、小型回执及源码版本映射保留。
