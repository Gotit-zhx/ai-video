# GitHub Actions 与统一运维

本指南描述一期实现及接通步骤。启用状态以[一期验收记录](../releases/infrastructure/devops-phase1-20261106.md)为准，不能把仓库中的工作流文件视为生产已经接通。生产分支为 `main-tree`，集成分支为 `develop`，仍按 [AGENTS.md](../../AGENTS.md) 使用 Gitflow。

## 构建与验证

语言、工具和基础镜像固定在 `scripts/ci/runtime.json`。前端测试依赖独立放在 `scripts/ci/qa`，不向仓库根增加 Node 包。安装指定 JDK、Maven、Node、FFmpeg 和 Docker 后运行：

```bash
npm --prefix scripts/ci/qa ci --ignore-scripts
python3 scripts/ci/java.py unit
python3 scripts/ci/java.py integration
python3 scripts/ci/run.py frontend
python3 scripts/ci/container.py
python3 -m unittest discover -s scripts/ci/tests -v
python3 -m unittest discover -s ops/heyi-ops/tests -v
```

数据库测试创建本次专属 MySQL/Redis 容器，每个测试类独享空库和仅限该库的账号。清理仅删除这些有唯一名称的容器、库和账号。继承财务夹具的测试也分别建库，不能指向真实业务数据库。报告缺失、必需测试跳过和失败都会使 CI 失败。私有工作簿、历史真实素材及真实 OSS 验收的可选边界集中在 `scripts/ci/optional-tests.json`；这些项目不计入必需回归通过。

`frontend/package.py` 是生产静态资源打包入口，校验 HTML、CSS 及 JavaScript 字面量资源引用，生成 SHA-256 资源清单与固定时间戳 gzip。生产镜像不再手工列举每个脚本。无法静态求值的业务 API/用户内容路径仍需相应页面测试。

## 四条流水线

| 入口 | 触发 | 验证与产物 |
| --- | --- | --- |
| `CI` | PR、develop、手动、候选复用 | Gitflow、策略测试、Java 单元及隔离数据库、前端回归；稳定汇总检查 `CI required` |
| `Candidate artifacts` | release/hotfix 分支、手动 | 仅本仓库受信任的版本分支；linux/amd64 全量构建、隔离容器冒烟、私有 GHCR、来源证明、候选 Release |
| `Production deployment` | `v*` 标签、手动重试 | 当前生产 HEAD、no-ff 第二父提交、候选树、来源证明、不可变镜像、主机执行及回执 |
| `Operations` | 每五分钟、手动 | 外部 HTTPS、只读主机诊断、去重 Issue；已验收版本回滚 |

第三方 Actions 固定完整提交 SHA。PR 没有生产凭据；镜像发布用 `GITHUB_TOKEN`。生产预取只接收当前任务的短期读取令牌，经 SSH 标准输入传入，独立 systemd 切换前删除临时认证文件。保存的回滚镜像无需长期 GHCR 密钥。

新 GHCR 包的可见性不能依赖默认值。候选任务先检查两个包的 Private 状态及仓库关联；包尚不存在时，仅推送不含任何应用文件的 scratch 初始化镜像。设置私有性后重试发布任务，才允许构建应用镜像。已有候选标签不覆盖；失败任务如果已经生成应用镜像，需要新提交生成新的候选身份。

正式候选只构建一次；不要重跑相同提交来覆盖镜像或候选附件。修正失败候选应在支持分支产生新提交。候选保存为 `candidate-<完整提交>` 的预发布，正式版本仅在生产 `--no-ff` 合并提交打 `v年.月.日[.序号]` 标签。候选分支名称为 `release/<版本>-<主题>` 或 `hotfix/<版本>-<主题>`。

