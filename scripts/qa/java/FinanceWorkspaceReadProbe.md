# 财务工作区只读事实与分页探针

`FinanceWorkspaceReadProbe` 不启动 Spring、后台 worker、汇率 provider 或 Redis。它把一个显式 MySQL `REPEATABLE READ / WITH CONSISTENT SNAPSHOT / READ ONLY` 事务保持到全部比较结束；仓库内部的 commit、rollback、close 不会结束外层事务。JDBC 代理进一步拒绝写语句、锁定读取、事务降级、连接解包及可更新结果集。最终由持有者 rollback 并关闭连接。

先用当前源码完成 V155 和正常回填，再运行探针。投影缺失、脏或未就绪直接非零退出；不会调用 request、seed、buildNext 或写回退路径。探针读取正常业务数据，可能产生较慢的原生扫描，请在已批准的只读验证窗口运行。

## 编译与本地无数据库测试

使用本机已有 JDK 和父任务生成的依赖 classpath；不运行 Maven，不构建 JAR。`target/classes` 必须已由父任务编译至当前源码。

```sh
probe_jdk=/Users/gotit/.codex/backups/ai-video/real-preview-20261106-imq070tu/toolchain/amazon-corretto-21.jdk/Contents/Home
probe_cp="target/classes:$(cat /tmp/finance-bi-performance-qa/dependency-classpath.txt)"
probe_output=/tmp/finance-bi-performance-qa/probe-test
"$probe_jdk/bin/javac" -cp "$probe_cp" -d "$probe_output" scripts/qa/java/FinanceWorkspaceReadProbe.java scripts/qa/java/FinanceWorkspaceReadProbeTest.java
"$probe_jdk/bin/java" -cp "$probe_output:$probe_cp" com.yingdao.aicommerce.finance.FinanceWorkspaceReadProbeTest
```

## 真实数据只读运行

仅继承已批准 runtime 的 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD` 环境变量；不得把凭据放入参数、命令日志或本文件。设置 `FINANCE_PERF_READ_ONLY=true` 显式启用。可由私有环境变量 `FINANCE_PROBE_ORGANIZATION_ID` 指定公司；未指定时选择未移除连接最多的公司（不会输出其 ID）。编号筛选仅从 `FINANCE_PROBE_NUMBERS` 环境变量读取。

```sh
FINANCE_PERF_READ_ONLY=true "$probe_jdk/bin/java" -cp "$probe_output:$probe_cp" com.yingdao.aicommerce.finance.FinanceWorkspaceReadProbe --month 2026-10 --view fees --kind FEE
FINANCE_PERF_READ_ONLY=true "$probe_jdk/bin/java" -cp "$probe_output:$probe_cp" com.yingdao.aicommerce.finance.FinanceWorkspaceReadProbe --start 2026-10-02 --end 2026-10-15 --platform TIKTOK_SHOP --view orders --kind REFUND --page 2
```

日期必须明确使用 `--month YYYY-MM` 或成对 `--start YYYY-MM-DD --end YYYY-MM-DD`。还支持 `--shop`、`--group`、`--currency`、`--platform`。页类型支持 `settlements`（SETTLEMENT/BILL/PENDING）、`reconciliation`（BILL）、`orders`（ORDER/REFUND）、`fees`（FEE/COST）、`ads-support`（AD/AD_GMV/FEE）；`--all-orders true` 仅适用于 orders/ORDER。默认页大小为 50。主广告页的财务源与分配通过 spendFacts 对照覆盖，但不计时主广告展示报告。

## 结果与边界

- 用原始 `buildNativeDashboardFacts`、`buildNativeSpendFacts` 作为事实权威，与 `FinanceQueryProjections.find` 的完整 facts/spendFacts 逐字段比较。仅忽略 facts 的 `_financeDataRevision`、`_financeFactsBuilt`；不忽略任何财务字段。类型保留，精确规范化数值和 SQL 时间，事实集合排序但保留重复项，缺失/null/零严格区分。
- 真实数据库页先走原始精确计数，再走投影 rawCounts 提供总数，逐字段比较有序页和总数；有编号时两者都走原始计数，避免使用不匹配的无编号 facets。重复读取也保持页顺序。
- 每个条件同时比较完整原币事实；币种只筛实际页，符合 workspace nativeFilter 的原币事实口径。支持完整月、部分月、跨月以及店铺/分组/平台隔离。
- 仅输出固定操作标签、计数、哈希、耗时及布尔边界；不输出金额、订单号、店名、组织 ID、原始 JSON、SQL、异常堆栈或凭据。比较不等、未就绪、超时、异常均非零退出，错误码脱敏。
- 结果缓存全程关闭；“repeat”是同一快照内重复数据库读取，可能受数据库页缓存影响，不能称为热结果缓存。没有清理 Redis 或数据库缓存。
- 不覆盖 FX 换算、图表生成、HTTP、浏览器、后台同步并发或端到端 5 秒验收；不以探针成功代替真实页面验收。只证明当前 MVCC 快照中的已入库数据，不证明尚未同步的数据完整。
