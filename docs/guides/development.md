# 开发与验证指南

核对日期：2026-11-05。首次 Compose 启动见[项目首页](../../README.md)；本文说明配置边界、日常开发和验证条件。

## 环境与构建

| 范围 | 要求 |
| --- | --- |
| 主应用容器 | Docker Desktop / Engine；Compose 2.24.4+，支持 `!override` / `!reset` |
| 宿主机 Java 调试 | JDK 21、Maven 3.9 系列；仓库没有 `mvnw` |
| Runtime / Node 测试 | Node.js 22；Pi 使用 `package-lock.json` 和 `npm ci` |
| 招聘桌面 | 依平台构建，Node 22、Python 3.12、pnpm 11.25.0；完整流程见 HR README 与工作流 |
| 视频抽帧及 watch | FFmpeg、Python，以及 Runtime 镜像内的 watch 工具目录 |

根 `Dockerfile` 在容器里全量编译，运行 Maven 测试后生成 JAR；`Dockerfile.local` 和 `Dockerfile.runtime` 则读取预构建 JAR，不能直接在新检出目录构建。具体 COPY 输入应以各 Dockerfile 为准。根 `pom.xml` 产物为 `target/ai-commerce-platform-0.1.0-SNAPSHOT.jar`；本地 Dockerfile 要求复制成根目录 `app-local.jar`。修改 Java 后必须重新打包、复制并重建 `app`，容器不会热读宿主源码。

普通前端不需要 `npm install` 或 `npm run build`。首页本机方案使用只读源码挂载，修改后刷新页面即可；发布镜像仍需检查脚本引用、普通/gzip 文件和缓存版本。本次目录整理已补齐两个前端 Dockerfile 原有的页面资源缺项，后续新增资源仍需同步清单并运行打包检查。

## 配置入口及优先级

- `.env.example` 是示例，不包含所有配置；后端默认及变量映射见 `src/main/resources/application.yml`，其他 `@Value` 配置要查所属服务。
- `.env` 用于 **Compose 变量插值**，并不会自动把每个变量传给容器。要核对 `services.app.environment` 或相应覆盖文件是否真正注入。
- 直接运行 Java / Node 时也不会自动加载根 `.env`。使用 IDE 的私有环境变量配置，或明确注入当前进程，不把生产配置复制到源码里。
- Compose 通过 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD` 连接 MySQL；`MYSQL_*` 是数据库容器初始化配置。已有数据库不会因改 `.env` 自动改密码。
- 新装管理员由 `SaasBootstrap` 初始化；已有账号不因改变 `APP_ADMIN_PASSWORD` 被重置。

| 配置组 | 关键变量 | 接手注意事项 |
| --- | --- | --- |
| 数据库 / Redis | `DB_URL`、`DB_USERNAME`、`DB_PASSWORD`、`DB_POOL_SIZE`、`REDIS_HOST/PORT/PASSWORD` | 容器间用 `mysql`/`redis`；宿主机调试用对应本地端口 |
| 初始企业 / 管理员 | `APP_TENANT_CODE/NAME`、`APP_ADMIN_PHONE/PASSWORD` | 只在本地新环境生成测试身份 |
| 加密 | `APP_MODEL_SECRET_KEY`、`APP_SHOP_CREDENTIAL_KEY` | 分别保护模型和店铺等加密资料；换密钥不等于自动迁移已有密文 |
| 会话及地址 | `APP_SESSION_COOKIE_SECURE`、`APP_PUBLIC_BASE_URL`、`APP_SHOP_AUTH_CALLBACK_BASE_URL`、`APP_SHOP_AUTH_FRONTEND_BASE_URL` | 本地 HTTP Cookie 设 false；正式 HTTPS 为 true；地址须与实际回调和链接用途一致 |
| 内部执行 | `APP_PI_RUNTIME_URL/TOKEN`、`APP_AUTOMATION_AGENT_TOKEN` | App 与 Runtime 的 `PI_RUNTIME_TOKEN` 必须一致，两个系统令牌分开生成 |
| 文件目录 | `APP_AUTOMATION_SKILL_STORAGE`、`APP_AUTOMATION_ATTACHMENT_STORAGE`、`APP_VIDEO_SCRIPT_STORAGE`、`APP_AI_VIDEO_TASK_STORAGE` | 容器默认 `/data/...`；宿主机调试改为可写的独立绝对目录 |
| 官方店铺 | `APP_SHOPEE_*`、`APP_TIKTOK_SHOP_*` | 真实密钥和控制台权限单独交接；商品运营与财务连接分别验证 |
| 发布 | `APP_PRODUCT_PUBLISHING_PROVIDER`、`APP_SHOP_LIVE_PUBLISHING_ENABLED` | 初始 `DRY_RUN`/false；需要已有授权及目标店铺字段校验 |
| 图片 | `APP_PRODUCT_WATERMARK_PROVIDER/MODEL_ID`、`APP_PRODUCT_WATERMARK_LOCAL_*` | 本地检测只是证据；默认关闭不能当作已验收无水印 |
| OSS | `APP_OSS_*`、`ALIBABA_CLOUD_ECS_METADATA` | 核对 endpoint、签名身份、私有凭据挂载及素材权限；本机不直接使用生产实例角色 |
| 广告 | `APP_TIKTOK_ADS_CREDENTIALS_FILE` 或 `APP_TIKTOK_ADS_APP_ID/APP_SECRET` | 文件优先；模板在 `config/`，挂载覆盖见 `compose.finance-ads.yaml` |
| 文档转换 | `APP_KNOWLEDGE_PREVIEW_URL/TOKEN`、`DOCUMENT_PREVIEW_TOKEN` | 通过 `compose.document-preview.yaml` 显式启用，内部令牌一致 |

`.env.example` 保持 `APP_SESSION_COOKIE_SECURE=true`，这不是本地 HTTP 的适用值。若出现登录成功后立即 401，先核对 Cookie 及访问主机，不要先重置账号或清空 Redis。

容量配置 `APP_DISPATCH_WORKER_THREADS=8`、`APP_MAX_ACTIVE_TASKS=80`、`APP_MAX_MODEL_CALLS=20` 属于通用调度；财务同步有独立并发/分页控制，不能通过调大这几个变量改变财务上游限额。

## 本地迭代

Java 修改后，在根目录执行：

```bash
mvn -B -s maven-settings.xml -DskipTests package
cp target/ai-commerce-platform-0.1.0-SNAPSHOT.jar app-local.jar
docker compose -f compose.yaml -f compose.local.yaml up -d --build app
```

未安装宿主 Maven 时，用首页的 Maven 容器构建命令替代第一行。首页的前端源码挂载无需因修改静态文件重建容器；更改 Nginx 或需要重建前端时，重新使用首页带标准输入覆盖的完整命令，保留挂载。正式候选镜像须检查 COPY 清单并单独验收。

宿主机调试可先启动 MySQL、Redis 和 Pi Runtime，再在 IDE 配置 `DB_*`、`REDIS_*`、内部令牌及可写存储目录，运行 `AiCommercePlatformApplication`。`APP_PI_RUNTIME_URL` 使用 `http://localhost:8090`，数据库地址使用本机发布端口。不要把两套 App 同时连到生产数据库来验证。