`HEYI_CD_ENABLED` 和 `HEYI_OPS_ENABLED` 仓库变量是接通开关，默认缺失时不执行生产发布、回滚或巡检。首次迁移先通过 Production deployment 的 `prepare` 模式验证并预取正式制品，再由 root 提交 `heyi-ops adopt VERSION LEGACY_VERSION OPERATION_ID`。验收前不打开开关。初始化账号使用 `bootstrap.py identities`，只接收两份独立 Ed25519 公钥，强制命令与 sudoers 均由 root 管理。

首次迁移另外核对旧标签所有源码文件与现场快照，通过不启动的配置探针验证固定 Compose 等价性。旧 Java 尚无维护协议，因此仅在其网络命名空间临时将新 8080 连接转到 503 响应器，并让前端停止接收业务请求；已有连接必须结束。空闲财务工作器只预留本次 UUID 的租约，不改变计划时间或检查点；外来及未知租约会阻止迁移。安全点确认后短暂冻结旧应用以关闭检查与领取之间的竞争，再执行有界优雅停止。恢复逻辑随版本化执行器永久安装，不依赖上传目录。此首次迁移模块必须完成隔离演练后才能在生产启用。

完整容器测试在内部 Docker 网络运行独立探针，不依赖宿主机端口映射，也不访问生产或外部 API。CI 还在 GitHub 临时 Linux 机执行真实 systemd 单元测试，验证提交进程退出后继续完成，以及执行器被终止后的 ExecStopPost 恢复。合成诊断只在临时测试环境有界保存，上传内容仍只有摘要。

`release.json` 保存源码提交/树、组件指纹、迁移指纹、源码包校验值、OCI manifest digest、镜像 config digest、空间预算、前端资源指纹和验证结果。标签树必须等于候选树，不能在验收后再提交回执来改变正式候选；回执进入 GitHub Release 附件及服务器。

仅文档变更复用已有镜像、同步源码并跳过容器切换。其他常驻服务、桌面应用、配置目录或 Compose 的变更会阻止普通自动上线。运维执行器与备份脚本另有 `operations` 指纹；这类更改必须先完成明确的主机维护和基线更新，再进入应用发布，不能仅同步源码就声称运维程序已更新。

## 生产控制面

程序源码位于 `ops/heyi-ops`；生产入口为 `/usr/local/sbin/heyi-ops`。固定配置 `/etc/heyi-ops/config.json`，状态根目录 `/opt/heyi/ops`。为兼容并行会话，按固定顺序先取 `/run/lock/heyi-application-release.lock`，再取旧 `/opt/heyi/operations/.project-release.lock`；两者都成功才可改变生产状态，不删除锁文件。

| 命令 | 用途 |
| --- | --- |
| `status` / `doctor` | 当前版本、维护状态、服务健康及脱敏故障摘要 |
| `stage VERSION` | 从本仓库下载候选、验证证明、预算磁盘并预取不可变镜像；凭据仅从标准输入读取 |
| `preflight VERSION` | 核对实际基线、配置、迁移、空间和活动维护 |
| `deploy VERSION EXPECTED_VERSION OPERATION_ID` | 提交持久化请求，由 systemd 独立执行 |
| `receipt OPERATION_ID` | 查看执行结果，SSH 中断后仍可查询 |
| `rollback VERSION EXPECTED_VERSION OPERATION_ID` | 选择曾验收且仍保留的兼容版本 |
| `recover` | 在共享锁内恢复中断操作和本次维护门控 |
| `cleanup` | 仅清理本流程拥有、无容器引用且超过保留范围的制品 |

`OPERATION_ID` 为 32 位小写十六进制，同一请求可重复查询，但不能用于不同目标。GitHub 生产操作排队、不取消已执行的任务；服务器锁同时约束人工调用。旧脚本不自动遵守新入口，使用旧脚本前仍须协调所有会话并持有同一共享锁。

固定 Compose 链为 `compose/base.json`、`compose/production.json`、版本目录 `images.json`。私有环境继续留在服务器；初次收敛必须从现场完整配置链导出，并逐项比较生效环境、端口、网络、挂载、资源和日志策略，不使用开发 Compose 覆盖生产。

