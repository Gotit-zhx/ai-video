# 开发与验证指南

核对日期：2026-11-11。首次源码启动见[项目首页](../../README.md)；本文说明配置边界、日常开发和验证条件。

## 环境与构建

| 范围 | 要求 |
| --- | --- |
| 主应用源码运行 | JDK 21、Maven 3.9.11、Python 3；仓库没有 `mvnw` |
| 本地依赖容器 | Docker Desktop / Engine；Compose 2.24.4+，仅启动 MySQL/Redis |
| Runtime / Node 测试 | Node.js 22；Pi 使用 `package-lock.json` 和 `npm ci` |
| 招聘桌面 | 依平台构建，Node 22、Python 3.12、pnpm 11.25.0；完整流程见 HR README 与工作流 |
| 视频抽帧及 watch | FFmpeg、Python，以及 Runtime 镜像内的 watch 工具目录 |

日常主应用不使用 Dockerfile、`app-local.jar` 或 Maven `package`。`python3 scripts/dev.py run` 以 dev profile 执行 `spring-boot:run`；watcher 只把必要 Java/资源编译到 `target/classes`，编译成功后更新 DevTools trigger。Java 保存到可用新上下文仍需要编译和重启时间。Dockerfile、JAR 和镜像入口保留为历史恢复或明确的独立分发资料，不是当前开发命令。

普通前端不需要 `npm install` 或 `npm run build`。本地 Spring 直接从 `frontend/` 读取 HTML/JS/CSS；服务器由 Nginx bind 同一源码目录。保存后刷新即可，开发专用浏览器轮询可自动刷新。新增资源仍须检查 `index.html` 引用和相关页面测试，但日常运行不生成 gzip、静态发布目录或前端镜像。

## 配置入口及优先级

- `.env.development` 是源码开发的私有环境文件；`python3 scripts/dev.py init` 只在它缺失时生成，不覆盖已有内容，也不修改 `.env` 或其他环境文件。
- `.env.example` 是示例，不包含所有配置；后端默认及变量映射见 `src/main/resources/application.yml`，其他 `@Value` 配置要查所属服务。
- 不把生产配置复制到源码或提交私有环境文件。IDE 单独启动时须明确加载 `.env.development` 或等价私有配置。
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
| TikTok 广告 | `APP_TIKTOK_ADS_CREDENTIALS_FILE` 或 `APP_TIKTOK_ADS_APP_ID/APP_SECRET` | 文件优先；模板在 `config/`，挂载覆盖见 `compose.finance-ads.yaml` |
| Shopee 广告 | `APP_SHOPEE_ADS_CREDENTIALS_FILE` | 独立 Ads Service 正式配置；模板 `config/shopee-ads.properties.example`，默认关闭，审核和新店铺授权前不借用 ERP 凭据 |
| 文档转换 | `APP_KNOWLEDGE_PREVIEW_URL/TOKEN`、`DOCUMENT_PREVIEW_TOKEN` | 通过 `compose.document-preview.yaml` 显式启用，内部令牌一致 |

`.env.example` 保持 `APP_SESSION_COOKIE_SECURE=true`，这不是本地 HTTP 的适用值。若出现登录成功后立即 401，先核对 Cookie 及访问主机，不要先重置账号或清空 Redis。

容量配置 `APP_DISPATCH_WORKER_THREADS=8`、`APP_MAX_ACTIVE_TASKS=80`、`APP_MAX_MODEL_CALLS=20` 属于通用调度；财务同步有独立并发/分页控制，不能通过调大这几个变量改变财务上游限额。

## 本地源码迭代

初始化并运行：

```bash
python3 scripts/dev.py init
python3 scripts/dev.py run
```

无参数执行 `python3 scripts/dev.py` 等同于 `run`。已有兼容的 MySQL/Redis 时可用 `python3 scripts/dev.py run --no-deps`。默认 `compose.dev.yaml` 只启动项目隔离的 MySQL/Redis，宿主端口为 `3307`/`6380`，数据库为 `ai_commerce_dev`，使用项目命名持久卷；它不构建或启动 app/frontend。

