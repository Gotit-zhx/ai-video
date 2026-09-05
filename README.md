# 赫逸智能化 AI 平台

面向 AI 内容生产与电商运营的企业级工作流平台。当前基础版本包含：

- 赫逸智能化品牌登录页、Logo、公司简介与运营工作台
- Spring Boot 4.1、Java 21、MyBatis-Plus
- MySQL 8.4、Flyway 数据库版本管理
- Redis 缓存基础设施
- SaaS 企业、部门、员工、标准 RBAC 角色权限和数据范围模型
- 数据库员工账号登录与账号启停
- 三字段模型接入配置与 API Key 加密存储
- 工作流任务创建、查询、分页与状态流转 API
- Redis Stream 工作流调度、持久化执行记录与 SSE 进度事件
- 单账号单会话、单用户单任务以及跨用户全局并发控制
- Nginx 前端及 API 反向代理
- Actuator 健康检查
- 全 Docker Compose 本地运行环境
- 独立的 Windows/macOS“赫逸自动化助手”桌面执行程序

## 启动

宿主机只需安装 Docker Desktop，无需单独安装 Java、Maven、MySQL 或 Redis。

首次使用可复制环境变量模板并修改其中的密码：

```powershell
Copy-Item .env.example .env
docker compose up --build -d
```

访问地址：

- 前端入口：http://localhost:3000
- 后端健康检查：http://localhost:8080/actuator/health

如果没有创建 `.env`，本地开发登录信息为：

- 企业编码：`heyi`
- 管理员手机号：`13800000000`
- 管理员密码：`admin123`

默认值只用于本机开发，上线前必须修改。

常用命令：

```powershell
docker compose ps
docker compose logs -f app
docker compose down
```

## 赫逸自动化助手

本机自动化能力位于 `automation-agent`，它是独立桌面程序，不运行在 Docker
容器中。当前版本可以检测平台、飞跨客户端和本机店铺环境，具备接单开关、托盘
常驻、本地配置以及飞跨动态 CDP 生命周期适配器。

Windows 安装包构建：

```powershell
cd automation-agent
npm install
npm run icons
npm run dist:win
```

详细说明见 `automation-agent/README.md`。

## SaaS 人员配置

登录后进入左侧“系统管理”，可以：

- 维护企业名称、简介、联系人和品牌 Logo
- 建立多级部门
- 创建员工账号并绑定部门
- 为员工分配一个或多个角色
- 创建企业自定义角色并配置菜单、接口和操作权限
- 配置全部、本部门、本部门及下级、仅本人四种数据范围
- 编辑员工资料、重置密码、启用、锁定或停用账号

权限模型采用标准 RBAC：

```text
用户（User） ⇄ 角色（Role） ⇄ 权限（Permission）
                           └─ 数据范围（Data Scope）
```

部门是独立组织树，不作为字典或角色使用。角色负责组合权限，权限码用于后端
API 鉴权和前端菜单/操作入口控制。

员工使用手机号作为登录账号，前端自动组合为 `企业编码/手机号`；所有组织数据均按当前企业隔离。

## 模型接入

管理员可从左侧“模型接入”直接配置第三方模型，只需填写：

- API 地址
- 模型 ID
- API Key

模型按用途分为驱动模型、视频模型和图片模型，每类独立维护默认连接。
从对应分类点击添加后，系统会自动记录类型，不要求运营人员额外选择。

首个连接会自动成为默认模型。API Key 使用 AES-256-GCM 加密后存入 MySQL，
接口和页面只返回 Key 尾号；编辑连接时 Key 留空即可保留原值。协议适配与平台
内置执行能力不向普通用户暴露。

生产环境必须在 `.env` 中将 `APP_MODEL_SECRET_KEY` 设置为至少 32 位的随机值，
并妥善备份；变更该值会导致已有模型密钥无法解密。

## Shopee / TikTok Shop 官方店铺授权与发布

商品采集插件只负责读取商品详情页，店铺授权和发布始终由服务端调用官方 Open API。
店铺 `access_token`、`refresh_token` 使用独立 AES-256-GCM 密钥加密，并绑定企业与店铺
作为附加认证数据；网页和插件只能看到店铺名称、地区及授权状态。

