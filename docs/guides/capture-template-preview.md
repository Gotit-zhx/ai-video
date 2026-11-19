# 采集模板

入口：商品运营 v2.0 → 采集模板，路由 `/#product-capture-templates`。旧 `/capture-template-upload.html` 自动跳转到该路由。当前已接入后端与私有 OSS，不再使用 IndexedDB 保存新模板。

## 数据与权限

- 模板名称、说明、款式、款式编码、颜色及其编码和版本保存在 MySQL。模板以企业为边界共享，客户端不能指定企业；后端从当前会话取企业编号。
- 图片复用 `CommerceImageArchive` 的私有 OSS 归档，按企业哈希与文件 SHA-256 隔离、去重。数据库只存归档引用，不存 Base64。
- 图片通过带登录和企业校验的同源接口读取，避免向客户端暴露存储凭据或接收任意外部 URL。
- 查询需要 `product:view`、`product:edit`、`product:publish` 或 `product:collect` 之一；写模板和上传图片需要 `product:edit`。无会话返回 401，无写权限返回 403，跨企业模板或图片返回 404。
- 每个模板仅一个款式、一个款式图片，可配置 1–30 个颜色。款式编码沿用原系统规则：一个英文字母加两位数字（例如 A01）。颜色编码为 1–16 位英文字母或数字（例如 BK、WH、001），同一款式内不能重复。编码去除首尾空格并转大写，保留数字前导零。图片支持 PNG/JPEG/WebP，最大 8 MiB，至少 128×128，最多 4000 万像素。前后端均校验，服务端按实际内容解码，不信任扩展名或声明 MIME。
- 保存携带版本号；冲突返回 409，不覆盖更新的内容。相同编号、相同内容的保存重试和相同图片上传可以安全重试。
- OSS 不可用时上传/读取失败，不回退成本地服务器存储或假装本机保存成功。

## 页面行为

首次打开为空白表单，只保留一个“保存”主按钮。已有企业模板在左侧读取；不提供添加或切换版式。款式名称和款式编码并排填写，每行颜色填写名称、编码和 HEX 色值，保留取色器。一次上传仅接受一张图片，多文件拖入会提示重新选择。上传即时写入 OSS，保存再关联模板。只读和未登录状态禁用写操作。

旧预览版本的浏览器本地数据不自动上传、不删除，也不跨企业导入。新版本的跨设备共享依赖登录同一企业及相应权限。

当前功能只管理模板配置和图片。款式编码和颜色编码为后续 SKU 生成提供数据，尚未接入原有采集或发布的 SKU 生成；不改变既有 SKU。字段保存在原 content_json 中，不新增数据库迁移。旧 JSON 缺少编码仍可读取，修改保存时须补齐。

## 接口

