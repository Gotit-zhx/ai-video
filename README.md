# 赫逸智能化 AI 平台

面向电商运营、AI 图片与视频生产、财务分析和招聘管理的多租户平台。

文档核对日期：**2026-11-11（北京时间）**。主应用当前采用源码开发运行模式：本地和共享服务器直接运行 Java 与前端源码，代码生效不再以打包、Candidate、版本标签或正式发布流水线为条件。共享服务器主应用与前端已完成实际切换，运行入口、实测耗时与剩余验收项见[源码运行现场状态](docs/guides/source-runtime-status.md)。设计边界见[源码开发运行设计](docs/design/source-development-20261111.md)。

## 接手入口

| 要做的事 | 文档 |
| --- | --- |
| 确认交接范围、账号归属和待确认事项 | [开发交接清单](docs/guides/handoff.md) |
| 第一次启动、配置和运行测试 | [开发指南](docs/guides/development.md) |
| 理解服务边界、代码位置和数据流 | [架构说明](docs/guides/architecture.md) |
| 查找登录、业务接口和权限入口 | [接口导航](docs/guides/api.md) |
| 共享服务器源码运行、恢复、备份和排障 | [运维指南](docs/guides/operations.md) |
| 接管服务器、确认现场版本与故障待办 | [服务器运维交接](docs/guides/server-handoff.md) |
| 查业务规则及历次验收 | [文档索引](docs/README.md) |

共享服务器操作先读 [AGENTS.md](AGENTS.md)、[运维指南](docs/guides/operations.md)和最新源码切换回执。历史发布记录中的镜像、时间和测试结果只代表当次发布；旧记录不能直接作为当前启动或恢复命令。

项目目录及模块组织图见[项目组织结构](docs/guides/project-structure.md)，完整依赖版本及来源见[技术栈清单](docs/guides/technology-stack.md)。独立组件入口：[客户端](apps/README.md)、[服务](services/README.md)、[工程脚本](scripts/README.md)、[实验](experiments/README.md)。

## 当前功能与技术栈

- 电商：Shopee / TikTok Shop 官方授权，浏览器插件采集，商品与 SKU 整理、尺码图、主图、图片处理、模板裂变与发布流程。
- 视频：脚本分析、分镜、AI 视频任务、通用视频生成、进度监控与素材归档；通过 Pi Runtime 和已配置的模型执行。
- 财务：独立财务店铺授权、公司共享分组、订单/退款/结算、广告、成本导入与 SKU 关联、物流贴单、刷单费、千易收货核对、报表与导出。全部订单查询与结算核算分别使用各自日期口径。
- 招聘：招聘需求、人才与简历、AI 辅助分析、主动/被动邀约、面试、入职及 BI；包含 Python 服务和本机助手。
- 基础能力：企业、部门、员工、角色权限、模型接入、自动化执行和知识库附件预览。

| 组件 | 当前仓库配置 |
| --- | --- |
| Java 后端 | Java 21、Spring Boot 4.1.0、MyBatis-Plus 3.5.17、Maven |
| 数据层 | MySQL 8.4、Flyway、Redis 8、Spring Session |
| Web 前端 | 原生 HTML / JavaScript / CSS；本地 Spring 直接读取源码，服务器 Nginx bind `frontend/` 并保留 TLS/代理，无独立 npm 构建步骤 |
| 模型运行时 | Node.js 22、Pi Agent SDK，位于 `services/pi-runtime/` |
| 本机执行 | Electron 自动化助手、招聘助手及浏览器扩展，各自独立构建 |
| 文件 | Docker 持久卷及按环境配置的 OSS；数据库备份不包含全部素材 |

`services/erp/` 是独立 ERP，`services/household/` 是独立家务服务；它们的账户、数据库和部署不属于主应用 Compose 的默认启动范围。服装重建目录包含实验原型，能力边界见各自 README。

## 本机首次启动

以下命令适用于 macOS / Linux，从仓库根目录运行。主应用需要 JDK 21、Maven 3.9.11 和 Python 3；Docker/Compose 只用于本地 MySQL 与 Redis。Windows 使用 WSL2 Linux 环境，配置步骤与手动启动参数见[开发指南](docs/guides/development.md)。

首次初始化：

```bash
python3 scripts/dev.py init
```

该命令只在缺失时生成私有 `.env.development`，不会覆盖已有文件或其他环境文件。编辑其中的本地密码和密钥；不要复制生产凭据。默认依赖环境为项目隔离的 MySQL 8.4 与 Redis 8：宿主端口 `3307`、`6380`，数据库 `ai_commerce_dev`，数据保存在项目命名卷中。

启动源码开发环境：

```bash
python3 scripts/dev.py run
```

无参数执行 `python3 scripts/dev.py` 也等同于 `run`。如果 MySQL/Redis 已由本任务的 `compose.dev.yaml` 启动或已有兼容基础设施，可使用 `python3 scripts/dev.py run --no-deps`。

访问 `http://localhost:8080/`；API 与 UI 同源。健康检查：

```bash
curl -fsS http://localhost:8080/actuator/health
```

Java 以 Maven dev profile 的 `spring-boot:run` 启动。watcher 在保存后仅增量编译到 `target/classes`，成功后再触发 DevTools 重启；Java 修改需要编译和上下文重建的数秒时间，不能承诺零延迟。HTML/JS/CSS 直接从 `frontend/` 读取，无 npm 打包、gzip 或镜像构建；开发专用浏览器轮询刷新可选。`pom.xml` 依赖变化由 watcher 受控重启 app；Flyway 迁移、环境配置与运行工具本身变化须显式维护并单独验证。

需要单独开发 Pi Runtime 时运行：

```bash
npm --prefix services/pi-runtime run dev
```

它使用 Node watch 监听自身源码，不强制重建完整运行时或 FFmpeg。招聘 Python 服务、可选文档转换/水印服务、ERP、桌面程序和已授权的外部模型仍按各自 README 启动；主站健康不等于这些链路已完成业务验收。独立桌面安装包只在明确分发任务中构建，不是内部主应用运行的前置步骤。

## 开发时必须保留的约束

- Web API 使用 `HEYI_SESSION` Cookie；先调用 `POST /api/v1/auth/login`。旧版 `Authorization: Basic` 工作流示例不适用。插件 Bearer、内部令牌和签名素材链接有各自边界，见[接口导航](docs/guides/api.md)。
- 所有数据库结构变更通过 `src/main/resources/db/migration/` 的 Flyway 迁移完成；不重写已应用迁移。当前最高现存文件为 V150，V149 缺号；共享环境本次已核对 V150。自动 Flyway 已禁用，普通保存不执行 DDL；不补造旧文件。
- 企业隔离、财务公司共享范围、店铺/分组权限、加密凭据归属和人工审核不能只靠前端保证。
- 保持同步游标、租约、限流和结果未知时的处理规则；不要用删除队列或重发请求代替恢复。
- 共享 watcher 使用有界 `heyi-source` journal（64 MiB/3 天）；其他日志策略及源码热重启、历史恢复、临时文件清理和备份边界以 [AGENTS.md](AGENTS.md) 为准。

修改业务时同时更新对应常驻说明及本次验收记录，避免只留下按日期命名的发布文件。