先在 Shopee Open Platform、TikTok Shop Partner Center 创建并审核应用，把 OAuth 回调
分别登记为：

```text
https://api.example.com/api/v1/shop-authorizations/callback/shopee
https://api.example.com/api/v1/shop-authorizations/callback/tiktok_shop
```

然后在 `.env` 配置：

```text
APP_SHOP_CREDENTIAL_KEY=<独立的至少 32 位随机值>
APP_SESSION_COOKIE_SECURE=true
APP_SHOP_AUTH_CALLBACK_BASE_URL=https://api.example.com
APP_SHOP_AUTH_FRONTEND_BASE_URL=https://app.example.com
APP_SHOPEE_ENABLED=true
APP_SHOPEE_PARTNER_ID=<Partner ID>
APP_SHOPEE_PARTNER_KEY=<Partner Key>
APP_SHOPEE_AUTH_BASE_URL=https://open.shopee.com
APP_SHOPEE_API_BASE_URL=https://partner.shopeemobile.com
APP_TIKTOK_SHOP_ENABLED=true
APP_TIKTOK_SHOP_SERVICE_ID=<Service ID>
APP_TIKTOK_SHOP_APP_KEY=<App Key>
APP_TIKTOK_SHOP_APP_SECRET=<App Secret>
APP_TIKTOK_SHOP_AUTHORIZATION_BASE_URL=https://services.tiktokshop.com
APP_TIKTOK_SHOP_TOKEN_BASE_URL=https://auth.tiktok-shops.com
APP_TIKTOK_SHOP_API_BASE_URL=https://open-api.tiktokglobalshop.com
```

临时使用 cpolar 联调回调时，先确认管理员密码、模型密钥、店铺凭证密钥及内部 Agent/Runtime
Token 均已替换为独立随机值，再运行 `scripts/start-cpolar-secure.sh`。脚本会在回环地址启动
callback 白名单网关，只允许上述两个 OAuth GET 回调通过 HTTPS 隧道，不会把登录页、后台 API
或静态站点整体暴露到公网；同时关闭 cpolar Dashboard、inspection 与落盘日志。按脚本输出设置
`APP_SHOP_AUTH_CALLBACK_BASE_URL`，单机联调可让 `APP_SHOP_AUTH_FRONTEND_BASE_URL` 保持为
`http://localhost:3000`，授权完成后浏览器会回到本机页面。免费域名重启后可能变化，每次都要同步
更新 Shopee/TikTok 开发者后台登记值。本地 `http://localhost:3000` 不经过代理协议头，不会被
HTTPS 重定向。

默认始终是 `DRY_RUN`。只有联调完成、图片去水印提供方不再是 Dry Run，并确认应用已取得
商品与物流权限后，才同时设置以下两个变量启用真实官方发布：

```text
APP_PRODUCT_PUBLISHING_PROVIDER=OFFICIAL
APP_SHOP_LIVE_PUBLISHING_ENABLED=true
```

两个开关缺少任意一个都不会向店铺提交商品。库存不依赖采集结果，而是在最后发布确认时
由运营人员填写；官方叶子类目、TikTok 仓库与多规格属性映射也必须以目标店铺返回的数据为准。

实施细节、最新版接口路径、Sandbox 阻断清单和 GitHub 项目对比见
[`docs/official-shop-integration.md`](docs/official-shop-integration.md)；插件安装、登录配对、
采集边界和测试清单见 [`browser-extension/README.md`](browser-extension/README.md)。

## Pi Agent 与助手联调

左侧“助手测试”是临时联调入口，链路为：

```text
驱动模型 -> Pi Agent Runtime -> 平台命令队列 -> 赫逸自动化助手 -> 飞跨
```

当前驱动模型按 OpenAI Chat Completions 兼容协议接入，因此普通用户只需填写
API 地址、模型 ID 和 API Key。若厂商只支持 Responses、Anthropic Messages 或
Google Generative AI 协议，后续需要在高级配置中增加协议选择，不能只靠这三个字段推断。

