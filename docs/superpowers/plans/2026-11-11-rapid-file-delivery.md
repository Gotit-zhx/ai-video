# 快速文件发布实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将已批准的小团队快速发布方案实际接通生产，使前端更新不排空或重启 Java、后端仅验证相关功能并更新版本化 JAR，移除普通发布的重复 CI/Candidate/源码/镜像传输。

**Architecture:** 在既有 heyi-ops 双锁、Store、Engine、systemd 执行器上增加一种明确的 files-v1 交付。固定现有 Docker runtime，完整 JAR 首版；文件组装、适配主机与薄客户端各一处，不建设新服务。旧镜像和回执保留，迁移时为当前及两个回滚生成独立文件映射，不修改原始正式附件。

**Tech Stack:** Python3 标准库、SSH、Compose、Nginx、Java21/Maven3.9.11。

**Spec:** [已批准选型](../../design/rapid-delivery-20261110.md)。

## Global Constraints

- 严格 Gitflow：feature 从 develop，no-ff PR、release、生产标签及必要合回；不绕过现有保护，新入口验证后才迁移保护/工作流。
- 保留业务卷、私有环境、数据库/Redis、备份、任务租约/检查点/限流；当前和两个已验回滚、10 GiB 安全余量与实际暂存预算。
- 当前生产窗口由广告会话独占；本地开发并行，迁移/安装/发布须待其交接并现场核对。
- files-v1 信任边界是经现有受控 SSH 认证的发布操作者；服务器实验字节/摘要/固定路径/运行基线，不将任意 passed=true 当成独立测试证明。
- 普通发布不在线读取 GitHub/GHCR、不传源码、不构建 runtime；源/正式合并在可信客户端一次核验。独立维护仍处理 migration/operations/unsupported 变化。
- 首版只用完整 JAR；标准 Boot 分层留在同一入口后续优化，不作为可运行首版前置。

## 固定接口（实现者共同遵守）

文件 manifest 保留旧 `version/source/images` 数据结构，新增 `schemaVersion:3, delivery:"files-v1", components:{app,frontend}, build`。`source` 继续为 commit/tree/ref/六类 inputs；`images` 是固定 runtime 镜像元数据，不代表新应用内容。每个 component 为 `{kind:"jar"|"static", identity:<64hex>, files:{relativePath:{sha256:<64hex>,size:<int>}}}`；identity 是 canonical JSON `{kind,files}` 的 SHA256。app 只含 app.jar，frontend 含原文/gzip/asset-manifest.json，不能含路径穿越或链接。`build` 记录真实执行的命令/退出结果/工具指纹，必须绑定 source；来源边界由 SSH 身份承担。

`FileArtifacts(root)`：`validate_manifest(value)`、`component_identity(component)`、`release_identity(target)`；`path(version)` 指向 root/file-releases/version；`component_path(name, descriptor)` 指向 root/file-artifacts/name/identity；`load(version)` 验 manifest+verified；`receive(target, baseline, stream, archive_bytes)` 在独占锁内验证完整目标视图并只接收差异 tar（成员 app/<path> 或 frontend/<path>），从验过的 baseline 复用未变文件，发布不可变目录+小回执。重复内容可复用，坏hash/缺失/多余/重复/链接/超额/尾随字节拒绝。

CLI 新动作 `ship-info` 返回 active 文件 manifest 与允许的非敏感基线；`ship VERSION EXPECTED_VERSION OPERATION_ID` 输入为有界一行 JSON `{manifest,archiveBytes}` 加准确 tar 字节。验证并持久化 request，再交现有 systemd executor；receipt/status沿用。所有 execute/recover/rollback/monitor 统一按 delivery/config 选择 adapter，不另建队列。

`FileHost(Host)` 接口与 Host 相同；复用 gate/drain/schema/request。文件模式 preflight 保留 schema/ops/unsupported/配置/容器集合/维护检查，只替换旧在线证明和 source/current；app 版本 mount 的 Source 只允许精确已验证 immutable path，其他配置/挂载完整保留。frontend 父目录固定挂载，current 指向 identity；切指针不 gate/drain/restart。app.jar 只读挂载 /app/app.jar，运行命令保持标准 java -jar。

## Task 1: 有界文件清单与增量组装

**Files:** create `ops/heyi-ops/heyi_ops/file_artifacts.py`, `ops/heyi-ops/tests/test_file_artifacts.py`。

