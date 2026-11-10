# CI/CD 与大陆服务器网络提效实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. 默认单会话顺序执行；用户另行明确授权时才采用代理并行。

**Goal:** 在零新增付费资源条件下，通过减少下载、增量传输、验证复用和发布编排，提高快速迭代效率，并保持现有生产保护。

**Architecture:** 保留 GitHub Actions、私有 GHCR、受限 SSH 与主机 systemd 执行器。先在旧完整验证/传输路径上改善缓存，再安装双协议控制面，最后启用基于证据的分级验证与一次命令发布。

**Tech Stack:** 现有 Python 3.9+ 标准库、GitHub CLI、GitHub Actions、Java 21/Maven、Node、Docker Buildx、OCI、Linux systemd；不新增生产服务、数据库结构或付费产品。

**Spec:** [大陆服务器 CI/CD 总体设计](../specs/2026-11-10-cicd-network-acceleration-design.md)。所有接口字段和默认值以该设计与本计划共同约束。

**Status:** 实现已进入审查及上线验证。实际通过项和待验证边界见[改造验收](../../releases/infrastructure/cicd-acceleration-20261110.md)；未勾选项不能视为已上线。

## Global Constraints

- 仓库固定为 `Gotit-zhx/ai-video`，Git 远端 `fork`；生产 `main-tree`，集成 `develop`；支持分支、no-ff、正式标签和所有必要合回遵循 AGENTS.md。
- 当前计划工作区 detached HEAD，有广告相关未提交修改。开始实现时从最新远端 develop 建立独立 `feature/cicd-network-acceleration` 工作区，不切换或重置当前共享工作区，不混入其他修改。
- 用户已确认：零新增付费资源、按风险分级验证、一条发布命令。不购买代理/仓库/Runner，不在生产机安装 CI Runner。
- 保持凭据密文、Cookie、私有配置、业务原件和完整导出不进入 Git、缓存、日志、回执或公开工作流产物。
- 安全余量为 10 GiB，另加实际峰值暂存空间。保留当前及两个已验近期回滚、所有容器引用、业务卷和迁移恢复输入。
- 所有生产变更按先主锁后兼容锁的固定顺序执行；CI 运行时不占用生产发布锁。
- 数据库集成测试只使用脚本创建的可销毁隔离库，保留每类独立库/账号和报告缺失/跳过审计。
- 不顺带升级 Java、FFmpeg、字体、框架或业务依赖；不改财务计算、权限、同步 checkpoint、租约与上游限流。
- 控制面和运维输入变更必须独立维护。来源证明、正式标签/树校验、数据库门禁和兼容回滚不能以加速为由关闭。
- v1 完整候选/旧正式版本继续可读；快速 v2 候选不能靠协议降级进入旧控制面。
- 运行日志脱敏；Actions 汇总保留 7 天，主机普通指标回执最多 30 天/50 MiB，当前/回滚/迁移恢复必需证据不参与普通清理。

## 任务地图与依赖

| 任务 | 独立交付 | 依赖 |
| --- | --- | --- |
| 1 | 分段指标与有界网络诊断 | 无 |
| 2 | 工具链、runtime 与构建缓存 | 1 的指标定义 |
| 3 | v1/v2 清单验证与 CI 证据校验 | 1 |
| 4 | 增量 OCI 与双协议主机通道 | 1、3 |
| 5 | 分级验证、编译产物共享与候选门禁 | 2、3 |
| 6 | 一条命令与可恢复 Gitflow 状态机 | 3、4、5 |
| 7 | 独立控制面维护、影子运行与开关切换 | 1～6 |
| 8 | 性能实测、恢复演练与正式文档 | 7 |

每个任务在支持分支按独立目的提交；不直接向 main-tree/develop 提交，不推送到原上游仓库。下列文件均为实施时的目标，不在本次计划阶段创建生产实现。

## Task 1：分段指标与有界网络诊断

