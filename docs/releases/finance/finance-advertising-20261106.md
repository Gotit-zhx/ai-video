# 财务广告分析与账户授权 · 2026.11.6.3

状态：**2026-11-07 09:18:32（北京时间）已激活 `2026.11.6.3`，报表及运行验收通过；真实广告账户授权验收未通过。** 版本名及文件名保留 11 月 6 日的发布准备日期，实际激活日期为 11 月 7 日。基于已验收的 `v2026.11.6.2` 冷查询版本，保留其分段查询、同一事务快照、缓存和成本队列优化。

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

候选于 11 月 6 日完成只读核对，激活后于 **11 月 7 日 09:28** 再次核对 2026-10-01 至 2026-10-31 的已入库广告费用：35 家店、8 个分组、30,173 条记录；分类、店铺、分组及总额一致。正式页面的总览卡片、经营明细和广告分析均已核对。各行展示金额独立四舍五入，逐行加和可能与未舍入汇总有分位差；人民币金额随参考汇率快照变化，私有核对回执保存当次快照，不在公开记录中保存业务金额或截图。独立 `AD` 报表仍无记录，不代表实际消耗为零。

健康、匿名财务接口 401、JAR、前端文件与 gzip、配置哈希、V147 和无关容器身份通过；生产日志驱动仍为 `none`。291 项同步计划已恢复，检查点未重置；09:31 复核本次计划标记、贴单及两类千易预留残留均为 0。

## 真实授权验收未通过

用户于 **11 月 7 日 09:28** 使用新链接完成 TikTok 页面授权后返回系统。新回调记录为 `FAILED / TIMEOUT`，当前公司广告账户仍为空。该结果与隔离测试通过分别记录，不能宣称授权问题已经全部解决。

同次无凭据诊断中，服务器默认解析地址连接超时；从可信 HTTPS DNS 取得地址后，宿主机和应用容器均在 TLS 握手期间被重置，HTTP/1.1 和 TLS 1.2 限定探测均未取得 HTTP 响应。运维机对同一官方令牌接口提交空参数可收到平台参数错误响应。已确认服务器至广告接口的连接目前不可用；具体阻断位置尚未确定，不能凭这些探测推断历史每次失败的原因。

下一步需恢复服务器到官方广告 API 的可验证 HTTPS 通道，再生成新链接验收账户保存、名称与同步。当前未新增代理、修改 DNS、关闭证书校验或重放旧授权码。小型脱敏探测回执保存在发布目录；不保存授权链接、令牌、请求原文或持久诊断日志。

## 制品与接手入口

| 项目 | 已核对结果 |
| --- | --- |
| 制品源提交 | `86903734810c9396538ac4f962466e9c90630bc7` |
| 后端镜像 | `ai-commerce-platform:finance-advertising-20261106` / `705cc4678025` |
| 前端镜像 | `ai-commerce-frontend:finance-advertising-20261106` / `34dccb8abc37` |
| JAR SHA-256 | `c38bd767c79dd8bda87ebb07a62f937a23646db0fd8c28fb96384260acd28ae0` |
| 发布 helper / 后台运行器提交 | `4009c66` / `bf77932` |
| 发布目录 | `/opt/heyi/releases/finance-advertising-20261106/` |
| Compose 链 | 后端 252 份、前端 240 份；有序完整路径以容器标签及现场回执为准 |

后台运行器 `deployment-result.json` 为 `exitCode=0`；同目录的 `deployment-running.json` 是阶段回执，不能据其存在判断发布还在执行。`activation.json`、`prepared.json`、`provenance.json`、`drain-restored.json`、`account-drain-restored.json`、`post-release-restoration-check.json` 与 `read-verification.json` 保留。发布标签为 `v2026.11.6.3`；标签源码还包含后续发布脚本修正及本次验收文档，业务制品对应上表的源提交。脱敏摘要见[发布回执](finance-advertising-20261106.json)。

## 回滚与收尾

生产切换前的后端为 `ai-commerce-platform:finance-cold-queries-20261106`，前端为 `ai-commerce-frontend:brush-source-summary-20261106`；实际完整 ID、配置哈希和有序 Compose 链以本次现场回执为准。失败仅回滚本次切换的服务并恢复本次停领标记，不清理业务数据或重写迁移。

后端保留当前广告版、冷查询版及第一轮性能版；前端保留当前广告版、刷单汇总版及 GIFT 版，另保留所有容器引用和无关服务镜像。仅清理核实无引用的本次构建副本、恢复私有文件和旧应用标签，清理清单及结果保存在 `cleanup-plan.json` / `cleanup.json`。运行 Compose、发布 helper、小型回执及源码版本映射保留。清理后 `read-verify` 所需编译输入不再完整，不得直接重跑构建或激活。

Gitflow 按 `release/* → main-tree` 的 `--no-ff` 合并创建版本标签，再将发布分支合回 `develop`；未切换共享原工作区。服务器源码使用 `/opt/heyi/source/releases/v2026.11.6.3/`，逐文件核验清单位于 `/opt/heyi/source/manifests/v2026.11.6.3.json`。源码入口 `/opt/heyi/source/current` 与历史运行配置入口 `/opt/heyi/current` 分别管理。
