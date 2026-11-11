# 广告消耗工作台上线验收 — 2026-11-11

`v2026.11.11.2` 已完成 V150 独立维护、应用激活、真实数据功能验收和 Gitflow 合回。随后用户报告主表布局回归，已在 PR #54 修复并合入源码基线，随后于当日完成前端源码直服切换和正式页面布局验收；不能把字段和接口通过等同于视觉布局通过。

## 发布范围与 Gitflow

广告分析以分组广告消耗、店铺广告消耗、店铺消耗明细三张表组织 TikTok 和 Shopee 数据；保留现金、信用额度、广告赠款和合计。官方来源明确时自动归属，跨店且缺少真实金额时待分配；明确核实的分配保留版本，API/资金导入可确认重叠不重复累计。资金来源表独立保存，不修改原利润流水。

功能 PR #49 合入 develop 后，通过 PR #50 纳入已发布成本修复。发布 PR #52 的候选源为 `c0304551dbad584ae468df4f1db7d75fd6507bfa`，生产 no-ff 合并为 `3e5b23f43d734e581b481deefcd4ff8ee51b38eb`，正式标签 `v2026.11.11.2`。root 后续文档与客户端竞态修复已在 develop 的 `16a817b8e482374288e2ed2af4d5eaaacbb4d633`，最终合回须保留。

完整验证包含 Java 2,400 项、46 个隔离数据库测试类的 1,535 项用例、前端 736 项及 35 组 UI、容器与 systemd 回归；可信同树 CI `42942787379` 由发布 PR `42944124246` 重新核验复用，候选构建 `42944324798` 成功。v2/full 清单与源码均完成 GitHub 来源证明校验；未重建或覆盖候选。

## 独立控制面与结构维护

控制升级使用已审查的历史独立执行器，适配后仅允许新增两个固定摘要的 V150 helper 及固定摘要 Host 兼容判断，其他 operations 文件集合及内容锁定。11 项隔离恢复/资格/拒绝测试及独立审查通过。systemd 安装单元 `heyi-control-upgrade-ee098893271269558257c313` 成功，原容器、配置、数据库、备份策略保持；完整保留集 `.11.11.1`、`.11.10.7`、`.11.10.5` 均通过回滚预检。

V150 维护操作 `a194383f53ec4bdca94d9352d153ad66` 已成功。只新增三张资金/分配表；原迁移历史前缀、账户、官方店铺关系、同步状态、分页与限流摘要保持。旧 `.11.11.1` 在新结构上实际重启、旧 Flyway 校验与健康/鉴权检查通过，门控恢复。MySQL DDL 尝试前落盘标志，结果不明时保留门控并拒绝自动重放；V148 原工具和回执未修改。

维护后再次验证完整保留集的不可变清单及真实 rollbackPreflight 全部通过。结构兼容登记不替代镜像/配置/原始资格检查，不代表数据回滚。

## 部署尝试与保护边界

唯一发布 journal `c125201f65224ea7bd71ef5b88dfbb2f`，CD run `42946585443`。attempt 1 在 stage-plan 服务端命令 TimeoutExpired，未提交 deploy 请求；旧 operation `5328eacba796eb6398a3a89f7a0e6635` 的 requests/operations 均不存在，服务器继续运行 `.11.11.1`。保留失败事实后仅重试同一失败 CD job，attempt 2 已成功，真实 operation 为 `743d5409ee2e1b478b2a85ac24ae26d9`。新版客户端通过显式 `--adopt-deploy-attempt 2` 核验旧失败事实、新成功清单/回执与实际 operation 后接纳该尝试，保留原 journal ID，完成 Gitflow 合回；原失败 artifact 和 tag 均保留。

用户示例工作簿未导入生产，也未进入 Git；真实页面使用已授权 API 已入库记录。API 未提供资金分项时保留未知，不填零或按比例估算。Shopee Ads 权限和新的普通广告店铺粒度采集不在本次上线中重新宣称已解决。

operationsInputs：`32f410d9017f7f4de6e5a540f2dbac7bb457531ea797fd5d0a42247b2efeaeb1`。

migrationInputs：`4f168438e3892a398687c4dad216666fccd65804265cb319becc5e6e0dc90af4`。

schemaFingerprint：`06b53a20e58d8e0b51ecc3fbb929512efc0e13559d1dd6deae26a2d48b744fb3`。

候选镜像：

- app：`ghcr.io/gotit-zhx/ai-video/app@sha256:7b5c8095a01925b59d776cd9710869b97a0aea1166b5a2d6390ca38c3b799682`。
- frontend：`ghcr.io/gotit-zhx/ai-video/frontend@sha256:3d845d2f2faa96ead49317f3ab312c673940bc6c2684230234edcbb58ed7deb3`。