**Files:** 新建 `scripts/ci/timing.py`、`scripts/ci/network_probe.py`；修改 `scripts/ci/remote.py`、`ops/heyi-ops/heyi_ops/artifacts.py`、`ops/heyi-ops/heyi_ops/host.py`；测试 `scripts/ci/tests/test_timing.py`、`test_network_probe.py`。

**Interfaces:**

- `TimingRecorder.measure(phase: str)` 为上下文管理器；`snapshot() -> dict` 返回 schemaVersion=1、phase 汇总和字节计数。
- `run_probe(config: dict) -> dict` 接收固定端点配置，返回 vantage、endpointHost、解析结果、连接/TLS/首字节/总时长、bytes、status、failureClass；不接受任意 shell。
- `failureClass` 仅为 `none/connect_timeout/tls_failed/sample_incomplete/http_error/dns_suspect/unresolved`，不写原始异常或签名 URL。

**计时字段约定：** phase 名使用总体设计中的 12 个固定名称；每个记录为 `{phase, durationMs, bytes, status}`。本地阶段使用本地单调时钟，服务端阶段使用服务端单调时钟。聚合记录同时保留 logicalBytes、uploadedBytes、cachedBytes。

- [ ] 为以下失败/预算语义建立测试并确认旧实现不能满足：35 秒采样未完成标记 sample_incomplete，不当作完全不可达；没有成功的可信 DNS 对照不能返回 repaired；未知 phase、负计数、超预算请求拒绝。

```python
import unittest
from network_probe import classify_sample

class SampleMeaningTest(unittest.TestCase):
    def test_deadline_with_partial_payload_is_not_total_outage(self):
        value = classify_sample(exit_code=28, connected=True,
                                tls_ok=True, received_bytes=858643)
        self.assertEqual('sample_incomplete', value)
```

`classify_sample(exit_code: int, connected: bool, tls_ok: bool, received_bytes: int) -> str` 是本任务实现的纯分类函数；连接未建立的预算耗尽才是 connect_timeout，TLS 建立失败归 tls_failed。

- [ ] 实现 48 MiB/150 秒总诊断预算、单请求 8 MiB/35 秒、并发 2、单文件最多 3 次尝试且总 300 秒的下载预算；按单调时钟计算剩余预算，不在每次重试重置。
- [ ] 在真实下载、OCI 导出、接收、校验和导入边界记录时间；接收最后一个字节与返回最终 JSON 分开记录，不能把整个 SSH 调用当纯上传。
- [ ] 增加 `--dry-run` 网络诊断参数，仅列出固定公共端点与预算；不读取代理密码，不把业务 API token 带入诊断。
- [ ] 如复核 DNS，仅记录相关 hosts/NSS/解析器链与证书验证结果。可信解析通道失败则停止自动修复；将精确 DNS 维护作为单独评审项。
- [ ] 运行 `python3 -m unittest discover -s scripts/ci/tests -v`，检查日志中无令牌、签名查询或原始 stderr；独立提交本任务。

**验收：** 可从一次发布分别读出注册表获取、上传接收、验证、导入与激活时间；三类已观察网络现象不会混算为同一条链路。

## Task 2：减少工具链下载与重复 runtime 构建

**Files:** 修改 `.github/actions/toolchain/action.yml`、`Dockerfile`、`frontend/Dockerfile`、`scripts/ci/build.py`、`frontend/package.py`；新增 `src/main/docker/runtime.Dockerfile`、`src/main/docker/runtime.json`、`scripts/ci/runtime_recipe.py`；测试 `scripts/ci/tests/test_runtime_recipe.py`、现有前端资源检查。

**Interfaces:**

