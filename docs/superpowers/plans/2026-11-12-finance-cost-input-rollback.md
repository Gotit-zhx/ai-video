# 撤销人工成本安全核对版本 Implementation Plan

> **For agentic workers:** Use inline execution for this exact restoration task. Do not reimplement or extend the withdrawn feature.

**Goal:** 按用户明确指令撤销人工成本安全核对功能，恢复此前直接导入与人工修改行为。

**Architecture:** 使用 `cf76ea0` 中已验收的原实现反向撤销本分支功能差异，以新的提交保留历史；服务器按当前源码逐文件三方合并，只去掉本功能。V153 已应用，SQL、表及记录继续保留，运行守卫以当前数据库为准。

**Tech Stack:** Java 21、Spring/JDBC、Flyway、MySQL、原生 JS/JSDOM、既有源码 watcher。

**Spec:** 用户指令“把你修改的这个安全核对的版本内容撤销”；原设计与上线验收保留为撤销前的历史记录。

## Global Constraints

- 不重置、强推共享历史或覆盖其他会话文件；现有 `feature/finance-cost-reliability` 工作区干净且已隔离。
- 保留 SKU→SPU 匹配、导出、后续 Shopee 广告功能、所有业务资料、凭据、任务及迁移 V153/V154。
- 不修改已应用 SQL，不删除暂存表或迁移历史，不把未确认资料自动应用为有效价格。
- 服务器回退必须持有现有维护双锁，门控、安全点、编译成功后重载、健康验收及门控恢复。
- 无镜像/JAR 构建，无全仓同步，无生产写入测试或测试性成本导入。

### Task 1: 精确撤销源码

- [x] 核对 `git status --short --branch` 与服务器当前健康/迁移/维护状态。
- [x] 保存本次文件回退摘要，以 `git show cf76ea0:<path>` 恢复原成本 controller/workspace/workbook、前端和原测试；只移除本功能新增 helper、核对 JS 与专用测试。保留 V153 SQL。
- [x] `rg -n 'FinanceCostInput|FinanceCostValidation|FinanceCostIssues|input-changes|inputSafety' src/main/java frontend scripts/qa` 无残余引用。

### Task 2: 必要验证与服务器文件合并

- [x] 运行原成本七类单元、`FinanceCostSpuIntegrationTest`、`FinanceCostStorageIntegrationTest` 及全部剩余财务 DOM 套件。集成只用 `/tmp/finance-cost-governance-20261112/run-isolated.py` 的可销毁库。
- [x] 用部署前本功能代码为基点，把反向变化合到服务器当前文件；预期哈希不符则重新核对。首页保留后来 Shopee JS 及导航修改，未引用的本功能新文件才允许移除。

### Task 3: 受控回退与交接

- [x] 核对无其他维护后在共享锁和门控内保存本次恢复副本，等待安全点；仅替换清单文件并移除本功能编译类，编译成功才触发重载。失败保留并恢复此前可用源码/classes。
- [x] 验证健康、watcher ready、无遗留门控，数据库历史/原业务配置摘要及其他会话文件保持；正式页面核对页消失、原导入表单恢复。只读验收，不导入测试价格。
- [x] 更新常驻说明、撤销验收和原 PR 状态，按 feature 分支提交并推送；保留 V153 与全部历史回执。

维护交接：服务器源码回退与只读页面验收已完成，后续性能会话接收 9a50b21 及当前基线；本会话暂停服务器文档写入，只完成本地 Git/PR 收尾。