统一响应结构为 `ApiResponse`；图片响应为带 `Cache-Control: no-store` 的图片字节。

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/api/v1/product-capture-templates?offset=0&limit=50` | 分页读取当前企业模板，返回 items/total/offset/limit，limit 上限 100 |
| GET | `/api/v1/product-capture-templates/{id}` | 读取模板和版本 |
| PUT | `/api/v1/product-capture-templates/{id}` | 新增或更新；新模板 version=0，更新传当前版本 |
| POST | `/api/v1/product-capture-templates/images` | multipart 字段 file；上传 OSS 后返回图片 id、同源读取地址、文件名、尺寸 |
| GET | `/api/v1/product-capture-templates/images/{id}` | 校验权限与企业后读取 OSS 图片 |

保存体：

```json
{
  "version": 0,
  "name": "基础圆领 T 恤",
  "description": "",
  "variants": [{
    "id": "22222222-2222-2222-2222-222222222222",
    "name": "圆领短袖",
    "styleCode": "A01",
    "imageId": "11111111-1111-1111-1111-111111111111",
    "colors": [{"id":"33333333-3333-3333-3333-333333333333","name":"本白","hex":"#FFFFFF","colorCode":"WH"}]
  }]
}
```

所有 id 使用 UUID。`imageId` 必须来自当前企业成功上传的图片；服务端不接受 Base64、任意图片 URL 或存储 key 作为替代。

## 代码与迁移

- `product/application/ProductCaptureTemplateService.java`：校验、企业隔离、数据库保存、版本冲突、OSS 归档。
- `product/web/ProductCaptureTemplateController.java`：REST 接口；`SecurityConfig` 统一鉴权。
- `V160__product_capture_templates.sql`：仅新增 `product_capture_templates` 和 `product_capture_template_images`。
- `frontend/product-capture-templates.js`：单一业务模块，复用主系统 `api()`、`currentUser`、权限函数与模块生命周期；`product-capture-templates.css` 只作用于模块根节点。
- 页面结构在主 `index.html` 的 `data-commerce-panel="product-capture-templates"` 内，路由由既有 `commerceSections` 管理；不创建第二套应用、侧栏、顶部栏或登录流程。

## 验收与上线

2026-11-15（America/Los_Angeles）：

- Java 定向测试 8 项通过，0 跳过：输入限制、伪造图片拒绝、OSS 缺失、未登录/只读、企业隔离、版本冲突、重复上传、多个版式、分页、Controller 图片读取等。
- MySQL 测试只连接随机命名的可销毁 `capture_template_qa_*` 库和限定该库权限的测试账号；完成后均已删除。
- 用随机测试企业实测当前私有 OSS 的上传、读取与字节一致性；自建测试图片已删除。未使用真实商品数据。
- 独立传输层已合并至原系统请求函数；原系统财务导航、主图模板与静态资源相关的 12 项回归检查通过。
- 浏览器配合隔离 API 夹具实测：两张版式上传、独立颜色、保存、刷新读取、版式切换、只读禁写、未登录提示。390px 窄屏无水平溢出。这是前端接口对接测试，不能替代真实登录用户的生产端到端验收。
- 复用既有维护锁、门控、安全点、编译和受控重载协议上线；V160 仅执行一次，已有 Flyway 历史前缀保持，旧运行版本校验通过。watcher 已恢复 active，应用 health UP，维护门控 false。
- 线上前端四个文件经 en0 直连 HTTPS 校验内容哈希一致；模板列表、图片与保存接口未登录均返回 401。尚未使用真实用户登录完成生产保存验收。
- 线上源码、配置 CAS、迁移、类备份与上线回执位于 `/opt/heyi/dev/operations/capture-template-backend-20261115-0250/activate/`，`verified.json` 标记 activated。

服务器和浏览器验收继续遵守本次对话约束：该服务器通过 en0 绑定通道访问，不修改其他网站或全局网络设置。


## 原系统集成验收

2026-11-15（America/Los_Angeles）：按用户反馈移除独立页面外壳，统一进入原系统 hash 路由、侧栏、顶部工具栏、主题和登录态。旧独立 API、编辑器 JS 与全局 CSS 三个资源已移除，仅新增一个按原系统事件运行的业务脚本。默认仍为空白首个表单，并保留唯一保存主按钮。

本次浏览器隔离夹具验证：主导航与顶部栏各只有一个；旧地址自动进入新路由；仅一次主系统 auth/me 请求；上传、保存、切换主图风格模板再返回保持同一文档及已编辑状态；系统日间/夜间主题作用到模块；公司重置清空原企业数据并禁用保存。样式检测未发现问题。未使用真实企业数据做本轮 UI 测试。

此次部署只涉及前端，不改后端接口、数据库或 OSS 行为，无后端重启。五个上线文件经 en0 直连 HTTPS 校验哈希一致，应用健康 UP。现场 CAS、原文件备份及回执：`/opt/heyi/dev/operations/capture-template-native-20261115/`。

## 单款式与编码配置验收

2026-11-15（America/Los_Angeles）：每个模板仅配置一个款式，移除添加/切换版式。新增 `variants[0].styleCode` 与 `colors[].colorCode`；编码按上述规则校验、统一大写并随模板 JSON 入库，不修改已应用迁移，不接入现有 SKU 生成器。

- Java 定向测试 12 项通过、0 失败、0 跳过：编码规范化、前导零保留、缺失/非法/重复编码拒绝、单款式限制、旧 JSON 兼容、MySQL 保存和编辑回读、版本冲突、权限隔离、真实 OSS 往返等。仅使用可销毁 QA 库与自建 OSS 图片；库、限定用户、私有凭据及测试 OSS 对象已清理。
- 原系统财务导航与主图模板 12 项回归检查通过。隔离浏览器夹具确认：单保存按钮、无添加版式、编码保存后刷新重读、重复编码提示、多文件上传拒绝、日夜主题、390px 无水平溢出；样式检测无发现。
- 通过已有 watcher 的维护锁、门控、安全点、源码编译及受控重载上线；未手动重启容器，未构建 JAR/镜像。应用健康 UP、watcher ready、维护门控 false。三个前端文件经 en0 绑定 HTTPS 校验哈希一致，未登录接口拒绝访问。
- 本轮不使用真实用户数据做生产保存，生产账号端到端验收仍未执行。现场原文件备份、基线 CAS、QA 与上线回执：`/opt/heyi/dev/operations/capture-template-codes-20261115/`。无进行中的维护或数据库变更。

## 导航归属调整

2026-11-15：采集模板移至一级目录“商品运营 v2.0”下，原“商品运营”目录移除该入口。路由和模板 API 保持，权限继续沿用原系统。浏览器验证入口唯一、选中及展开正确、往返原采集页面正常，财务导航与主图模板 12 项回归通过。仅更新前端源码与本说明，无后端重载或数据操作；现场 CAS 和回执位于 `/opt/heyi/dev/operations/capture-template-v2-navigation-20261115/`。

## Git 分支归档

2026-11-18（America/Los_Angeles）：从已 fetch 的最新 `origin/develop`（`51336128256be4204d7ec4f7ccbd69790b6c2813`）创建用户指定分支 `byh-运营`，归档采集模板前后端、OSS 图片引用、单款式及颜色编码、商品运营 v2.0 导航和界面文案准则。使用独立 worktree，原开发工作区及未提交内容保持原状。

分支归档检查：变更文件内容核对、敏感值扫描、Git 空白检查、两个 JavaScript 语法检查、财务导航与主图模板 12 项回归均通过。Java/MySQL/真实 OSS 沿用上文已完成的定向验收，本次未重新执行。仅进行 Git 操作，无共享服务器同步、重载或数据变更。