- 工具链 action 输入 `java/maven/node/media` 四个布尔值，默认均为 true 保持现有调用兼容；调用方逐步显式缩小范围。
- `runtime.json` 记录固定平台、基础 JRE digest、FFmpeg/字体及依赖 BOM 的校验摘要；不能包含凭据。
- runtime 镜像写入现有私有 app 包，标签为 `runtime-git-<完整提交>`；最终清单记录 runtimeBaseDigest/runtimeRecipeDigest。复用此前已验摘要，不依赖可变 latest 标签。
- 后端仍提供 `/app/app.jar`；本任务先保留现有 Maven 构建并将原 JAR 放入固定 runtime。从已验证共享 JAR 包装在 Task 5 切换，不提前依赖尚未实现的编译产物接口，不改迁移/诊断加载约定。

- [ ] 先建立 recipe 失效测试：依赖/工具链/runtime 配方变化必须失效，纯源码修改不重建相同 runtime；缓存对象缺少合格证明时不复用。

```python
import unittest
from runtime_recipe import recipe_digest

class RuntimeRecipeTest(unittest.TestCase):
    def test_dependency_change_invalidates_runtime(self):
        base = {'jre': 'sha256:' + 'a' * 64,
                'platform': 'linux/amd64', 'bomSha256': 'b' * 64}
        changed = dict(base, bomSha256='c' * 64)
        self.assertNotEqual(recipe_digest(base), recipe_digest(changed))
```

`runtime_recipe.py` 放在 `scripts/ci/`，`recipe_digest(recipe: dict) -> str` 对固定键的 canonical JSON 计算 SHA-256；未知键、可变镜像引用和缺少 BOM 摘要拒绝。

- [ ] 从当前已验运行镜像核对 Java、FFmpeg 和字体 BOM，锁定现有依赖。首次 runtime 构建保留 HTTPS/APT 签名；依赖不可复现时停止，不自动换版本。
- [ ] 拆分 action 安装范围：前端 Node-only；Java 使用 JDK/Maven；媒体能力由明确的测试套件映射决定。保持版本、归档校验和缓存损坏恢复。
- [ ] 开启现有私有 app/frontend 包中的独立 Buildx registry cache；只有受信任候选可写缓存。缓存标签与 runtime/发布标签分开，避免覆盖不可变产物。
- [ ] 稳定前端层包含 vendor/assets，经常修改层包含脚本/样式/页面；保持完整资源清单和固定 gzip，动态引用仍由回归覆盖。
- [ ] 冷/热各运行一次隔离容器验证，比较 `/app/app.jar`、依赖 BOM、健康/鉴权、字体和媒体处理、静态资源完整性；记录网络与实际上传字节。
- [ ] 运行必要策略、Java、前端和容器检查；使用原完整验证与 v1 传输完成首轮发布，不借本阶段启用 fast profile。

**验收：** 常规源码变化不执行系统依赖安装；热准备目标 P50≤30 秒/P95≤60 秒；首轮冷构建单列，不混入热路径统计。

## Task 3：清单双读与验证证据的信任边界

**Files:** 修改 `scripts/ci/release.py` 及其主机安装副本约定；新增 `scripts/ci/evidence.py`；测试 `scripts/ci/tests/test_evidence.py`、`test_release.py` 与 `ops/heyi-ops/tests/test_schema_compatibility.py`。

**Interfaces:**

- `validate_manifest(value, require_smoke=True)` 双读 schemaVersion 1/2，旧身份/镜像/摘要校验保留。
- v2 validation 必需：`profile` 为 docs/module/full，`policyDigest`、`toolchainDigest` 为 SHA-256，`suiteInputDigests` 为 suite→SHA-256 映射，`requiredSuites` 为去重列表，`evidence` 为证明引用列表，result=passed、unexpectedSkips=0。
- `verify_evidence(evidence, request, run_metadata, artifact_bytes) -> dict` 先核对真实 GitHub run/attempt、仓库、workflow、合成树、状态与产物摘要，返回已核验记录。
- `can_reuse(verified_evidence, request) -> bool` 只处理上一步输出，要求 repository/tree/policyDigest/toolchainDigest 及请求 suite 的输入摘要一致，requiredSuites 为 passedSuites 子集；原 head 与目标提交可不同，但完整有效树必须一致。

