# 项目技术栈

核对日期：2026-11-05。版本取自仓库声明、锁文件、镜像及 vendored 资源，不代表已连接生产核验全部运行版本。带 `^` 或范围的依赖保留原声明；实际安装版本应由对应锁文件确定。

## 主系统与基础设施

| 层次 | 技术与仓库版本 | 主要来源 |
| --- | --- | --- |
| 后端语言 / 框架 | Java 21、Spring Boot 4.1.0、Maven 3.9 构建镜像 | [pom.xml](../../pom.xml)、[Dockerfile](../../Dockerfile) |
| HTTP / 权限 | Spring MVC、Spring Security、Bean Validation、Spring Session Redis、BCrypt | [后端清单](../../pom.xml)、`auth/`、`config/` |
| 数据访问 | MyBatis-Plus 3.5.17、JdbcTemplate、MySQL Connector/J | [后端清单](../../pom.xml) |
| 数据库 / 迁移 | MySQL 8.4、Flyway；最高现存迁移 V147，历史有缺号 | [Compose](../../compose.yaml)、[迁移](../../src/main/resources/db/migration/) |
| 缓存 / 会话 / 队列 | Redis 8、Redis Session、Redis Stream 与业务持久任务表 | [Compose](../../compose.yaml)、`workflow/`、`finance/` |
| Web | 原生 HTML / JavaScript / CSS；没有 React/Vue 构建工程 | [前端](../../frontend/) |
| 图表 / 3D | Apache ECharts 6.1.0、Three.js r170（0.170.0） | [本地第三方资源](../../frontend/vendor/) |
| 反向代理 | Nginx 1.29 Alpine | [前端 Dockerfile](../../frontend/Dockerfile) |
| 图像 / 媒体 | FFmpeg、Java ImageIO/WebP；图片传输与模型运行时另有实现 | [后端 Dockerfile](../../Dockerfile)、[Runtime](../../services/pi-runtime/Dockerfile) |
| 文件 | Aliyun OSS SDK 3.18.4、Dayan/S3 兼容对象存储、Docker 持久卷 | [后端清单](../../pom.xml)、[存储说明](../business/storage/dayan-object-storage.md) |

## 独立服务

| 模块 | 技术与声明版本 | 边界 |
| --- | --- | --- |
| [Pi Runtime](../../services/pi-runtime/package.json) | Node.js 22 镜像、Pi Agent Core / Pi AI 0.82.1、Undici 7.16.0、ali-oss 6.23.0 | 模型传输、分析、生图、操作状态和恢复；内部令牌访问 |
| [招聘服务](../../services/recruitment/README.md) | Python、标准库 HTTP/SQLite、boss-agent-cli 2.0.0；oss2 2.19.1、pypdf 6.19.0、defusedxml 0.7.1、cryptography 50.0.1 | 平台业务及桌面授权；与桌面打包 Python 版本分别核对 |
| [文档转换](../../services/document-preview/README.md) | 固定 Python 基础镜像、LibreOffice Impress / UNO、Noto CJK | 内部 PPT/PPTX 转 PDF，无公开端口 |
| [图片证据服务](../../services/watermark-detector/README.md) | Python 3.11、NumPy 2.2.6、Pillow 11.2.1、OpenCV 4.12.0.88、ONNX Runtime 1.22.1 | custom 引擎；YOLO 基线另声明 ultralytics 8.3.160 / torch 2.7.1 |
| [HOYEE ERP](../../services/erp/README.md) | Python 3.12、Waitress 3.0.2、SQLite WAL、cryptography 46.0.3 | 独立身份、数据、公开 `/erphoyee/` 路由 |
| [家务应用](../../services/household/README.md) | Python 3.12、Flask 3.1.2、Gunicorn 23.0.0、pywebpush 2.1.2、SQLite | 独立部署、提醒调度及家庭访问凭据 |

## 客户端

| 模块 | 技术与声明版本 | 构建 |
| --- | --- | --- |
| [通用自动化助手](../../apps/automation-agent/package.json) | Electron `^43.2.0`、electron-builder `^26.15.3`、ws `^8.21.1` | npm 锁文件；按 Windows/macOS 目标构建 |
| [招聘桌面壳](../../apps/hr-desktop/package.json) | Electron 44.4.5、electron-builder 26.15.3、Python 3.12 打包运行时 | pnpm 锁文件；固定 SDK 及构建指纹校验 |
| [Ai boss 工作台](../../apps/hr-desktop/client-workbench/package.json) | Node/Electron 原客户端适配、Undici 5.29.0、ws 8.22.0 | 独立工作台及 Windows 安装器；源码包版本 0.1.27 |
| [商品采集扩展](../../apps/browser-extension/package.json) | Chrome/Edge Manifest V3、原生 JavaScript，版本 0.2.8 | 静态扩展与专用检查脚本 |
| [Profile 工具](../../apps/chrome-profile-manager/package.json) | Electron `^43.2.0`、PowerShell 本机启动 | 独立本机工具 |

应用版本、依赖版本和最低允许客户端版本分别维护，不能相互代替。

## 实验与工程

服装重建实验使用 Python、NumPy 1.26.4、SciPy 1.15.3、OpenCV 4.11.0.86，以及 Three.js / WebGPU；属于限定原型，见[实验 README](../../experiments/garment-reconstruction/README.md)。MiniMax 视频实验和提示词位于 `experiments/video/scripts/`。

测试包含 JUnit / Spring Boot Test / Mockito、Node 原生测试、JSDOM 页面模拟、Python unittest、隔离 MySQL 与本地 HTTP 模拟。真实模型、官方发布、完整 Windows 账号流程和恢复演练需分别记录验收。

GitHub Actions 提供显式选择平台的桌面构建，CircleCI 提供显式触发的 Windows 备用构建。当前并没有覆盖主业务后端/前端的统一完整 CI；配置检查通过不等于已经完成所有业务回归。

项目目录及模块关系见[组织结构](project-structure.md)，Git 协作遵守 [AGENTS.md](../../AGENTS.md) 的 Gitflow 规则。
