# 财务切换范围后金额不显示修复

2026-10-29

## 原因与修复

总览、明细、对账及大屏会将接口解析的原币写回筛选状态。店铺和平台选择器原来只修改店铺/平台，没有清除旧的 `currency` 和 `fxSnapshot`。因此 PHP 店铺切换到 MYR 等币种的店铺时，仍按 PHP 过滤，导致结果为空。点击原币/人民币按钮会清除这两个字段，所以能够恢复。

`frontend/finance-workspace.js` 新增统一的筛选更新入口：

- 店铺、分组或平台变化时，清除此前解析的原币和汇率快照，重新加载新范围。
- 保留日期、展示币种和编号查询，分页归回第一页。
- 显式选择原币仍然有效，切换展示币种会重新解析；明细等待当前范围的币种和汇率就绪。
- 分组经营明细跳转、贴单配置跳转、费用页返回报表、删除当前店铺/分组均使用同一入口。
- 保留现有请求序号检查和增量渲染；过期请求不能覆盖新店铺。
- 成本资料和订单成本仍以人民币存储、展示，成本查询与导出保持相同范围。

本次仅修改前端筛选状态，不修改结算日期、收入、利润公式、汇率计算及成本凭据。

## 验证

新回归 `scripts/test-finance-scope-currency-ui.mjs` 在上一线上版本复现旧币种残留；在修复版通过：

- 总览、结算明细/账单/待结算、订单/退款、对账、平台费用/物流贴单成本、广告/GMV Max、大屏。
- PHP/MYR/THB/SGD 店铺，原币和人民币模式，全部店铺、全部平台、分组、未分组。
- 明细金额显示、新汇率解析、分页重置、快速连续切换的过期响应拦截。
- 日历保留、大屏往返、费用页跨店/跨组后返回总览。
- 成本明细切店/切组以及结算、成本 Excel 的筛选一致性。

另有 9 组既有回归通过：`operating-currency`、`query-speed`、`filter-retention`、`profit`、`order-dates-search`、`pending-costs`、`costs-live`、`charges`、`shop-deletion`（均为 `scripts/test-finance-*-ui.mjs`）。JavaScript、发布脚本语法及差异空白检查通过。

## 上线

- 发布目录：`/opt/heyi/releases/finance-scope-currency-20261029`。
- 前端镜像：`ai-commerce-frontend:finance-scope-currency-20261029`。
- 基于当前运行镜像覆盖 `finance-workspace.js`、`index.html` 及各自 `.gz`，保留其他资源。
- 脚本：`scripts/release-finance-scope-currency.py`，包含现场文件校验、容器配置保留、验证失败自动回滚。
- 入口版本：`finance-workspace.js?v=20261029-scope-currency`。
- 部署后 Nginx 配置、HTTPS 普通/压缩资源 SHA-256、后端健康均通过。
- 从本机通过公网域名下载入口和带新版参数的脚本，SHA-256 与发布内容一致。
- 后端、数据库、缓存和同步容器未重启，运行日志配置保持原状。