- [ ] 将现有 v1 夹具作为兼容基线，添加 v2 缺字段、未知 profile、意外跳过、伪造成功、fork 来源、错误树/规则/摘要的拒绝测试。

```python
import unittest
from evidence import can_reuse

class CoverageTest(unittest.TestCase):
    def test_missing_suite_is_not_a_cache_hit(self):
        common = {'repository': 'Gotit-zhx/ai-video', 'tree': 'a' * 40,
                  'policyDigest': 'b' * 64, 'toolchainDigest': 'c' * 64,
                  'suiteInputDigests': {'core-policy': 'd' * 64, 'java-unit': 'e' * 64}}
        verified = dict(common, passedSuites=['core-policy'], result='passed')
        request = dict(common, requiredSuites=['core-policy', 'java-unit'])
        self.assertFalse(can_reuse(verified, request))
```

- [ ] 通过 GitHub API 元数据和下载产物的 SHA-256 验证来源，不使用 artifact 内部的 trusted=true 等声明作为信任来源；外部 fork 不能升级为发布凭据持有者。
- [ ] policyDigest 覆盖 suite 映射、工作流和测试/构建脚本；toolchainDigest 覆盖锁定工具链/依赖输入；suiteInputDigests 覆盖对应测试源码、继承夹具与相关辅助输入。普通测试增补只使其套件证明失效，选择/执行规则变化仍强制 full。缺少必需输入不得沿用旧证明。
- [ ] 在 v2 构建证明中增加 compiledArtifactSha256/buildRecipeDigest；打包规则变化时即使 app 源码未变也拒绝旧构建复用。
- [ ] v1 仅保留完整验证语义；首个 v2 和规则变化均强制 full；旧缺少构建证明的产物默认重建一次。
- [ ] 跑全部策略/运维验证，保存 v1 正式/回滚、v2 合法、v2 拒绝样例。此阶段只完成能力，暂不将普通流水线切到快速 v2。

**验收：** 旧已验发布仍有资格，新快速候选不能被旧格式、缓存或伪造 CI 记录放行。

## Task 4：增量 OCI、预算与可恢复上传

**Files:** 新建 `ops/heyi-ops/heyi_ops/transfer.py`；修改 `oci.py`、`artifacts.py`、`cli.py`、`scripts/ci/remote.py`；扩展 `ops/heyi-ops/tests/test_oci.py`、`test_staging.py`、`test_storage.py`、`scripts/ci/tests/test_remote.py`。

**Interfaces:**

- `plan_missing(descriptors, open_cached) -> list[dict]`：只消费已核验 graph 的描述符；open_cached 返回二进制流或 None。存在内容的大小/哈希错误抛 `CacheIntegrityError`，不按命中跳过。
- 新受限命令 `stage-plan VERSION PLAN_ID`：读取既有 release/source 固定名帧，加每个组件≤1 MiB 的 metadata 帧；metadata 是 digest→base64 的 root manifest/config 映射，不包含 layer 内容。
- `status` 加 `releaseSchemas:[1,2]` 和 `transferProtocols:[1,2]`。旧响应缺字段按仅 v1 处理。
- 新 stage 输入 `{protocol:2,planId,blobs:[{digest,size}]}` 后接按 digest 升序的缺失内容；旧 stage 输入保持兼容。
- 响应与错误按总体设计：30 分钟计划 TTL、manifest/生产基线绑定、delta/full、needs_replan/needs_repair，不开放任意路径。

- [ ] 先写增量集合与缓存完整性测试；原完整 OCI fixture 继续通过。

```python
import hashlib
import io
import unittest
from heyi_ops.transfer import plan_missing, CacheIntegrityError

def descriptor(value):
    return {'digest': 'sha256:' + hashlib.sha256(value).hexdigest(), 'size': len(value)}

class MissingBlobTest(unittest.TestCase):
    def test_only_new_layer_is_requested(self):
        old, new = descriptor(b'base'), descriptor(b'new')
        def cached(digest):
            return io.BytesIO(b'base') if digest == old['digest'] else None
        self.assertEqual([new], plan_missing([old, new], cached))

    def test_corrupt_existing_content_is_not_trusted(self):
        with self.assertRaises(CacheIntegrityError):
            plan_missing([descriptor(b'base')], lambda digest: io.BytesIO(b'evil'))
```

