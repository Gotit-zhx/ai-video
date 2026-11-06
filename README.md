# 赫逸智能化 AI 平台

面向电商运营、AI 图片与视频生产、财务分析和招聘管理的多租户平台。

文档核对日期：**2026-11-06（北京时间）**。上游默认分支为 `main-tree`；个人 fork 按 Gitflow 使用 `main-tree` 发布基线、`develop` 集成及 `feature/*` 开发分支，见[协作流程](docs/guides/development.md#gitflow-与个人-fork)。服务器于当日 11:33 完成 `2026.11.6` 发布，当前前后端为 `brush-source-summary-20261106`；源码、测试及运行边界见[发布验收](docs/releases/infrastructure/project-sync-20261106.md)，不等同于全部业务重新验收。

## 接手入口

| 要做的事 | 文档 |
| --- | --- |
| 确认交接范围、账号归属和待确认事项 | [开发交接清单](docs/guides/handoff.md) |
| 第一次启动、配置和运行测试 | [开发指南](docs/guides/development.md) |
| 理解服务边界、代码位置和数据流 | [架构说明](docs/guides/architecture.md) |
| 查找登录、业务接口和权限入口 | [接口导航](docs/guides/api.md) |
| 发布、回滚、备份和排障 | [运维指南](docs/guides/operations.md) |
| 接管服务器、确认现场版本与故障待办 | [服务器运维交接](docs/guides/server-handoff.md) |
| 查业务规则及历次验收 | [文档索引](docs/README.md) |

生产操作先读 [AGENTS.md](AGENTS.md)、[运维指南](docs/guides/operations.md)和[2026-11-06 发布验收](docs/releases/infrastructure/project-sync-20261106.md)。发布记录中的镜像、时间和测试结果只代表该次发布；旧记录不能直接作为当前部署命令。

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
| Web 前端 | 原生 HTML / JavaScript / CSS、Nginx；无独立 npm 构建步骤 |
| 模型运行时 | Node.js 22、Pi Agent SDK，位于 `services/pi-runtime/` |
| 本机执行 | Electron 自动化助手、招聘助手及浏览器扩展，各自独立构建 |
| 文件 | Docker 持久卷及按环境配置的 OSS；数据库备份不包含全部素材 |

`services/erp/` 是独立 ERP，`services/household/` 是独立家务服务；它们的账户、数据库和部署不属于主应用 Compose 的默认启动范围。服装重建目录包含实验原型，能力边界见各自 README。

## 本机首次启动

以下命令适用于 macOS / Linux，从仓库根目录运行。需要 Docker Desktop，Compose **2.24.4+**。Windows 对应步骤及宿主机调试见[开发指南](docs/guides/development.md)。

只在没有 `.env` 的全新检出目录复制模板：

```bash
cp .env.example .env
chmod 600 .env
```

启动前编辑 `.env`：

- 替换数据库、Redis、管理员密码以及四个独立密钥/令牌：`APP_MODEL_SECRET_KEY`、`APP_SHOP_CREDENTIAL_KEY`、`APP_AUTOMATION_AGENT_TOKEN`、`APP_PI_RUNTIME_TOKEN`。可用 `openssl rand -hex 32` 分别生成。
- 本地 HTTP 设置 `APP_SESSION_COOKIE_SECURE=false`，将 `APP_SHOP_AUTH_FRONTEND_BASE_URL` 设为 `http://localhost:3000`，`APP_SHOP_AUTH_CALLBACK_BASE_URL` 设为 `http://localhost:8080`。生产 HTTPS 保持 Secure Cookie。
- 首次运行保持官方平台开关关闭，发布与水印提供方保持 `DRY_RUN`；不需要真实店铺、广告或模型凭据即可启动基础服务。

本地后端 Dockerfile 会读取根目录 `app-local.jar`，需要先构建。下面使用 Maven 容器，不要求宿主机安装 Java/Maven；`-DskipTests` 仅用于先生成启动包，回归测试另见开发指南：

```bash
docker run --rm -v "$PWD:/workspace" -v heyi-maven-cache:/root/.m2 \
  -w /workspace maven:3.9-eclipse-temurin-21 \
  mvn -B -s maven-settings.xml -DskipTests package
cp target/ai-commerce-platform-0.1.0-SNAPSHOT.jar app-local.jar
docker compose -f compose.yaml -f compose.local.yaml -f - up --build -d <<'YAML'
services:
  frontend:
    volumes:
      - ./frontend:/usr/share/nginx/html:ro
YAML
```

最后一份标准输入覆盖仅用于本机开发，将前端源码只读挂载到 Nginx，用于静态资源开发与实时查看修改；编辑 JS/CSS 后刷新页面即可。两个前端 Dockerfile 的既有资源缺项已在本次整理中补齐，镜像验收见[交接清单](docs/guides/handoff.md)。

必须带 `compose.local.yaml`：它选择本地 Nginx 配置、移除生产证书挂载，并把发布端口绑定到 `127.0.0.1`。单独使用 `compose.yaml` 会采用生产域名证书配置，不适合新机器直接启动。

访问 `http://localhost:3000/`；健康检查：

```bash
curl -fsS http://localhost:8080/actuator/health
docker compose -f compose.yaml -f compose.local.yaml ps
```

首次初始化的企业编码、管理员手机号分别取 `.env` 中的 `APP_TENANT_CODE`、`APP_ADMIN_PHONE`，密码取你设置的 `APP_ADMIN_PASSWORD`。Compose 要求显式配置密码及关键令牌，**不能依赖旧文档的默认密码**。修改环境变量不会重置已存在账号的密码。

本地停止使用同一组 Compose 文件，不加 `-v`：

```bash
docker compose -f compose.yaml -f compose.local.yaml down
```

这组启动步骤覆盖主应用五个服务。招聘 Python 服务、可选文档转换/水印服务和已授权的外部模型需要另外配置；主站启动成功不等于这些链路已完成业务验收。

## 开发时必须保留的约束

- Web API 使用 `HEYI_SESSION` Cookie；先调用 `POST /api/v1/auth/login`。旧版 `Authorization: Basic` 工作流示例不适用。插件 Bearer、内部令牌和签名素材链接有各自边界，见[接口导航](docs/guides/api.md)。
- 所有数据库结构变更通过 `src/main/resources/db/migration/` 的 Flyway 迁移完成；不重写已应用迁移。当前最高编号为 V147，历史编号有缺口，不补造旧文件。
- 企业隔离、财务公司共享范围、店铺/分组权限、加密凭据归属和人工审核不能只靠前端保证。
- 保持同步游标、租约、限流和结果未知时的处理规则；不要用删除队列或重发请求代替恢复。
- 生产不增加持久应用/诊断日志；镜像压缩、回滚保留、临时文件清理及备份边界以 [AGENTS.md](AGENTS.md) 为准。

修改业务时同时更新对应常驻说明及本次验收记录，避免只留下按日期命名的发布文件。