默认访问 `http://localhost:8080`，API 与 UI 同源。Java watcher 编译失败时保留上一个可用上下文，不触发重启；修复编译后才触发 DevTools。`pom.xml` 依赖变化由 watcher 受控重启 app；Flyway 迁移、环境配置、工具链和 watcher 本身变化须显式维护并验证。共享服务器自动 Flyway 已禁用，普通保存不执行 DDL，见[现场状态](source-runtime-status.md)。不要把两套 App 同时连到共享真实数据库来验证。

Pi 源码开发使用：

```bash
npm --prefix services/pi-runtime run dev
```

运行前注入私有 `PI_RUNTIME_TOKEN`，确认 8090 未被占用。该命令使用 Node `--watch` 监听自身源码；普通 Pi 源码修改不强制重建完整镜像或重新安装 FFmpeg。涉及媒体系统依赖变化时再按 Runtime README 做显式验证。

Windows 使用 WSL2 Linux 环境运行源码启动器：在 WSL2 内安装 JDK 21、Maven 3.9.11 和 Python 3，配置 `JAVA_HOME` 与 `MAVEN`（或 `MAVEN_HOME`），并启用 Docker Desktop 对该 WSL2 发行版的集成。在 WSL2 终端的仓库根目录执行 `python3 scripts/dev.py init` 与 `python3 scripts/dev.py run`。启动器依赖 Unix 的 `fcntl` 文件锁、信号和进程组，原生 Windows PowerShell/Python 不支持该入口。路径、端口和环境变量以 WSL2 内的 `.env.development` 为准。

## Git 协作与个人 fork

日常协作使用以下映射，保留上游和个人 fork 的旧分支名称与历史：

| 分支 | 角色及使用方式 |
| --- | --- |
| `develop` | 日常共享集成基线 |
| `feature/*` | 从 `develop` 建立，完成相应验证后通过 PR 合入 `develop`；需要保留关系时使用 `--no-ff` |
| `main-tree`、`release/*`、`hotfix/*`、历史标签 | 旧发布与恢复资料；只有明确的独立分发或历史恢复任务才使用 |

本地 `origin` 保留上游地址，`fork` 指向个人仓库；推送前核对远端归属。源码可以在提交前运行和验证，不需要版本标签或 Candidate；提交仍只包含本任务文件，不覆盖其他会话的工作。

旧 Gitflow 映射和历次正式标签保留在[历史资料](../history/README.md)及各期回执中，不再作为日常代码运行步骤。

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

最高现存文件为 V150，V149 缺号；本次共享环境已核对 V150，schema/history 指纹保持，后续变更仍须重新核对现场。新迁移先确认没有并行冲突，使用下一个未占用版本。迁移编号有历史缺口，保留现状。已应用 SQL 不编辑、不重命名、不用 `repair` 掩盖校验差异。

先在隔离 MySQL 验证迁移、旧数据保留、索引与幂等行为；涉及财务时核对租户/分组限制、零值与缺失值、首次结算及样品/取消订单。生产回滚应用镜像不会自动回滚 MySQL，需要明确旧应用与新结构的兼容性。

## 已知开发缺口

- 源码模式默认同源端口不等于生产 Nginx 的全部招聘/ERP 代理均已复现；招聘及安装包交接需要独立环境。
- 本地及共享服务器源码环境已启用 dev 专用浏览器刷新，共享服务器同页自动刷新已验收；DevTools 远程更新接口继续禁用。刷新只读取修订状态，不授予远程写入源码的能力。
- [Development checks](../../.github/workflows/development-checks.yml) 已覆盖源码启动器契约、前端语法与资源引用、开发刷新及 Java 开发模式/维护安全测试；在 `develop` 推送、面向 `develop` 的 PR 和手动触发时运行。功能分支单独推送不触发该工作流。这些定向检查不等于完整业务回归，真实依赖和业务链路仍按本次变更单独验证。
- 历史发布脚本依赖 `/opt/heyi/releases/...` 的已保留文件，清理后的目录未必能重新构建；它们是恢复资料，不是源码运行入口。
- 真实模型、官方授权/发布、Windows 安装和所有店铺独立对账，需要各自的授权环境与验收证据。