- [ ] 服务端在读缓存前验证正式标签/候选、来源证明、source、root/config、linux/amd64 和固定仓库。digest 必须来自已签清单的 graph，最多 128 层，拒绝外部 layer URL。
- [ ] 缓存通过受支持的容器工具只读访问，不能直接写底层目录。损坏返回 needs_repair；仅由标准完整导入尝试恢复，仍不一致则保持旧服务交接。
- [ ] 计划写在本操作私有目录，保存非敏感验证回执、缺失集合和恢复状态；令牌仅在标准输入/内存存在，计划到期需重新验证。
- [ ] remote 从文件分块写入 stdin，避免将完整 OCI 与 source 多次拼接为大 bytes；计时保留写入结束、服务端接收结束和最终响应的区别。
- [ ] 完整重建 OCI 后沿用原安全校验，拒绝截断、额外字节、重复成员、未知 digest、错误大小和任意文件名；实际导入摘要必须匹配签名。
- [ ] 覆盖缓存被回收、接收中断、同 ID 重试、计划过期和生产 HEAD 漂移。已有正确 blob 不重复接收；失败不会清空已入库业务数据。
- [ ] 空间按接收/完整重建/导入/source 解包峰值计算，保留 10 GiB；classic driver 无法读 blob 时走 full。v2 快速候选碰到旧控制面时中止，不降级门禁。
- [ ] 使用隔离容器演练全量/增量导入后的 manifest/config 一致，以及缺层、篡改、重启和清理后恢复；不得用生产业务卷测试。

**验收：** 同一候选两种传输得到同一 digest；基底未变时后端上传目标约 69 MB 加当前约 30 MB source；这不是所有版本都必达的硬编码大小。

## Task 5：分级验证、一次编译和候选完成门禁

**Files:** 新建 `scripts/ci/scope.py`、`scripts/ci/test-scopes.json`；修改 CI/Candidate 工作流、`java.py`、`run.py`、`build.py`、`candidate.py`；测试 `test_scope.py`、`test_evidence.py`、`test_candidate.py` 和 required 汇总策略。

**Interfaces:**

- `classify_changed_paths(paths, context) -> dict` 返回 profile、requiredSuites、requiresHostMaintenance、unsupported、reasonCodes。context 包括最近全量结果/年龄；未知路径进入 full。
- reasonCodes 使用 `unknown_path/no_full_baseline/full_failed/full_stale/high_risk/unmapped_test/mapped_module/docs_only/host_maintenance/unsupported_component`，作为稳定可审计分类，不写任意原始错误。
- suite catalog 至少定义 core-policy、frontend-assets、finance-frontend、java-unit、各后端模块 DB、all-db、all-frontend、container-smoke、systemd-recovery，并列出真实类/脚本与媒体需求；不能保留空映射。
- `HEYI_FAST_CI_ENABLED` 默认 false；false 时仍完整验证。true 时按规则选择，第一轮 v2、规则变化、最近全量失败或年龄>48 小时均 full。
- Candidate 支持 dispatch 输入 `expected_head`、`validation_run`、`validation_attempt`；先核对证明再使用 packages/OIDC 写权限，完成检查名为 Release ready。

- [ ] 使用真实路径建立分级真值表，覆盖当前财务公共前端、财务后端、未知文件、文档、依赖、迁移与运维输入。