Pi 源码开发使用：

```bash
npm --prefix services/pi-runtime ci
npm --prefix services/pi-runtime start
```

运行前注入私有 `PI_RUNTIME_TOKEN`，确认 8090 未被容器占用。涉及完整 watch 链路时优先用 Runtime 镜像；其 Dockerfile 安装上游固定提交的 Python 工具和 FFmpeg，仅 `npm ci` 不会补齐这些工具。

Windows PowerShell 与首页相同步骤：

```powershell
# 只在新检出且尚无 .env 时复制，然后编辑配置
Copy-Item .env.example .env
docker run --rm -v "${PWD}:/workspace" -v heyi-maven-cache:/root/.m2 -w /workspace maven:3.9-eclipse-temurin-21 mvn -B -s maven-settings.xml -DskipTests package
Copy-Item target/ai-commerce-platform-0.1.0-SNAPSHOT.jar app-local.jar
@'
services:
  frontend:
    volumes:
      - ./frontend:/usr/share/nginx/html:ro
'@ | docker compose -f compose.yaml -f compose.local.yaml -f - up --build -d
```

## Gitflow 与个人 fork

2026-11-06 为个人 fork `Gotit-zhx/ai-video` 建立以下映射，保留上游 `HaLoBAI/ai-video` 的分支名称和历史：

| 分支 | 角色及使用方式 |
| --- | --- |
| `main-tree` | 发布基线；沿用上游默认分支，不直接提交日常开发改动 |
| `develop` | 集成分支；从已同步的 `main-tree` 建立，接收验证后的功能分支合并 |
| `feature/*` | 从 `develop` 建立，按独立变更提交，用 `--no-ff` 合回 `develop` |
| `release/*` | 从 `develop` 准备正式版本，按 [AGENTS.md](../../AGENTS.md) 合入发布基线、打标签并合回 |
| `hotfix/*` | 从已发布基线修复线上问题，按 Gitflow 合回所有需要接收修复的分支 |

本地 `origin` 保留上游地址，`fork` 指向个人仓库；默认推送目标为 `fork`。本地配置明确 `gitflow.branch.master=main-tree`、`gitflow.branch.develop=develop` 和对应支持分支前缀。新检出时这些本地 Git 配置不会随源码复制，需要按本表重新配置并核对远端归属。

目录整理、交接文档与刷单合计先经 `feature/project-handoff-and-brush-summary` 合入个人 fork 的 `develop`。随后通过 `release/2026.11.6-project-sync` 完成正式上线，发布标签为 `v2026.11.6`；当前镜像、源提交、验证边界与服务器源码入口见[发布验收](../releases/infrastructure/project-sync-20261106.md)。后续功能仍先进入 `develop`，不得绕过发布流程直接推向 `main-tree`。

## 测试入口

