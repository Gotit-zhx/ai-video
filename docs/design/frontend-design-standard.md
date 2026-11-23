# 前端设计标准

自 2026-11-23 起，仓库内新建或重构的用户界面统一采用 [Ant Design 官方设计语言](https://ant.design/design.md)。范围包括主站、独立服务的 Web 页面、桌面端和浏览器扩展的用户界面。视觉基线采用官方默认浅色主题。设计稿、实现和验收共用这一入口。

## 设计与实现

- 从官方设计文档和组件示例确定布局与信息层级。颜色、字号、间距、圆角和动效使用 Ant Design 的设计 Token。品牌或主题调整集中到对应模块的主题配置。
- 优先复用适用的组件与交互模式，明确主要操作和状态反馈。按页面实际场景覆盖加载、空数据、错误、禁用、键盘焦点和窄屏布局。
- 使用 React 组件库的模块以该模块锁定的 `antd` 版本为准。实现前查询组件 API 和示例，核对弃用说明。现有原生页面也采用同一视觉标准，具体运行时和迁移方式由各模块重构任务决定。
- 重构验收按实际页面核对布局、状态和关键交互，并回归业务口径、权限、国际化及数据覆盖含义。对变更的 Ant Design 代码运行 CLI 的 `lint`，检查弃用 API 等问题。

## 项目工具

[Codex Skill](../../.agents/skills/antd/SKILL.md) 提供按版本查询组件 API、示例、主题 Token 和迁移说明的流程。仓库直接维护这份适配项目安装路径的 Skill。CLI 作为 [开发工具依赖](../../scripts/antd/package.json) 独立安装，版本由 [npm 锁文件](../../scripts/antd/package-lock.json) 固定。它与各模块的界面运行时版本独立。

从仓库根目录执行以下命令安装工具并读取设计语言。Node.js 版本要求见工具清单的 `engines`。

```bash
npm --prefix scripts/antd ci --global=false
./scripts/antd/node_modules/.bin/antd design.md --format json
```

官方资料见 [Agent 指南](https://ant.design/docs/react/for-agents-cn.md)、[CLI 命令说明](https://ant.design/docs/react/cli-cn/)和[主题定制](https://ant.design/docs/react/customize-theme-cn/)。