维护标记通过只读目录挂载进入 Java。请求入口在鉴权之前返回现有 API 格式的 `503` 和 `Retry-After`，健康检查继续可用。Spring 调度和财务自有调度停止领取新工作，已领取工作继续完成；发布器等待请求、后台工作及数据库业务安全点。成本队列积压保留，不要求队列归零，也不修改计划、检查点或上游限流。

状态在副作用前落盘，SSH/Actions 中断不终止 systemd 执行器。验收失败只在数据库结构兼容时恢复应用；迁移新增、历史校验变化及配置漂移必须在切换前阻止。`recover` 不修改其他操作拥有的标记。恢复失败进入 `needs_attention`，保留证据并告警。

## 监控、备份与权限

本机每分钟巡检全部常驻容器、主应用、任务状态、备份、磁盘及 TLS。积压仅作为诊断数据，不能触发重启。有限自愈需连续三次失败、没有发布、数据库任务到达安全点且依赖正常；每个故障事件最多一次，同类尝试冷却至少 30 分钟。上线前保持关闭，故障演练通过后才启用。

磁盘小于 14 GiB 告警并阻止发布；小于 8 GiB 为严重。下载预算另外包含压缩层和解包开销。保留当前和两个已验收近期回滚版本，以及所有容器引用的镜像；禁止全局 prune、清理业务卷或按日期整删旧发布目录。

保留策略同时保存最近三个源码版本和当前镜像之外两个已验证的不同镜像组合，避免文档发布挤掉可用应用回滚。已成功发布版本可以重试；原始 Release 清单与回执不覆盖，新增回执按操作 ID 独立命名。预取阶段的可处理断连会删除临时认证文件；硬中断遗留的目录只有在归属明确且原进程已结束时，才由恢复或清理入口移除。

本地备份继续 03:20（Asia/Shanghai）、最多 15 分钟随机延迟、两份、单份 6 GiB、8 GiB 保留空间、3600 秒处理预算及 3900 秒服务超时。定时器保持 `Persistent=false`。`status.json` 区分成功、锁占用、空间不足、超时和完整性失败，成功回执包含归档大小与 SHA-256；部署不触发补备份。

通知通过 `heyi-ops-event` Issue 按服务和故障类型去重，恢复后关闭。Issue 指派维护者，邮件使用其 GitHub 原生订阅设置；必须人工或收件证据确认送达，API 成功不等于邮件送达。GitHub 报告默认保留七天，服务器运维回执限定 30 天/50 MiB，并保留当前及回滚所需证据。

首次邮件验收可手动运行 Operations 的 `notification-test`，生成独立的 `monitoring-test / notification-validation` 事件；正常巡检不会把它误判为业务事故或提前关闭。收到邮件后记录证据并关闭该验收事件。证书续期使用既有 webroot，deploy hook 提交 `heyi-ops-certificate.service`，经同一发布锁重载 Nginx 并比较实际对外证书与磁盘证书；锁占用时等待重试。日常巡检同时检查实际生效证书的到期日和未重载情况。

`production` 与 `monitoring` 使用独立 SSH 身份，强制命令与 sudo 仅允许固定入口，禁用转发并固定主机密钥。生产环境只允许正式标签和 `main-tree` 的运维任务。巡检身份不允许发布、Shell 或 SQL。

定时工作流不是可靠的本机调度替代。主机 watchdog 仅恢复 `disabled_inactivity`，尊重人工停用；需要仅限本仓库 Actions 写权限的专用令牌，保存在 `/etc/heyi-ops/monitor-actions-token`，不能使用开发者的广泛 OAuth 凭据代替。

## 一期验收顺序

先修复并验证全量构建/测试，再接通受限账号和固定配置，随后验证候选及正式标签，最后打开分支保护、监控及有限自愈。必须演练不可信制品、跳过测试、过期标签、并发/重复请求、SSH 断开、执行器中断、活跃业务超时、不健康镜像、清理后回滚、迁移拦截及故障通知。每项实际证据记录在一期验收记录中。
