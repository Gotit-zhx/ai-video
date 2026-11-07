# 阿里云生产部署与运维交接

核对日期：**2026-11-07（北京时间）**，服务器 `8.163.34.81`。Java/Web 已接入统一运维；当前应用基线 **v2026.11.7.7** 修复了接管引入的定时调度缺陷，原任务已恢复推进。本批交接文档随 v2026.11.7.8 发布；最新源码版本以 `heyi-ops status`、`/opt/heyi/source/current` 和正式 Release 回执为准。

日常命令见[运维指南](operations.md)，流水线和权限见[CI/CD 指南](cicd.md)，版本、故障修复和验收边界见[一期收尾](../releases/infrastructure/devops-phase1-closure-20261107.md)。接管前的详细端口、目录与问题已归档到[历史快照](../releases/infrastructure/server-handoff-before-cicd-20261107.md)，不再作为当前发布入口。

## 1. 接手时先处理什么

日常工作收敛为：查看 CI → 按 Gitflow 完成发布并创建正式标签 → 查看 Production deployment 和 Release 回执；故障从 Operations 的 `doctor` 或受限 SSH 入口诊断，选择保留版本回滚。

| 已完成 | 当前状态 |
| --- | --- |
| 可重复构建 | 固定工具链、按类隔离数据库、明确允许的跳过项、前端资源清单/gzip、完整镜像冒烟 |
| 生产入口 | GitHub 托管 Runner、两个 Private GHCR 包、来源证明、受限部署/巡检身份、固定 SSH 主机密钥 |
| 配置与数据边界 | app/frontend 各由 252/240 份历史覆盖收敛为三份配置；环境、端口、挂载、网络、资源和日志比对通过，增加只读运维控制目录 |
| 恢复与留痕 | systemd 独立执行、双锁、维护门控、拥有者租约释放、持久回执与恢复入口 |
| 自动巡检 | 九个常驻容器、主应用、数据库任务、磁盘、备份和 TLS；本机每分钟，GitHub 配置每五分钟且手动外部检查通过；有限自愈已启用 |
| 通知 | 去重 Issue 与 GitHub 原生邮件；Issue #4 两次测试未重复创建，维护者确认邮件送达后已关闭 |
| 备份/TLS | 最新已有归档完整校验通过；webroot 续期和实际证书重载通过 |

广告代理由并行开发会话维护。新节点已完成独立连接验证，但正式代理、共享 properties 和广告应用发布仍需独立维护/验收；本次 CI/CD 接管不代表广告账户已授权或广告数据已同步。其他仍需接手的事项见第 10 节。

## 2. 主机与运行平台

| 项目 | 现场值 |
| --- | --- |
| 主机 | Ubuntu 26.04 LTS，内核 `7.0.0-28-generic`，8 vCPU |
| 时区 | 宿主机 Asia/Shanghai；数据库业务诊断使用 UTC |
| 容器平台 | Docker 29.1.3，containerd image store；Compose `2.40.3+ds1-0ubuntu1` |
| 磁盘样本 | v7 收尾复核约 15.2 GiB 可用；数值持续变化，14 GiB 是发布/备份启动保护线，8 GiB 为严重阈值 |
| 运维程序 | `/usr/local/sbin/heyi-ops`；版本位于 `/usr/local/lib/heyi-ops/versions/`，`current` 指向已验收代码 |
| 日志 | 核心容器仍为 `none`；新增运维回执按 30 天/50 MiB 管理，保留当前和回滚必需证据 |

Docker 在本机可能把 manifest digest 作为 `inspect.Id` 返回。发布清单另外保存 config digest，运维入口分别核对，不能把两种摘要混用。

## 3. 服务与访问链路

| 容器 | 用途与访问 |
| --- | --- |
| `ai-commerce-frontend` | Nginx/Web，宿主机 80/443，承接主站、API、ERP 和下载路由 |
| `ai-commerce-app` | Java，宿主机 8080，健康检查 `/actuator/health`；业务权限保留 |
| `ai-commerce-mysql` | MySQL 8.4，宿主机 3306，业务数据库与卷保留 |
| `ai-commerce-redis` | Redis，仅宿主机回环 6379，原会话/任务持久化保留 |
| `heyi-pi-runtime` | Pi Runtime，宿主机 8090 |
| `heyi-commerce-image-processor` | 图像处理，仅容器网络 8091 |
| `heyi-document-preview` | 文档预览，内部网络 8092 |
| `hoyee-erphoyee` | ERP，内部 8137，经主站 `/erphoyee/` 访问 |
| `heyi-cloudbeaver` | 数据库管理，仅宿主机回环 8978 |

接管只重建 app/frontend；其余七个容器 ID 均未变。`running` 仅表示进程运行；没有 Docker 健康检查的组件不能据此推断全部业务功能通过。

宿主机另有正常运行的 Docker/containerd、1Panel core/agent 和 `heyi-desktop-downloads.service`。定时执行的 `heyi-ops-*.service` 正常结束后为 inactive/dead，应结合 `Result=success` 和 timer 状态判断。Aegis 的既有失败状态另列待办，未由本任务修复。