## 激活后复核与后续样式修复

正式站点现有授权会话完成三张主表、三项资金表头、空值语义、明细分页及跨页全量汇总的只读核对，没有向真实店铺写测试数据。主机健康、鉴权、静态/gzip、配置及完整保留回滚集 `.11.11.2`、`.11.11.1`、`.11.10.7` 的真实预检通过，维护关闭，无未完成运维和告警。

应用与前端 Compose 链均为 `/opt/heyi/ops/compose/base.json`、`/opt/heyi/ops/compose/production.json`、`/opt/heyi/ops/releases/v2026.11.11.2/images.json`；源码位置为 `/opt/heyi/source/releases/v2026.11.11.2`。后续环境采用新的源码运行计划时，须重新核对现场，不把这些历史路径当作永久运行契约。

用户随后指出三卡片被压缩成窄列。只检查 DOM 字段和分页遗漏了视觉几何问题；详见 [布局修复](finance-ad-spend-layout-20261111.md)。修复源码在 PR #54 以 no-ff 合入 `develop` 的 `548c8ae235600a3bdf35a599d40172bfa45f8349`，未创建 `.3` 软件包或标签，已通过下述前端源码切换实际生效。旧预览 helper 的额外行内跨度已移除，补做 840/480 窗口的实际卡片宽度与表内滚动验证。

## 前端源码切换与布局验收

2026-11-11 12:10（北京时间），经运维串行委派，维护 `b4ecbdf1013b47c5958343070432e099` 成功。源码根 `/opt/heyi/dev/ai-video` 来自已验收 `.2` 的可信 archive：逐文件核对完整集合，拒绝额外文件、内容漂移与链接，仅叠加 `75e11ea09ab18f4f5dac377ebd233f82646af269` 的 `frontend/finance-workspace.css` 和 `frontend/index.html`。未生成软件包或新镜像。

前端 Compose 在原三个文件后追加 `/opt/heyi/dev/source-development.frontend.json`，只读挂载源码前端和 `/opt/heyi/dev/frontend.nginx.conf`。保留原 TLS、certbot、downloads、control、环境、网络、日志及代理配置；只关闭预压缩静态文件、文件缓存并设置静态 `no-store`，私有 Nginx 文件权限 `0600`。原前端镜像不变，后端容器 ID 保持；数据库结构指纹不变，未修改授权、业务数据、同步计划或备份。

关键技术回执：

- 前端容器：`e40d7534fccac954ed1b281da3a3208f90c4e61d84a405b3c3da7a46a35fde53`。
- 前端规范化配置：`d86702da205627ab911e110ddc1461049d88a222f8716c67d76e16b89cf3c401`。
- CSS SHA-256：`2b922d4a0bc5f227fd38149fbe1d991069a6ef154384a16379fe97747e7cc0c4`。
- index SHA-256：`74cf97894baa3c40bfea388f163fa476b542356e0f38619c0edf993509b168c1`。
- 主机回执：`/opt/heyi/dev/operations/frontend-source-b4ecbdf1013b47c5958343070432e099/verified.json`。

维护 worker 的四项隔离测试及独立审查通过，覆盖只允许两项只读挂载、缓存最小改写、额外源码拒绝，以及晚失败时重新门控排空恢复和重复恢复幂等。首次 systemd 分派因远程参数引用失误以 `203/EXEC` 结束，未启动 worker、未生成维护 state、未切换业务；修正引用后第二单元成功结束。真实 HTTPS 解压后两文件摘要一致，健康返回 200，维护期财务入口 503、恢复后匿名入口 401，门控已解除。

正式登录页面读取 CSS token `20261111-ad-spend-layout-1`：840 像素窗口三张卡片均为 796 像素且分行；480 像素窗口三卡片均为 456 像素，各表在自身 `overflow:auto` 容器滚动，页面宽度和滚动宽度均为 480 像素。测试后恢复默认视口并保留含表头的正式页面截图。只验证当前业务数据展示与布局，未重新宣称缺失 API 分项已补齐。

按统一源码模式委派将 `selfHealingEnabled` 从 `true` 关闭为 `false`，避免旧镜像自愈覆盖源码挂载；旧 active、Release 和 baseline 保留为历史镜像基线，不能作为源码模式的新完整状态。运行根、完整 Compose 链、摘要、回执及窗口释放已交接运维，后续后端源码切换须复用该根和前端覆层，并现场复核最新状态。