```python
import unittest
from scope import classify_changed_paths

class ScopeTest(unittest.TestCase):
    def test_finance_frontend_does_not_require_unrelated_db_classes(self):
        scope = classify_changed_paths(['frontend/finance-workspace.js'],
                                       {'lastFullPassed': True, 'lastFullAgeHours': 1})
        self.assertIn('finance-frontend', scope['requiredSuites'])
        self.assertNotIn('all-db', scope['requiredSuites'])

    def test_unknown_path_uses_full(self):
        value = classify_changed_paths(['unknown.bin'],
                                       {'lastFullPassed': True, 'lastFullAgeHours': 1})
        self.assertEqual('full', value['profile'])
        self.assertIn('unknown_path', value['reasonCodes'])
```

- [ ] 以已有测试 inventory 生成 catalog 并人工核对分类一次；配置保存模块路径/类模式和夹具规则，每次展开为真实类/脚本清单。新增未映射测试强制 full 并纳入完整 inventory；即使 full 仍无法纳入的测试或空 suite 使 policy 失败。共享财务前端映射完整财务 UI，不按文件中的某个页面名称猜测影响。
- [ ] 编译任务保存 JAR、classes/test-classes、依赖与规则摘要；单元/DB 分片使用已编译内容并保留每类独立库/账号。候选包装同一已验证 JAR，运行实际镜像组合冒烟。
- [ ] CI required 始终运行，只允许计划内 skipped。发布和合回仅复用覆盖充分的真实树证明；新组合补齐检查，不一概跳过。
- [ ] Candidate 从 push 全量双跑切换为显式 dispatch，命令等待 CI 后触发；启用前保留现有人工 dispatch 兼容入口。不要用 fork PR 完成事件直接启动有凭据的发布。
- [ ] 添加每天 `0 18 * * *` 对 develop 的完整回归；结果用于下一候选的 full 强制条件，不因测试失败自动回滚线上。
- [ ] 先影子判定并仍运行全量，至少覆盖文档、前端、普通后端、财务后端、迁移/规则与未知路径；需要全部相关测试和失败案例都被新范围覆盖，才能启用分级。

**验收：** 同一有效树/规则下已成功套件不重复运行；证明不足自动补测；财务/权限/迁移/基础设施仍完整回归。

## Task 6：一次命令与恢复状态机

**Files:** 新建 `scripts/ci/release_flow.py`；扩展 `scripts/ci/tests/test_release_flow.py`；复用 promote、remote、release/evidence 模块。

**Interfaces:**

- `--source develop`、`--source hotfix/<主题>`、`--dry-run`、`--resume <32hex>`；固定个人 fork，不接受任意仓库/生产目标。
- `next_version(tags, occupied_versions, shanghai_date) -> str`：当天首版无序号，后续取未占用的最大序号+1；已占用活动分支也参与判断。
- `next_action(journal, observed) -> str` 是纯决策函数，按设计状态机返回动作；requires_maintenance/conflict/baseline_changed 明确停止。
- journal/锁存放 Git common dir 的 heyi-release-flow 目录，只保存提交、版本、PR、run/attempt、操作 ID 和阶段；不记录凭据。

- [ ] 使用 fake GitHub/host API 测试版本占用、候选未就绪、基线漂移、部分成功和恢复，不向真实仓库写测试 PR/标签。

```python
import unittest
from release_flow import next_version, next_action

class FlowTest(unittest.TestCase):
    def test_version_does_not_reuse_a_reserved_release(self):
        self.assertEqual('v2026.11.10.5', next_version(
            ['v2026.11.10.3'], ['v2026.11.10.4'], '2026-11-10'))

    def test_resume_accepted_deploy_continues_to_backmerge(self):
        action = next_action({'phase': 'deploying', 'version': 'v2026.11.10.5',
                              'operationId': 'a' * 32},
                             {'deploymentPhase': 'succeeded', 'version': 'v2026.11.10.5',
                              'operationId': 'a' * 32, 'backmergesComplete': False})
        self.assertEqual('backmerge', action)
```

