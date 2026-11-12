# 财务 BI 导航恢复 — 2026-11-11

正式站点的财务 BI 菜单会在登录/会话恢复时偶发消失。`app.js` 的 `applyFeatureAccess` 早于后加载的 `FinanceWorkspace` 完成时，`FinanceWorkspace?.allowed()` 尚不存在，导致九个子入口全部被隐藏，随后父菜单也被隐藏。财务后端和已有权限并未消失。

修复仅为主导航新增同步权限判断：严格 `platformAdmin === true`、`ADMIN` 角色，或 `finance:view` / `finance:manage`。与财务工作台当前权限语义一致，不依赖脚本就绪；页面和 API 的原独立权限检查继续执行。`index.html` 仅将主脚本缓存标识改为 `20261111-finance-nav-1`。

六项新增回归在旧实现全部失败，修复后与既有商品、店铺导航共 13 项通过。覆盖模块尚未注册、模块返回旧状态、不同财务角色、无权限/未登录，以及权限变化。JS 语法、静态资源引用和差异检查通过；独立只读审查无 Critical/Important。

## 实际生效

维护操作 `163f688ea0514b179c7b9960641c4da5` 在共享双锁内执行，先核对源码运行 ready、无门控/未完成维护、容器身份及两文件原摘要。直接原子更新 `frontend/app.js`，再在现场 `index.html` 精确替换一个主脚本缓存引用，没有整体上传本地 index；历史汇率资源、dev-live 和其他现场改动均保留。

- app.js SHA-256：`b717721483b267c0baac0127746f9b1237b8af3745bc17b7c5c69293494dd111`。
- 现场 index SHA-256：`1197222cf9b872bc619c9ddb1b1299acefa12faf304711805f489c26f0bb0f1d`。
- 主机回执：`/opt/heyi/dev/operations/finance-navigation-163f688ea0514b179c7b9960641c4da5/verified.json`。

真实 HTTPS 解压后文件与目标摘要一致，健康返回 200、匿名财务查询保持 401。后端和前端容器均未重建，数据库结构及源码上下文保持，门控关闭。未执行数据库写入、暂停同步、业务修复或备份操作，未发布仍独立保留的 Shopee 候选和 V153。

默认 Edge 的既有授权登录实际展开财务 BI 后，九个子菜单均出现；进入财务总览可读取已有数据，再次刷新菜单及页面仍正常。截图仅保留在用户本地验收目录，不将真实金额、账户或业务截图放入 Git。
