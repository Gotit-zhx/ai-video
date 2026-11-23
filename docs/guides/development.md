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

可选新版财务的 `frontend/modern/` 和 `frontend/assets/finance-modern/` 不计入整站自动刷新修订。重新进入新版财务时读取新资源清单，保留当前打开页面的录入状态。

财务 BI 主导航直接按当前用户的 `platformAdmin`、`ADMIN`、`finance:view` / `finance:manage` 判断可见性，不能依赖后加载工作台脚本是否已注册。工作台及财务 API 仍独立鉴权。导航时序回归入口为 `node --test frontend/test/finance-navigation.test.cjs`。

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

TikTok 与 Shopee 广告应用的凭据文件、授权状态和历史报表覆盖分别核对。共享服务器使用受限挂载，配置模板随仓库保留。现用入口见[广告费用统计](../business/finance/advertising.md#广告授权与账户显示)。

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

## 快速迭代流程

日常从 `develop` 建立 `feature/<主题>`，按任务使用独立 worktree。共享试用、上线和紧急热修按现有权限及[源码运维流程](operations.md#源码变更与热重启)执行，可以先于提交或 PR 合并。PR 和现有必要检查用于合并及归档。变更记录可直接写在任务说明、PR 或已有验收回执里，用于交接和恢复。

### 快速试用

1. 确定负责会话、文件范围和一条可判断保留或弃用的验收条件。碰到同一文件的并行改动，先确定谁负责整合。需要缩小影响时，使用现有功能开关、独立入口或指定企业试用。
2. 在本地或隔离环境运行与改动对应的最小验证。进入共享环境前，按实际运行文件记录可恢复基线。现场文件与 Git 不同时，记录受影响文件的摘要和可恢复版本位置。撤回时按该范围恢复。
3. 用既有定向同步或 Remote SSH 更新本次文件，现场验证试用入口、权限和关键行为。确认保留后经 PR 合入 `develop`，核对服务器生效内容与归档提交。

### 功能弃用

1. 结束试用并写明弃用原因。仍在任务工作区、尚未生效的改动在本任务收尾，关闭对应的未合并 PR，保留接手和试用证据。
2. 共享环境已启用的改动走[上线后回退](#上线后回退)。已合入 `develop` 的部分通过新的回退 PR 移除，保留共享历史。只撤销本功能的范围。
3. 确认入口关闭或旧行为恢复，核对关联配置、任务及数据影响。记录「弃用」结论、实际生效版本和剩余处理事项。

### 上线后回退

1. 核对当前运行源码、受影响的文件、配置和已领取任务。选定已验证且兼容当前数据库、接口和配置的恢复版本。遇到后续并行改动时，由负责整合的会话仅撤销本功能引入的差异。
2. 有现成开关或可控入口时，先缩小影响。需要恢复源码时，定向还原本次范围。前端保存后验证实际页面，Java 变更走原维护锁、安全点、编译和重启流程。已应用的 Flyway 迁移保留原件及校验值，结构变更通过新增迁移处理。已写入的业务数据和已发生的外部操作按各自业务流程核对。
3. 确认触发回退的问题解除或试用入口关闭，核对关键行为、权限、健康及本次门控恢复。记录实际恢复版本和遗留影响，归档对应的回退提交或 PR，核对服务器与 Git 的结果。

### 紧急热修

1. 从实际生效的源码和故障样本确认影响范围及可恢复基线。用独立 worktree 承接修复，按 `feature/<主题>` 归档。明确共享文件的整合会话与维护状态。
2. 修补限于故障范围，在本地或隔离环境优先跑覆盖故障的定向验证。需要立即恢复时，在现有维护流程内先缩小影响或执行回退，随后继续修复和补验。共享环境按文件同步，验证原故障是否消失及关键链路是否正常。
3. 在同一任务中补齐能够执行的回归，记录跳过及未验证项。通过 PR 归档已生效的修补，核对 Git、服务器文件和运行状态。

### 变更记录模板

```text
任务与负责会话  <任务或 PR、日期、负责人或接手入口>
改动与验证范围  <文件、模块、试用对象及验收条件>
生效前基线与退回  <提交或文件摘要、可恢复版本位置及退回方法>
验证结果        <本地或现场的通过、失败、跳过和待验证项>
现场与 Git 状态  <共享环境及实际生效版本、提交或 PR、验收回执>
结果            <试用中、保留、弃用或已回退，附剩余事项>
```

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

仓库迁移链已归档至 V187。11 月 23 日只读核对确认新增的 V166–V187 均与共享环境成功应用的记录一致。分配新编号时同时核对共享环境和并行任务，使用尚未被占用的版本。V153 保留已应用的成本录入历史结构，原功能继续撤回。V160 保留已应用的商品采集迁移，财务记录身份和币种字节变更由已应用的 V161 一并处理。已执行旧版 V154 且缺少 V153 的本地开发库，应先在副本验证缺号补齐或使用新的隔离库。保留历史缺号、已应用 SQL 及其校验值。见[源码运行现场状态](source-runtime-status.md)。

迁移链回归可运行 `FlywayProductionChainMySqlTest`。使用空的 `migration_sync_qa_<十六进制后缀>` 数据库，提供专用 `MIGRATION_SYNC_QA_JDBC`、`MIGRATION_SYNC_QA_USER` 和 `MIGRATION_SYNC_QA_PASSWORD`。测试从空库执行到生产 V160、写入模板样例，再升级到 V187，检查历史、模板数据和重跑幂等性。

先在隔离 MySQL 验证迁移、旧数据保留、索引与幂等行为；涉及财务时核对租户/分组限制、零值与缺失值、首次结算及样品/取消订单。生产回滚应用镜像不会自动回滚 MySQL，需要明确旧应用与新结构的兼容性。

## 已知开发缺口

- 源码模式默认同源端口不等于生产 Nginx 的全部招聘/ERP 代理均已复现；招聘及安装包交接需要独立环境。
- 本地及共享服务器源码环境已启用 dev 专用浏览器刷新，共享服务器同页自动刷新已验收；DevTools 远程更新接口继续禁用。刷新只读取修订状态，不授予远程写入源码的能力。
- [Development checks](../../.github/workflows/development-checks.yml) 已覆盖源码启动器契约、前端语法与资源引用、开发刷新及 Java 开发模式/维护安全测试；在 `develop` 推送、面向 `develop` 的 PR 和手动触发时运行。功能分支单独推送不触发该工作流。这些定向检查不等于完整业务回归，真实依赖和业务链路仍按本次变更单独验证。
- 历史发布脚本依赖 `/opt/heyi/releases/...` 的已保留文件，清理后的目录未必能重新构建；它们是恢复资料，不是源码运行入口。
- 真实模型、官方授权/发布、Windows 安装和所有店铺独立对账，需要各自的授权环境与验收证据。