- [ ] dry-run 只读显示远端 source/base、版本、变更范围、维护需求、已有候选和拟执行步骤；检查登录权限、工作区脏状态、活动发布及并行维护。
- [ ] 相对正式版本只有文档变化时返回 no_runtime_release，不创建标签/候选/部署，文档随下一次应用发布同步；为该退出路径增加 fake API 无写调用测试。
- [ ] 正常发布只从远端 develop 创建 release；hotfix 校验生产来源。每个副作用前刷新观察状态，以匹配 head/base 的 no-ff 合并，不强推、不自动解决业务冲突。
- [ ] 按 existing PR CI → dispatch candidate → CI required/Release ready → merge → 检查第二父/树 → tag → CD/receipt 顺序推进。继续使用现有用户 gh 登录，避免自动化令牌递归触发限制。
- [ ] 自动处理 production/develop/活动 release 的必要合回；合回新组合重新验证。完成所有验收和合回前不能标 complete 或清理临时分支。
- [ ] 恢复优先读同一 operation，版本/operation 不匹配返回 operation_mismatch，不能继续合回；已完成的合并/标签/部署不重复执行。部署失败保留回执，按当前数据库兼容规则回退；迁移/控制面维护缺失停在 requires_maintenance。
- [ ] 主机成功而附件上传失败时保留 receipt_pending，从同一受信任部署 run 的成功回执补传；新增 fake API 用例证明该分支只执行 publish_receipt，不调用 deploy 或 build。
- [ ] `HEYI_RELEASE_FLOW_ENABLED` 默认 false；关闭时只允许 dry-run 和查询恢复状态。不要提供绕过门禁的 force/skip-tests 选项。

**验收：** 无例外时只输入一次命令；中断、并发、重试和失败都保留工作与明确接手点。

## Task 7：独立维护、影子运行与开关切换

**Files:** 保持现有主机维护安装方式；更新 `docs/guides/cicd.md` 和本任务正式回执。不得改写原 verified.json/release.json。

**Interfaces:** 开关仅为 `HEYI_FAST_CI_ENABLED`、`HEYI_DELTA_TRANSFER_ENABLED`、`HEYI_RELEASE_FLOW_ENABLED`，默认均 false。v1 full 作为过渡路径；首次 fast 开启后首个 v2 必须 full。

- [ ] 实施前重新读取实际容器/镜像/配置/Compose/迁移/回滚资格与并行维护；本文旧快照不能作为安装参数。
- [ ] 从完整 CI 与来源证明通过的 **v1 full** 候选安装新控制面，保存私有程序/配置恢复点，实际验证 v1/v2 解析、旧应用、schema 兼容和锁协议，再登记 operations 指纹。
- [ ] 此时仍使用完整传输/完整验证，完成一次正常发布和已有兼容回滚演练；不因控制面更新重启备份 timer 或补跑备份。
- [ ] 启用 DELTA，先对完整验证候选演练成功/中断/缺层/全量回退，核对生产摘要、10 GiB+峰值预算、凭据清理和工作器恢复。
- [ ] 影子范围真值表通过后启用 FAST，执行第一个 full v2；确认历史 v1 回滚资格保留，然后验证前端 module 候选和高风险 full 候选。
- [ ] 真实候选已生成原生 Release ready 检查后，才将该检查加入 main-tree 必需门禁；CI required 保留。不能先删除旧门禁或提前制造缺失检查阻塞。
- [ ] CLI 的 fake/API 恢复演练与 dry-run 通过后启用 RELEASE_FLOW。未满足既有审查/维护/冲突条件时等待或明确停止，不能管理员绕过。
- [ ] DNS 维护如需执行另开精确维护记录；可信解析/TLS/业务域名复测不通过就恢复，不与控制面切换捆绑扩大变更。

**验收：** 先有兼容能力和可信回执，再启用新生产行为；任何阶段可关闭相应开关并保留合格旧路径。

## Task 8：实测验收与交付

**Files:** 新建 `scripts/ci/performance_summary.py`、`scripts/ci/tests/test_performance_summary.py`；更新 `docs/guides/cicd.md`、设计/计划状态；新增实际发布的验收回执与脱敏 performance summary。