当前开放 `FEIKUA_INSPECT` 环境检查和 `FEIKUA_OPEN_STORE` 精确店铺启动命令。
启动命令必须提供完整店铺名称，桌面助手会精确匹配、获取动态 CDP 端口并通过飞跨自身
入口打开店铺；不会模糊匹配。页面点击、发布、删除与付款仍未开放。
生产环境必须在 `.env` 中同时设置独立随机值：

```text
APP_AUTOMATION_AGENT_TOKEN
APP_PI_RUNTIME_TOKEN
```

联调接口：

```text
GET  /api/v1/assistant-test/status
POST /api/v1/assistant-test/chat
GET  /api/v1/assistant-test/commands
POST /api/v1/automation-agent/heartbeat
GET  /api/v1/automation-agent/{agentId}/commands/next
POST /api/v1/automation-agent/{agentId}/commands/{commandId}/complete
```

主要接口：

```text
GET  /api/v1/auth/me
GET  /api/v1/system/organization
PUT  /api/v1/system/organization
GET  /api/v1/system/departments
POST /api/v1/system/departments
PUT  /api/v1/system/departments/{departmentId}
DELETE /api/v1/system/departments/{departmentId}
GET  /api/v1/system/roles
POST /api/v1/system/roles
PUT  /api/v1/system/roles/{roleId}
GET  /api/v1/system/permissions
GET  /api/v1/system/users
POST /api/v1/system/users
PUT  /api/v1/system/users/{userId}
POST /api/v1/system/users/{userId}/reset-password
PATCH /api/v1/system/users/status
GET  /api/v1/system/model-configs
POST /api/v1/system/model-configs
PUT  /api/v1/system/model-configs/{configId}
PATCH /api/v1/system/model-configs/{configId}/status
PATCH /api/v1/system/model-configs/{configId}/default
```

## 工作流任务 API

除登录和健康检查外，API 使用 Redis Session 身份认证。同一账号再次登录会使
此前会话失效；同一用户有活动任务时，平台拒绝其创建第二条任务。

创建图生视频任务：

```http
POST /api/v1/workflow-jobs
Authorization: Basic <credentials>
Content-Type: application/json

{
  "type": "IMAGE_TO_VIDEO",
  "title": "夏季商品宣传视频",
  "inputPayload": {
    "script": "展示商品的三个核心卖点",
    "aspectRatio": "9:16"
  },
  "budgetLimit": 50.00
}
```

查询和更新：

```text
GET /api/v1/workflow-jobs/{id}
GET /api/v1/workflow-jobs?status=DRAFT&page=0&size=20
PATCH /api/v1/workflow-jobs/{id}/status
GET /api/v1/workflow-jobs/{id}/events
GET /api/v1/tasks/current
```

任务进入 `QUEUED` 后由 Redis Stream 消费者调度。尚未接入对应内置 Skill 时，
任务会安全停在 `WAITING_EXECUTOR`，不会伪造成功结果。执行器实现
`WorkflowSkillExecutor` 后即可按任务类型自动被调度器发现。

默认容量参数可通过 `.env` 覆盖：

```text
APP_DISPATCH_WORKER_THREADS=8
APP_MAX_ACTIVE_TASKS=80
APP_MAX_MODEL_CALLS=20
```

其中活动任务限制作用于所有平台实例，模型调用限制同时覆盖助手调用和后续
工作流执行器中的模型调用。

## 数据库表

- `organizations`：SaaS 企业租户
- `departments`：企业部门树
- `app_users`：企业员工账号
- `roles`、`permissions`：角色与权限
- `user_roles`：员工角色关系
- `user_shop_scopes`：员工店铺数据范围
- `login_logs`、`audit_logs`：登录与业务审计
- `shops`：平台店铺与授权引用
- `products`、`product_skus`：商品主数据
- `assets`：图片、视频、音频等素材
- `workflow_jobs`、`workflow_steps`：工作流与 Skill 执行步骤
- `workflow_runs`：每次调度尝试、执行节点、错误与完成时间
- `workflow_job_events`：任务状态事件与前端实时进度来源
- `active_user_tasks`：每个用户唯一的活动任务锁
- `model_call_records`：模型调用和成本
- `publish_tasks`：内容、商品和促销发布任务

所有数据库结构变更都应通过 `src/main/resources/db/migration` 下的 Flyway 脚本完成。