- [ ] 写实际临时目录/BytesIO 测试：完整接收、只传改变文件、删除文件、新旧内容隔离；缺失/坏hash/重复/路径穿越/链接/超额/尾部输入拒绝；容量不足在激活前停止。
- [ ] `python3 -m unittest discover -s ops/heyi-ops/tests -p test_file_artifacts.py` 观察缺功能失败，再最小实现固定接口。
- [ ] 验证每个保留/复用文件的实际hash，组装完成后 rename；旧 artifact 不能被原地写坏。记录红绿证据并提交本任务文件。

## Task 2: 原生一次构建与薄发布客户端

**Files:** create `scripts/ship.py`, `scripts/ci/tests/test_ship.py`; modify `frontend/package.py` only if需新增完整文件hash辅助。

- [ ] 测试 frontend 变更不调用 Maven、app 缓存构建一次、相关测试失败不上传、dirty/源漂移拒绝、不可重试重建/重派发、shell 参数不拼接、只上传新/变化文件。
- [ ] 使用 Task1 manifest 接口；从 ship-info 实际 active 判定改变组件与 runtime，限制未维护的 ops/migration/unsupported。固定私有 JDK/Maven缓存，显式settings，相关 Java测试由参数明确指定并真实执行；前端做静态引用/语法/对应已映射 QA。
- [ ] 自动完成合法支持分支→受保护no-ff生产合并→标签；不覆盖tag，重复执行复用同源已构建产物和request。在新的保护入口验收前只允许prepare/dry-run，不能绕过旧门禁。
- [ ] 记录验证/构建/上传/激活/总用时，SSH参数数组、安全短输出，不输出凭据或业务数据。测试红绿后提交。

## Task 3: 主机适配与一次迁移

**Files:** create `ops/heyi-ops/heyi_ops/file_host.py`, `ops/heyi-ops/file_delivery_maintenance.py`, matching tests；必要时最小提取 Host公共检查，不改既有 additive compatibility 规则。

- [ ] 临时目录/配置fixture证明前端原子指针与fsync、app不可变只读mount、漂移拒绝、原文/gzip校验、失败恢复、旧镜像映射兼容。
- [ ] 迁移只读取已验收当前与两个回滚镜像中的程序文件，不 export 容器或卷；原始 release/verified/回执不改。保存精确Compose/配置与恢复材料，登记新允许挂载及不变配置hash。
- [ ] 受控双锁/systemd、同operation恢复、frontend一次受控重建与app安全排空；任一步失败恢复旧指针/配置/程序，外来漂移拒绝覆盖。只安装已受信候选来源。
- [ ] nginx root与相关绝对静态alias移到current，保留TLS、API/HR/ERP/download代理、安全头、gzip；nginx -t与实际HTTP检查。

## Task 4: 同一状态机、恢复、保留与运行版本

**Files:** modify `engine.py`, `cli.py`, `monitor.py`, relevant tests；必要时 private ops status 增加准确应用版本字段。

- [ ] 用失败测试证明 file identity 变化可识别、相同version不同manifest拒绝、frontend不门控、按变化组件回退和崩溃恢复；旧journal保持兼容。
- [ ] persist changed/requiresGate 后再副作用；execute/recover/finalize/restart/rollback都选同一adapter。新文件接收复用 Task1，不接受任意远端命令/路径。
- [ ] 保留按 runtime+artifact identity去重；保护当前+两个真实回滚、pending/live mounts和未知内容。只清理本入口拥有且无引用的文件；不能因固定runtime把版本合并成一个，也不能让父挂载永久阻止有界清理。
- [ ] 跑所有相关Python测试并独立整体验证；不重复跑无关Java全量。

## Task 5: 真实接通与精简关键路径

**Files:** `.github/workflows/`相关入口、`docs/guides/cicd*.md`, `docs/guides/operations.md`, `docs/design/rapid-delivery-20261110.md`, 本期真实验收记录。

- [ ] 待广告交接现场新基线，完成受信control安装、隔离Linux/Nginx/标准JAR启动和应用任务恢复测试。
- [ ] 迁移并实际发布前端与后端组件各一次，验证对应HTTP内容/版本、frontend不变容器ID/app不变ID、后端仅app变化、可恢复回退；记录五段真实耗时。复用实现所需真实功能变更或自然修复，不造十次资格发布。
- [ ] 新入口验收后，将全量CI放后台/夜间；PR只保留轻量Gitflow/必要影响检查，移除旧Candidate/readiness/重复构建/五分钟主机证明，保留单一备用构建入口。
- [ ] 按Gitflow完成feature→develop→release→生产标签及必要合回，归档版本→source→artifact→receipt，精确说明未验证边界。完成审计要求所有阶段均有实际证据。