**Interfaces:** `summarize(samples, minimum_samples=10) -> dict` 按 releaseType/profile/cacheState 分组，输出 schemaVersion=1、sampleCount、status、timeToAcceptedMs、flowCompleteMs、P50/P95 和各阶段字节/时长。百分位采用 nearest-rank；组内不足 10 条返回 insufficient_samples。普通网络波动与下载重试保留在正常样本；外部明确事故、冷构建、独立维护及人工冲突分别报告，不删除原记录。

- [ ] 先验证分组、样本不足与百分位定义，再实现汇总，防止混用冷/热或不同风险类型得出虚假的改善。

```python
import unittest
from performance_summary import nearest_rank

class PercentileTest(unittest.TestCase):
    def test_ten_samples_use_nearest_rank(self):
        self.assertEqual(5, nearest_rank(list(range(1, 11)), 0.5))
        self.assertEqual(10, nearest_rank(list(range(1, 11)), 0.95))
```

`nearest_rank(values, probability)` 要求非空数值列表且 0<probability≤1，返回排序后第 ceil(probability×样本数) 个值；不修改输入。

- [ ] 跑完整的策略、运维、Java、隔离数据库、前端、实际镜像冒烟和 systemd 恢复；显式记录失败、跳过和未验证边界。
- [ ] 对冷/热、前端/普通后端/高风险各保留可比较样本，取真实上传字节、同机阶段耗时和总耗时，不用 ICMP 或 API 首字节延迟替代大文件吞吐。
- [ ] 每个拟宣称达标的发布类型至少取 10 个可比热样本，报告 P50/P95；不足 10 条只报告样本。上线 P50 目标前端≤5分钟、普通后端≤10分钟、高风险≤15分钟，P95 先报告实际值。上线计时到客户端确认可信主机成功回执；整条流程计时包含合回与回执归档，分别呈现。
- [ ] 验证匿名鉴权、私有维护协议、实际镜像/配置、静态文件/gzip、后台领取恢复与业务范围；不得把回补开始说成完整财务对账。
- [ ] 验证当前+两个近期回滚和业务卷/备份保留，清理仅限本任务无引用 staging；主机摘要 30天/50MiB，Actions 7天，必要恢复材料永久保留至对应版本退出保护。
- [ ] 保存版本、生产提交、候选、制品 digest、验证 profile、run、host operation、维护回执和必要合回的对应关系，完成 Gitflow 后再清理分支。

**实施时验证入口：**

```bash
python3 -m unittest discover -s scripts/ci/tests -v
python3 -m unittest discover -s ops/heyi-ops/tests -v
python3 scripts/ci/java.py unit
python3 scripts/ci/java.py integration --shards 3 --shard 0
python3 scripts/ci/java.py integration --shards 3 --shard 1
python3 scripts/ci/java.py integration --shards 3 --shard 2
python3 scripts/ci/run.py frontend
sudo --preserve-env=GITHUB_ACTIONS,RUNNER_ENVIRONMENT python3 scripts/ci/container.py --legacy-gate
sudo --preserve-env=GITHUB_ACTIONS,RUNNER_ENVIRONMENT python3 scripts/ci/systemd_test.py
```

容器/systemd 检查在现有受控 CI 环境执行，systemd 检查不得直接在 macOS 或业务生产机运行；数据库命令仅指向脚本自建测试容器。测试代码示例在各任务中先新增、先观察失败、再实现通过。

## 完成判定与未授权范围

完成实现需要八项任务逐项有实际证据；本文形成不等于这些功能已完成。用户当前请求仅交付全面计划，本轮不执行上述命令、不修改流水线/服务器、不购买资源，不把其他广告修改提交到本任务。

允许未来单独评估但不纳入本次默认实现：付费国内仓库/构建机、全局代理、技术栈迁移、Spring Boot 解包分层及其诊断兼容改造、通用数据库迁移执行平台。若后续实测表明零新增资源仍不能达到时长目标，先出具分段证据和收益/费用对照，再取得用户对新增资源的明确决定。
