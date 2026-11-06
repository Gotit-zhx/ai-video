# 客户端应用

这里存放独立运行和单独发行的客户端；主站 Web 前端继续位于根目录 `frontend/`。

| 应用 | 职责 | 开发入口 |
| --- | --- | --- |
| [通用自动化助手](automation-agent/README.md) | 本机环境、浏览器和自动化任务执行 | [package.json](automation-agent/package.json) |
| [商品采集扩展](browser-extension/README.md) | Chrome/Edge 商品页采集、平台登录与上传 | [package.json](browser-extension/package.json) |
| [招聘桌面助手](hr-desktop/README.md) | 招聘本机执行、SDK 运行时、Ai boss 工作台 | [工作台](hr-desktop/client-workbench/README.md) |
| [Profile 工具](chrome-profile-manager/README.md) | 本机 Chrome Profile 管理 | [package.json](chrome-profile-manager/package.json) |

各程序分别维护安装包、用户资料和依赖；本机凭据及 Profile 不随源码迁移或进入 Git。招聘桌面构建复用 `services/recruitment/` 的服务端策略，打包前校验来源指纹。

[目录与模块图](../docs/guides/project-structure.md) · [技术栈](../docs/guides/technology-stack.md)
