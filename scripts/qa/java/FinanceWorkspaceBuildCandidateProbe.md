# 未发布增量候选只读诊断

`FinanceWorkspaceBuildCandidateProbe` 只诊断一个尚未发布的单月增量计算候选。它在同一个显式 MySQL `REPEATABLE READ / WITH CONSISTENT SNAPSHOT / READ ONLY` 物理快照内读取已有分区、调用当前源码中私有的 `FinanceQueryProjections.build(Key, previous)` 进行计算，并与同一快照中的原生完整事实、广告消耗和数据库页比较。它不调用领取、事件处理、请求、租约、发布或 worker 方法，也不写数据库、Redis、缓存或服务器文件。

## 私有编译与无数据库测试

使用当前 `target/classes` 和父任务已有的依赖 classpath；输出只放在 `/tmp`，不运行 Maven，不写共享 `target`。

```sh
probe_jdk=/Users/gotit/.codex/backups/ai-video/real-preview-20261106-imq070tu/toolchain/amazon-corretto-21.jdk/Contents/Home
probe_cp="target/classes:$(cat /tmp/finance-bi-performance-qa/dependency-classpath.txt)"
probe_output=/tmp/finance-bi-performance-qa/candidate-probe
mkdir -p "$probe_output"
"$probe_jdk/bin/javac" -cp "$probe_cp" -d "$probe_output" \
  scripts/qa/java/FinanceWorkspaceReadProbe.java \
  scripts/qa/java/FinanceWorkspaceBuildCandidateProbe.java \
  scripts/qa/java/FinanceWorkspaceBuildCandidateProbeTest.java
"$probe_jdk/bin/java" -cp "$probe_output:$probe_cp" \
  com.yingdao.aicommerce.finance.FinanceWorkspaceBuildCandidateProbeTest
```

## 协调后的真实只读运行

真实运行由维护窗口持有者在实际 runtime container 内继承现有 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD` 环境变量；凭据不得写入参数或日志。必须显式设置 `FINANCE_PERF_READ_ONLY=true`。可通过私有环境变量 `FINANCE_PROBE_ORGANIZATION_ID` 选定组织；编号筛选仍只读取 `FINANCE_PROBE_NUMBERS`。

运行前必须确认容器中已存在 GNU `timeout`。如果不存在或不是 GNU coreutils 实现，拒绝无界运行且不临时安装工具。保留每条查询的 180 秒限制，并额外用 210 秒的有限进程监督和 5 秒终止宽限覆盖连接、逐脏日多查询及 CPU 比较：

```sh
timeout_bin=$(command -v timeout) || {
  printf '%s\n' '{"operation":"candidate-supervisor-failed","error":"PROCESS_SUPERVISOR_UNAVAILABLE","candidateOnly":true,"publishedProjection":false,"cacheReadyVerified":false,"fiveSecondAcceptanceClaimed":false}'
  exit 125
}
"$timeout_bin" --version 2>/dev/null | grep -q 'GNU coreutils' || {
  printf '%s\n' '{"operation":"candidate-supervisor-failed","error":"PROCESS_SUPERVISOR_UNAVAILABLE","candidateOnly":true,"publishedProjection":false,"cacheReadyVerified":false,"fiveSecondAcceptanceClaimed":false}'
  exit 125
}
set +e
FINANCE_PERF_READ_ONLY=true "$timeout_bin" --signal=TERM --kill-after=5s 210s \
  java -Xmx768m -cp "$probe_output:$probe_cp" \
  com.yingdao.aicommerce.finance.FinanceWorkspaceBuildCandidateProbe \
  --month 2026-10 --platform TIKTOK_SHOP --view orders --kind REFUND --page 2
candidate_status=$?
set -e
case "$candidate_status" in
  0) ;;
  124)
    printf '%s\n' '{"operation":"candidate-supervisor-failed","error":"PROCESS_TIMEOUT","candidateOnly":true,"publishedProjection":false,"cacheReadyVerified":false,"fiveSecondAcceptanceClaimed":false}'
    exit 124
    ;;
  137)
    printf '%s\n' '{"operation":"candidate-supervisor-failed","error":"PROCESS_KILLED_AFTER_TIMEOUT","candidateOnly":true,"publishedProjection":false,"cacheReadyVerified":false,"fiveSecondAcceptanceClaimed":false}'
    exit 137
    ;;
  *) exit "$candidate_status" ;;
esac
```

只支持一个完整自然月，可使用 `--month YYYY-MM`，或精确的月首/月末 `--start`、`--end`。平台可省略；`--shop`、`--group`、`--currency`、部分月和跨月会显式拒绝。`view`、`kind`、`page`、`all-orders` 采用原只读分页探针的约束。

## 适用条件和结论边界

分区必须存在、schema 版本与当前源码一致、已有非零 `built_revision` 和全部三个已保存 payload、`full_rebuild=false`、`requested_revision>built_revision`，并且当前快照中存在脏日。缺失基线、需要完整重建、版本异常或其他不适用状态以固定 `candidate-not-applicable` 原因非零退出。若 `requested_revision==built_revision`，固定原因是中性的 `BASELINE_REVISIONS_EQUAL`：它只表示本诊断没有待计算的分区修订，不证明公共 READY。此时应运行原 `FinanceWorkspaceReadProbe`，由它独立检查 payload、修订和相关事件等完整发布围栏；持续导入期间它仍可能拒绝。

候选的 `buckets`、`orders`、`advertising`、当前 `reportingMonths`、广告 `shops/sources/assignments` 会与同快照原生数据逐字段比较；候选 `rawCounts` 只用于原分页规则下的精确有序页和总数比较。数值精度、缺失/null/零和重复项均保留。快照开始时只输出是否存在相关组织的待处理事件；仍在事件队列中、尚未映射为脏日的源变更可能产生真实不一致，这种不一致是证据，不是绕过发布围栏的理由。

每条探针输出和上述监督失败回执都固定包含 `candidateOnly=true`、`publishedProjection=false`、`cacheReadyVerified=false`、`fiveSecondAcceptanceClaimed=false`。退出码 124 和 137 明确是非成功，分别标记 `PROCESS_TIMEOUT` 和 `PROCESS_KILLED_AFTER_TIMEOUT`。成功只表示当前捕获源快照中的内部增量计算相等；已发布真实性、公共缓存有效性、上游完整覆盖、生产收敛和五秒门槛仍待独立验证。原 `FinanceWorkspaceReadProbe` 的 fail-closed 行为保持不变。
