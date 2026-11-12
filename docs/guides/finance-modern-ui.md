# 财务 BI 双界面开发

旧版财务 BI 继续作为默认入口。新版由当前标签页 URL 中的 `financeUi=modern` 选择，返回旧版使用 `financeUi=legacy`；界面选择不写入全局浏览器偏好。体验入口只在完成完整验收后开放，旧版代码与入口的删除另需用户明确确认。

旧版总览的体验链接在新标签页打开，只传递有效的日期、店铺、分组、平台、原币及展示币种，不传汇率快照、凭据或授权码。两边共用正式 API、Cookie、权限和业务配置；新版保存会影响同一正式业务数据。筛选、分页、弹层、展开及草稿由各标签页分别管理。

## 代码边界

| 位置 | 职责 |
| --- | --- |
| `frontend/finance-entry.js` | URL 白名单、互斥界面选择、旧版回退、生命周期门面 |
| `frontend/finance-host.js` | 旧会话与权限、语言主题订阅、导航、授权回执、资源加载 |
| `frontend/modern/src/shared` | 请求与取消、会话契约、主题、表格、弹层和国际化能力 |
| `frontend/modern/src/features/finance` | 财务页面、字段、查询、导入导出和业务操作 |
| `frontend/modern/src/app` | React 挂载、QueryClient 与页面注册 |

财务业务页不读取旧版 DOM，不调用旧 `window.Finance*` 组件。所有现代样式、Ant Design 弹层和消息都放在本页 ShadowRoot 中；整站迁移时替换宿主适配层即可复用业务页面。只使用旧版或其他模块时不下载 React 财务资源。

## 本地编译与校验

使用 Node 22.12+，CI 以 Node 22 为准：

```bash
npm --prefix frontend/modern ci --ignore-scripts
node scripts/ci/qa/node_modules/playwright/cli.js install chromium firefox webkit
npm --prefix frontend/modern run verify:ci
```

持续编辑使用 `npm --prefix frontend/modern run dev`，不要与同一 worktree 的另一编译器同时运行。它监听 TypeScript 并重新发布资源；手动刷新新版查看更新。旧版标签页不会因新版重建自动刷新。已有 `scripts/dev.py run` 在需要时完成一次编译；共享服务器不得自动同步所有 worktree，也不以本机源码启动器重启共享运行任务。

重建排除规则须由正在运行的源码监视器实际加载；旧 Python 进程不会自动载入修改后的 `dev.py`。共享服务器首次接入时核对监视器、其他会话的维护和现有 `dev-live` 行为。若受控重启会刷新已打开页面，须先保存草稿，再按既有锁、门控和安全点流程操作；仅更新磁盘文件不能作为该保护已经生效的证明。

编译输出先写本 worktree 的 `.dev/finance-modern-build`，成功后把完整带哈希文件写到 `frontend/assets/finance-modern`，最后原子替换 `manifest.json`。旧哈希保留供已打开标签页加载；编译失败不替换当前清单。资源及依赖不进 Git，`modern/` 下源码、测试与 `modern-build.mjs` 不对浏览器公开。旧镜像入口仅为独立分发保留，构建阶段编译资源，运行阶段不包含 Node 或现代源码。

UI 文案显式调用 `t()`。构建只保留现代页面引用的旧词条，新增短文案位于 `src/shared/messages.json`，按英语、印尼语、泰语、越南语、马来语、菲律宾语排列；缺少的词条沿用源文案回退。Filipino 标准组件文案使用 Ant Design 英语回退，主要财务导航提供 Filipino 译文。不要对 ShadowRoot 运行旧版 DOM 翻译器。

组件实现以 [Ant Design 官方全文](https://ant.design/llms-full.txt) 和锁定的 6.7.0 类型为依据。`ConfigProvider` 包住 `App`，消息与确认框使用 `App.useApp()`；表格采用稳定行键和受控分页；命名表单字段由 `Form` 管理。辅助提示支持悬停、键盘聚焦与触屏点击，重要异常直接显示。弹层使用 `open`、`destroyOnHidden`、`styles` 等当前接口，并核对表单保留语义。库升级或启用全局 Tooltip 行为前，重新验证 ShadowRoot 容器、语言及移动端操作。

按 [For Agents](https://ant.design/docs/react/for-agents-cn.md) 和[官方 antd skill](https://raw.githubusercontent.com/ant-design/ant-design-cli/main/skills/antd/SKILL.md) 使用组件知识。可通过 `npx skills add ant-design/ant-design-cli` 安装技能，并安装 `@ant-design/cli`。在 `frontend/modern` 中先执行 `antd env --format json`、`antd doctor --format json`；编写组件前运行 `antd info <组件> --version 6.7.0 --format json` 和对应 `antd demo`，不要根据旧版本记忆猜 API。修改后执行 `antd lint ./src --format json`，核对弃用、可访问性和配置提示。CLI 检查不替代业务测试及浏览器验收。

## 查询与操作约束

查询以公司和用户隔离缓存，切换身份或离开页面后取消适用的请求、计时器及图表。缺失、待补齐、未知与真实零值分别显示；服务端汇总、时区、历史业务日汇率和计算状态是权威来源。不得把当前页求和冒充全部查询结果。

保存、导入、授权和删除均不自动重发。导出任务在明确恢复时复用原 Idempotency-Key 和冻结条件；提交结果未知时先核对任务状态。授权沿用原有官方回调，脱敏回执只在当前页面内存中传递；URL 随即清除授权码、state 和提示内容。生产验收只读，实际写入测试使用隔离夹具或可销毁环境。

总览保留个人全部财务字段设置、管理员账单导入和全查询范围导出；导出窗口可选择平台退款等明细类型，冻结打开时的筛选与报告汇率快照。千易售后与平台退款是独立入口和数据范围，不相互替代。

店铺管理单独展示 Shopee 官方广告授权、采集状态和暂停/启用。状态按当前分组、店铺、平台及原币筛选，已同步日报数与商品覆盖率分开。页面可见且没有打开对话框时沿用 15 秒刷新，有删除任务时为 3 秒；隐藏、离开或对话框打开时不增加轮询。授权链接只由明确点击生成，采用原有回调；Shopee 回调标签页进入默认旧版，原新版标签页保留。授权信息在离开、切换身份或失去管理权限后关闭。

广告分析的官方 Shopee CPC 日报只展示已返回的证据，不调用仅支持导入/财务记录的来源详情接口，也不提供跨店分配。官方归属或来源待核实记录不提供分配操作；可分配的导入/未归店记录继续使用原金额及来源版本核实。CPC 与 GMS 的覆盖关系未知，不能叠加至已入账广告、利润或推算现金、赠款、净额。

完整的本地浏览器动作矩阵、预算理由、合成夹具边界与未验证项见[现代界面验收矩阵](finance-modern-acceptance.md)。任务隔离验收完成后，候选代码开放体验按钮，仍保留旧版默认；共享服务器启用以整分支评审和现场回执为准。