## 4. 部署入口、Compose 与版本

| 项目 | 当前入口 |
| --- | --- |
| 自动上线 | `main-tree` 当前正式 `v*` 标签；候选必须来自受信任 release/hotfix 分支并完成验证 |
| 统一命令 | `status`、`doctor`、`preflight`、`deploy`、`rollback`、`recover`、`cleanup` |
| 配置 | `/etc/heyi-ops/config.json`；仅 root 管理 |
| 固定 Compose | `/opt/heyi/ops/compose/base.json` + `production.json` + 生效版本 `images.json` |
| 当前容器引用的镜像清单 | 后端 `/opt/heyi/ops/releases/v2026.11.7.7/images.json`；未重建的前端仍引用 v4 的 `images.json` |
| 已安装控制面 | `/usr/local/lib/heyi-ops/versions/20bb15739b2f8601d1f87c4e342ed15747936466` |
| 当前源码/文档 | `/opt/heyi/source/current` → `/opt/heyi/source/releases/<已验收版本>` |
| 正式清单与验收 | GitHub Release 的 `release.json`、`receipt.json` 及追加的操作回执；服务器 `/opt/heyi/ops/releases/`、`operations/` |
| 初始接管证据 | `/opt/heyi/operations/devops-phase1-20261107-946c8f18/` 的小型回执，以及持久化的 `/opt/heyi/ops/adoption/` |

纯文档发布更新源码指针并复用镜像；后端、前端的 Compose 标签分别保留其最近实际重建版本，这是预期行为。下一次组件切换会生成新版本镜像清单。历史 `/opt/heyi/current` 仍是旧配置入口，不是当前应用版本指针。

