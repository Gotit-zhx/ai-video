# 独立服务

这些模块拥有独立进程或镜像边界。主系统 Spring Boot API 继续位于根目录 `src/`，根 Compose 默认只编排主 API、前端、MySQL、Redis 与 Pi Runtime。

| 服务 | 职责 | 默认主 Compose |
| --- | --- | --- |
| [Pi Runtime](pi-runtime/package.json) | 模型传输、分析、生图及图片操作恢复 | 包含 |
| [招聘 Connector](recruitment/README.md) | 招聘业务、SQLite、桌面授权及报表 | 独立部署 |
| [文档转换](document-preview/README.md) | 内部 PPT/PPTX 转 PDF | 可选覆盖启用 |
| [图片证据服务](watermark-detector/README.md) | 水印、前景及图像证据 | 可选覆盖启用 |
| [HOYEE ERP](erp/README.md) | 独立 ERP 及演示工作台 | 独立部署 |
| [家务应用](household/README.md) | 家庭任务、通知及提醒 | 独立部署 |

源码目录改名不改变容器 DNS、服务标识、公共路由或服务器部署目录。例如 ERP 的公开路由仍为 `/erphoyee/`，Pi Runtime 的 Compose 服务名仍为 `pi-runtime`；各服务的数据库、私有配置和持久文件分别保留。

[服务架构图](../docs/guides/architecture.md) · [技术栈](../docs/guides/technology-stack.md)