这些是现有测试入口，不是本次文档更新重新跑过的业务验收结果。按变更选择测试，记录通过、失败、跳过及所缺条件。

| 范围 | 从根目录运行 | 条件 |
| --- | --- | --- |
| Java 单元/条件集成测试 | `mvn -B -s maven-settings.xml test` | JDK 21；数据库集成测试可能按环境跳过 |
| 选定 Java 类 | `mvn -B -s maven-settings.xml -Dtest=FinanceSourceOrderIntegrationTest test` | 下述隔离 MySQL 条件 |
| 前端 Node 测试 | `node --test frontend/test/*.test.cjs frontend/test/*.test.mjs frontend/tests/*.test.mjs` | 个别测试另有文件/依赖条件；浏览器测试不是此命令的等价覆盖 |
| Pi 模块 | `node --test services/pi-runtime/test/*.test.mjs` | 先 `npm --prefix services/pi-runtime ci`；部分 watch 测试需要镜像工具 |
| 实际 Pi SDK 本地模拟 | `node --test services/pi-runtime/test/product-main-image-agent.integration.mjs` | 已安装锁定依赖；调用 loopback 模拟模型，不是付费模型验收 |
| 扩展语法及回归 | `npm --prefix apps/browser-extension run check` | 检查脚本会继续执行其列出的测试；不是单纯语法检查 |
| 财务页面交互 | `node scripts/qa/test-finance-source-orders-ui.mjs` 等 | 需要 JSDOM，脚本支持 `JSDOM_MODULE` |
| 通用桌面助手 | `npm --prefix apps/automation-agent test` | Electron 构建及实际窗口另验 |
| 招聘桌面 | `npm --prefix apps/hr-desktop test` | 运行时和目标系统打包另外按其 README / CI 验证 |
| 文档转换 | `python3 -m unittest discover -s document-preview -p 'test_*.py' -v` | 实际 PPT 转 PDF 仍要在候选镜像里测试 |

财务 JSDOM 脚本不依赖生产登录。仓库根目录没有 Node 依赖清单，可在仓库外安装经团队确认的 `jsdom` 版本，并令 `JSDOM_MODULE` 指向其绝对模块入口，例如 `/你的测试目录/node_modules/jsdom/lib/api.js`。不要为了临时测试生成新的根 `package.json`。常用脚本包括 `test-finance-source-orders-ui.mjs`、`test-finance-order-dates-search-ui.mjs`、`test-finance-fee-exports-ui.mjs`、`test-finance-qianyi-refunds-ui.mjs`、`test-finance-sync-priority-ui.mjs`。

### MySQL 集成测试

`FinanceConfigurationIntegrationTest` 及继承它的测试会建表、迁移，并在用例间删除测试表数据。必须先准备**空的可销毁数据库及只拥有该库权限的账号**：

- `FINANCE_QA_JDBC` 必须匹配 `jdbc:mysql://<测试主机>:<端口>/finance_qa_<十六进制后缀>?<参数>`。
- `FINANCE_QA_USER` / `FINANCE_QA_PASSWORD` 只对应这个测试库。
- 缺少环境变量时会跳过；跳过不能作为数据库回归通过。
- 知识库使用 `HEYI_KNOWLEDGE_TEST_URL`，Mapper 使用 `HEYI_MAPPER_TEST_URL`，两者有不同的本机库名约束及账号假设；运行前读具体测试夹具，不复用财务变量。

测试结束删除本次创建的测试库、账号、私有环境文件和临时构建物。不能向 `ai_commerce`、共享开发库或生产连接注入这些测试变量。部分历史发布 QA 会准备远程隔离夹具，但 `scripts/release-*.py` 同时包含发布动作，不能直接当作普通测试命令执行。

## 数据库迁移

最高现存编号 V147；新迁移先确认没有并行冲突，使用下一个未占用版本。迁移编号有历史缺口，保留现状。已发布 SQL 不编辑、不重命名、不用 `repair` 掩盖校验差异。

先在隔离 MySQL 验证迁移、旧数据保留、索引与幂等行为；涉及财务时核对租户/分组限制、零值与缺失值、首次结算及样品/取消订单。生产回滚应用镜像不会自动回滚 MySQL，需要明确旧应用与新结构的兼容性。

## 已知开发缺口

- 本地 Nginx 没有生产招聘/ERP 的专用代理；默认 Compose 不启动 Connector。招聘及安装包交接需要独立环境。
- 前端镜像仍采用手工 COPY 清单；原先缺失的 13/15 个入口资源已补齐并通过资源测试，本地源码挂载用于开发热更新。后续新增资源仍须检查，完整浏览器及真实外部链路另行验收。
- 缺少统一的后端/前端服务 CI。当前 GitHub 工作流主要是 HR 安装包；普通分支推送不等于通过服务回归。
- 历史发布脚本依赖 `/opt/heyi/releases/...` 的已保留文件，清理后的目录未必能重新构建；源码可编译不等于完整镜像与线上逐字节相同。
- 真实模型、官方授权/发布、Windows 安装和所有店铺独立对账，需要各自的授权环境与验收证据。