运行中后端 manifest 为 `sha256:5db37670c5f7f70a9df8030b4b9f71cffffcc920ed6198581d0b3c3ead71034a`，由候选 `20bb157` 构建；前端仍为 `sha256:ddea0f67a282d99e3a8113fb62f4509c1a29486a41360791024ec5f9166c5e59`，来源 `867724c`。v7 只切换后端，用时 43 秒。完整 config digest 见[当前版本表](operations.md#最近现场核实的版本)。

## 5. 持久化、目录与私有配置

原 MySQL、Redis、视频、自动化附件、Pi 图像和 CloudBeaver 卷继续保留；ERP 仍使用独立数据目录。核心路径：

| 路径 | 边界 |
| --- | --- |
| `/opt/heyi/ops/` | 生产控制面状态、基线、固定配置、已验证制品与回执；不是临时构建目录 |
| `/opt/heyi/shared/oss-signing.properties`、`/opt/heyi/secrets/dayan-oss.properties` | 私有签名/OSS 配置，保留既有只读挂载及权限 |
| `/opt/heyi/shared/finance-ads/` | 广告应用私有 properties；修改需与并行会话协调并记录维护，不能把内容写入 Git |
| `/opt/heyi/shared/hr-desktop-downloads/`、`/opt/heyi/desktop-installers/` | 下载发行物及服务程序，按实际引用保留 |
| `/opt/heyi/erphoyee/shared/data` | ERP 独立持久数据 |
| `/opt/heyi/backups/mysql/` | 本地日常归档与 `status.json` |
| `/etc/letsencrypt/` | 证书、续期配置与私有账户材料 |
| `/opt/heyi/releases/`、`/opt/heyi/deployments/` | 其他服务仍可能引用的配置、私有文件及历史回执，禁止按日期整删 |

`heyi-deploy` 与 `heyi-monitor` 使用不同 SSH 密钥和强制命令，禁止任意 Shell、SQL 和转发；巡检身份不能发布或清理。专用 watchdog 令牌位于 `/etc/heyi-ops/monitor-actions-token`，仅限本仓库 Actions 权限，不能替换为开发者的广泛 OAuth 凭据。

## 6. 数据库、任务与日志

接管前后 Flyway 历史指纹相同，仍为 V147，未新增或改写迁移。普通发布发现迁移新增、历史校验变化或无法确认的业务安全点时停止；结构变更走独立维护流程。

首次接管只临时预留本次 UUID 的空闲租约，未直接修改计划、检查点或上游限流；验收后五类预留均为 0。随后发现 Spring 周期调度没有恢复，v7 已把门控移到业务回调并验证原上传流程自然推进。原图预处理 19/19 完成、九图流程曾到 READY 9/9 后进入 PUBLISH；其他任务仍在运行，不能宣称所有平台上传已完成。成本积压不要求清零，也不因积压触发重启。既往领取或存在任何锁的上传任务仍阻止应用切换，不能修改业务记录绕过安全点。

生产保留原 `none` 日志策略。排障使用有界、脱敏输出和小型回执；不要记录完整请求/响应、凭据、业务导出或在长期运行容器上临时打开无限日志。

## 7. 网络、证书与管理访问

主站为 `hoyee.org.cn` / `www.hoyee.org.cn`。已将 Certbot 从 standalone 改为现有 webroot `/opt/heyi/shared/certbot-webroot`，完成测试、实际续期、Nginx 配置检查、重载和对外证书匹配。证书到期时间为 **2027-02-05 11:04:47（北京时间）**。deploy hook 通过 `heyi-ops-certificate.service` 与发布共用锁；巡检在 14 天/7 天阈值告警。

3306/8080/8090 的宿主机全地址绑定保留；云安全组仍需在阿里云控制台单独核实，不能用一次 TCP 探测推断全部访问边界。数据库、CloudBeaver、1Panel 应沿用公司授权和既有隧道/防护策略，私有安全路径及密码通过凭据渠道移交。

旧 Linux 招聘服务仍属退役范围，历史 HR 路由缺口不能通过擅自解除屏蔽旧浏览器来处理。

## 8. 自动任务与并行维护

| 调度 | 生效策略 |
| --- | --- |
| `heyi-ops-monitor.timer` | 每分钟；连续三次可用性失败后，确认无发布、无活跃任务且依赖正常后才允许一次重启；至少 30 分钟冷却 |
| `heyi-ops-watchdog.timer` | 每小时；只恢复 GitHub 的 `disabled_inactivity`，尊重人工停用 |
| `heyi-ops-cleanup.timer` | 每六小时；只清理有归属且无运行引用的产物，保留当前、近期源码和两个不同镜像组合的回滚证据 |
| GitHub Operations | 每五分钟计划触发及手动 `doctor`/`rollback`；GitHub 调度允许延迟，本机任务继续独立运行 |
| MySQL / TLS | 继续使用本机 backup / certbot timer，不依赖 GitHub 定时工作流 |

先取 `/run/lock/heyi-application-release.lock`，再取 `/opt/heyi/operations/.project-release.lock`。多会话发布、迁移、代理接入、数据维护、证书重载和清理按同一顺序串行；旧脚本不会自动遵守新入口，不能在另一会话发布时重跑历史 helper。

## 9. 备份、恢复与容量边界

| 项目 | 已核实策略 |
| --- | --- |
| 时间 | 每天 03:20 Asia/Shanghai，随机延迟不超过 15 分钟，`Persistent=false` |
| 上限 | 两份完成归档、单份 6 GiB；启动至少 14 GiB，运行中至少保留 8 GiB；处理 3600 秒、服务 3900 秒 |
| 回执 | `status.json` 区分成功、锁占用、空间不足、超时、完整性失败，并保存上次成功与 SHA-256 |
| 现存两份 | `ai-commerce-20261105T193420Z.sql.gz`（4,608,755,826 字节）和 `ai-commerce-20261106T192604Z.sql.gz`（4,715,703,883 字节） |
| 完整校验 | 最新已有归档通过完整 gzip 与 SHA-256 校验；新鲜度沿用归档真实时间，没有以校验时间伪装新备份 |

最新归档 SHA-256 为 `3929318c93d014d7e60d302ca30b4558845591b70864048267ee1d77a129ad19`。本次未发起数据库导出、补备份或生产恢复。脚本备份范围为主 MySQL 库，未证明覆盖 OSS、Redis、ERP 文件、全部业务卷和解密密钥。异地副本及隔离恢复留给独立流程；镜像回滚不等于数据恢复。

## 10. 日常巡检与故障处理

```bash
heyi-ops status
heyi-ops doctor
systemctl list-timers --all 'heyi-ops-*' heyi-mysql-backup.timer certbot.timer
```

| 现象 | 处理 |
| --- | --- |
| 发布失败/维护未结束 | 查操作回执、`doctor` 和 systemd 执行器；使用 `recover` 恢复本次拥有的状态，不能手删别人标记 |
| 新版不健康 | 通过 Operations 选择保留且已验收的版本；先确认 schema 兼容，避免直接整套 Compose 重建 |
| 配置或 operations 指纹不符 | 核对并行维护及实际基线，完成明确的主机维护和回执；不能只改指纹绕过检查 |
| 磁盘/备份告警 | 核对保护线、新鲜度、归档与状态回执，只清理明确拥有且无引用的产物，不改为漏跑补备份 |
| 同步等待/限流 | 查进度、租约和 pacing，保留检查点和冷却，不能以清队列或放宽限流代替诊断 |
| TLS 告警 | 核对 webroot、续期结果及实际对外证书，检查串行重载回执 |

新运维体系的自动回滚集合只包含已验收的受管版本。旧体系的近期应用/前端镜像和回执继续保留，但不能将未携带新维护协议的历史镜像直接当作自动回滚目标。

v3/v4/v5 因确认的定时器缺陷已撤销本机发布资格，原验证文件保存在 `/opt/heyi/ops/quarantine/20261107-upload-scheduler/`；真实受限 SSH 回滚请求已确认拒绝，九个容器、源码、门控和数据库未变。所有原始证据和前端仍引用的 v4 Compose 文件保留。当前健康的受管应用只有 v7 这一组，纯文档 v8 复用相同镜像。不能把历史保留列表或健康检查成功当作业务兼容证明。

仍需独立跟进：云安全组及阿里云管理权核验、Aegis 失败、招聘退役路由承接、完整隔离恢复和异地备份、广告代理及真实账户接入。这些事项不因本次健康检查通过而自动完成。
