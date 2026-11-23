# 项目文档与三处仓库核对 · 2026-11-23

核对时间使用北京时间。共享应用从 `/opt/heyi/dev/ai-video` 运行。GitHub 协作仓库为 `Gotit-zhx/ai-video`。

## 核对基线

| 位置 | 核对开始时的状态 |
| --- | --- |
| GitHub `Gotit-zhx/ai-video` | `develop` 与默认分支指向 `544584cf`，已归档的 Flyway 文件至 V165 |
| 本地工作区 | `develop` 跟踪 `fork/develop`，指向 `544584cf`。已有财务设计文档编辑，保留在原工作区 |
| 服务器运行目录 | `feature/shared-development` 指向 `544584cf`，已暂存 0 个文件，另有 132 个已跟踪文件存在工作区差异和 79 个未跟踪文件 |

共享工作区差异涉及财务、商品采集、迁移和验收。已生效源码以对应回执、运行上下文和文件摘要为准，Git 提交只标识归档基线。V166–V187 及其配套代码需要按各次生效范围核对和归档。共享分支更新走 PR，运行目录同步须先检查同一文件上的其他会话改动。

## 近期生效与归档

| 北京时间 | 已核对事项 | 服务器回执 |
| --- | --- | --- |
| 11 月 21 日 09:22 | V177–V187 显式追加，迁移前历史保留并通过校验 | `finance-workflow-v187-20261121-02be76dd03bc4d90a4f054eeec60b51f/schema-verified.json` |
| 11 月 23 日 09:48 | 财务广告月度范围改动生效，schema V187，鉴权及静态资源核对通过 | `finance-ads-monthly-range-20261123-141f32d47bc8495dadbabf91fa43aa86/verified.json` |
| 11 月 23 日 09:49 | 商品采集刊登配置的脚本、样式、入口及说明核对 | `capture-listing-config-20261122.VgB5Kq7I/verified.json` |
| 11 月 23 日 10:02、10:11 | 财务投影恢复生效。后续样本记录已重新发布的分区和仍待处理的事件、待授权广告账户 | `finance-projection-resume-20261123.yZuHOAFK/verified.json` 和同目录 `jobs-verified.json` |

回执均相对于服务器的 `/opt/heyi/dev/operations/`。本轮通过只读状态核对主应用、watcher、维护门控和备份 timer。财务数据覆盖、金额对账及独立服务仍以各自的业务验收为准。

本次归档包含两项此前已上线的前端修复：TikTok 店铺广告账户缺失金额的显示规则，以及财务报表起止日期的成对校验。两项既有提交在共同的 `frontend/index.html` 中分别更新各自脚本的资源版本。业务边界见[广告账户验收](../finance/finance-ad-shop-account-detail-20261119.md)和[报表日期验收](../finance/finance-report-date-range-20261120.md)。
