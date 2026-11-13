# 项目组织结构与目录约定

主体更新日期：2026-11-11；财务前端工程补充日期：2026-11-12。仓库按主系统、客户端、辅助服务、工程工具和实验资料分组。当前主应用直接运行源码；外部 API 路由、服务名、业务数据目录、数据库迁移和共享服务器数据路径保持原有含义。

## 目录图

```text
ai-video/
├── src/                         主系统 Java 后端与测试、Flyway 迁移
├── frontend/                    主系统 Web 静态前端、资源及前端测试
│   ├── finance-entry.js         财务双入口与互斥生命周期门面
│   ├── finance-host.js          旧登录、导航、权限及资源适配边界
│   ├── modern/                 独立 React/TypeScript 财务工程
│   │   ├── src/app/            挂载、提供器、错误回退与模块样式
│   │   ├── src/shared/         UI、会话契约、请求与样式隔离能力
│   │   ├── src/features/finance/ 财务业务页面及操作
│   │   ├── tests/              组件与接口契约测试
│   │   ├── browser/            隔离 HTTP 夹具和真实浏览器回归
│   │   └── scripts/            AntD、源码兼容与资源预算门禁
│   └── assets/finance-modern/  生成的哈希资源与原子清单，不进 Git
├── apps/                        独立客户端
│   ├── automation-agent/        通用 Electron 自动化助手
│   ├── browser-extension/       商品采集浏览器扩展
│   ├── chrome-profile-manager/  本机 Chrome Profile 工具
│   └── hr-desktop/              招聘桌面壳、工作台与 Python 打包运行时
├── services/                    独立运行或单独部署的服务
│   ├── pi-runtime/              Node.js 模型与图片操作运行时
│   ├── recruitment/             Python 招聘 Connector、业务服务及桌面桥接
│   ├── document-preview/        私有 Office 转 PDF 服务
│   ├── watermark-detector/      可选图片/前景与水印证据服务
│   ├── erp/                     独立 HOYEE ERP
│   └── household/               独立家务应用
├── scripts/                     工程与运维工具，按用途分类
│   ├── release/                 历史及专项发布 helpers
│   ├── finance/                 财务 Java 核对及维护程序
│   ├── qa/                      测试、模拟与冒烟脚本
│   ├── diagnostics/             检查、审计、诊断与样本核对
│   ├── maintenance/             修复、恢复、清理及专项维护
│   ├── local/                   本地回调白名单隧道
│   ├── build/                   静态资源构建工具
│   ├── ci/                      服务/桌面 CI、制品与验收辅助程序
│   ├── video/                   既有视频专项运维入口
│   ├── dev.py                   主应用源码开发初始化与运行入口
│   ├── compact-release-image.py 保持稳定路径的镜像压缩保护
│   └── heyi-mysql-backup.py     保持稳定路径的备份实现
├── experiments/                 隔离实验与参考，非默认生产服务
│   ├── garment-reconstruction/ 服装几何重建与计算实验
│   ├── video/scripts/           MiniMax 实验及提示词
│   └── ui/apple/                保留的视觉参考
├── ops/                         系统服务、备份、反代与管理配置
├── config/                      私有配置的公开模板
├── docs/                        开发、业务、发布、设计、研究与历史文档
├── .github/                     GitHub Actions 服务 CI/CD、巡检与桌面构建
├── .circleci/                   显式触发的备用 Windows 构建
├── pom.xml / maven-settings.xml Maven 构建入口
├── compose.dev.yaml             本地隔离 MySQL/Redis，仅依赖服务
├── Dockerfile*                  主系统后端镜像入口
├── compose*.yaml                历史恢复及其他场景 Compose 入口
├── AGENTS.md                    通用协作、数据保护与源码运行约束
└── README.md                    项目首页
```

Maven 的 `src/`、`pom.xml` 和主站 `frontend/` 继续留在根目录，供源码运行直接使用。历史制品/恢复工具继续读取既有路径；独立客户端与独立服务归入各自父目录，不用兼容软链接维持一套重复树。

## 模块组织图

```mermaid
flowchart TB
    Repo[赫逸平台单仓库] --> Core[主系统]
    Repo --> Apps[客户端 apps]
    Repo --> Services[独立服务 services]
    Repo --> Engineering[工程与运维]
    Repo --> Research[实验与参考]
    Core --> API[Java 后端 src]
    Core --> Web[Web 前端 frontend]
    Apps --> Desktop[通用助手 / 招聘助手 / Profile 工具]
    Apps --> Extension[商品采集扩展]
    Services --> Pi[Pi 模型运行时]
    Services --> HR[招聘 Python 服务]
    Services --> Media[文档转换 / 图片证据服务]
    Services --> Independent[ERP / 家务应用]
    Engineering --> Tooling[scripts / ops / config / CI]
    Engineering --> Docs[docs 开发与业务说明]
    Research --> Experiments[服装 / 视频 / UI]
```

## 主系统内部组织

Java 后端是一个 Maven / Spring Boot 应用，不因目录分组就成为多个微服务。`com.yingdao.aicommerce` 下按业务域组织，部分域使用 `web/application/domain/mapper`，财务域主要按功能类组织。

```mermaid
flowchart TB
    Backend[主系统 Java 应用] --> Foundation[企业与基础能力]
    Backend --> Business[业务域]
    Backend --> Execution[执行与任务]
    Foundation --> Identity[auth / saas / config]
    Foundation --> Data[storage / knowledge / common]
    Business --> Commerce[product / shop]
    Business --> Finance[finance]
    Business --> Video[video]
    Business --> Recruitment[recruitment AI 入口]
    Execution --> Jobs[workflow / task / automation / assistant]
```

这些是职责分组，尚未通过独立 Maven 模块或自动依赖检查强制隔离。招聘主体业务实际位于 Python 服务；ERP 和家务应用各有独立数据及运行边界。

## 新代码放置规则

- 主系统功能先归入既有业务域；Controller、计算、查询及工作器保持职责清晰，避免继续堆入通用大文件。
- 新版财务业务放 `frontend/modern/src/features/finance/`，公共能力放 `shared/`，旧系统会话和 DOM 适配限定在 `finance-host.js`。业务页不嵌入旧 `window.Finance*` 组件；旧财务继续保留，清理与默认切换需要用户另行明确确认。其他模块后续复用公共能力，避免另建一套登录、请求或组件主题。
- 独立桌面程序或浏览器扩展放 `apps/`，独立进程服务放 `services/`，分别维护清单、依赖、测试和 README。
- 工程脚本放对应 `scripts/` 分类，真实业务服务不得继续混进工具目录。脚本分类不等同执行安全等级，运行前必须核对读写范围与目标环境。
- 原型放 `experiments/`，不进入默认源码运行入口；实验结论与真实业务验收分别记录。
- 源码路径变更同步更新 dev runner、Compose、CI、跨模块导入、测试及文档。外部路由、服务标识、数据卷和共享服务器目录不能跟随源码改名一起替换。
- 历史发布、恢复及备份的稳定入口继续保留；服务端已保留的绝对依赖不因源码模式切换而删除或搬迁。

技术版本见[技术栈](technology-stack.md)，运行数据流见[架构说明](architecture.md)，操作入口见[开发指南](development.md)。

## 迁移边界与核对

本次归类移动 822 个现有源码/工具文件，主要映射如下；每个模块保留其依赖清单、内部测试、运行资源及构建文件。

| 原位置 | 新位置 |
| --- | --- |
| 四个独立客户端根目录 | `apps/` 下对应客户端 |
| `scripts/boss-agent/` | `services/recruitment/` |
| `pi-runtime/`、`document-preview/`、`watermark-detector/` | `services/` 下对应服务 |
| `erphoyee/`、`household/` | `services/erp/`、`services/household/` |
| `garment-reconstruction/` | `experiments/garment-reconstruction/` |
| `scripts/release-*.py` | `scripts/release/` |
| 财务 Java 工具 | `scripts/finance/` |
| 测试、诊断、维护及本地工具 | `scripts/qa/`、`diagnostics/`、`maintenance/`、`local/` |
| MiniMax 实验和提示词 | `experiments/video/scripts/` |

跨模块导入、招聘运行时来源指纹、GitHub/CircleCI 路径、Compose 构建上下文及文档引用同步调整。历史 `git show <提交>:<路径>` 和部署用绝对路径保留对应历史含义；源码指纹 JSON 不改写。前端两个 Dockerfile 原有漏包资源同时补齐。

最初整理阶段及 2026-11-06 的 Gitflow/发布事实保留在历史回执。当前日常协作改为 `feature/*` → `develop` PR，运行源码不要求标签或正式发布；共享服务器切换是否完成仍以最新现场回执为准。

## 整理验证结果

- 822 个原文件均存在于对应新位置；18 份依赖清单/锁文件/requirements 和 22 份迁移的二进制资源逐项哈希保持。
- 主系统 Java 源码、Flyway 迁移、Maven 依赖没有修改。Compose 静态对比除构建上下文外一致，服务名、环境、端口、网络与数据卷不变。
- Python 341 文件、Node 395 文件、JSON 28 文件和 Shell 19 文件通过语法检查。
- 客户端、Pi Runtime、招聘、ERP、家务、文档转换、构建/导出及恢复的 810 项定向测试通过；采集扩展检查通过。Node 测试使用 24.22.1，Python 使用 3.12.14。
- 前端全量 802 项在迁移前快照有 173 项失败，整理后为 171 项，新增失败 0；修复的两项是 Dockerfile 页面资源打包检查。其余既有失败未通过修改业务代码或测试掩盖，不能宣称全量回归通过。
- 未运行 Java/Maven 全量构建、Windows 整包/真实账号验收、付费模型或官方发布，也未进行生产部署。

小型验证回执见[project-structure-verification.json](project-structure-verification.json)。
