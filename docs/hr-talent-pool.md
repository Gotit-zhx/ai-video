# 招聘部门、储备人才库与招聘需求池

入口为人力资源 → 储备人才库（`#hr-pool`）。沿用现有指定平台管理员权限，不扩大账号可见范围。

## 独立的招聘部门管理

入口 `#hr-departments`，数据保存在 HR SQLite 文件的 `hr_departments` 表。与系统组织架构完全独立，不读取、复用、导入或修改原系统部门。
支持新建、编辑部门名称、编码、上级部门、经理姓名和启用状态。经理在招聘部门中直接维护，不要求关联平台用户账号。
阻止重复名称/编码、循环上下级和旧版本覆盖。变更写入 HR 操作记录。
需求使用 `departmentId` 关联这份独立目录，服务端校验并带出部门名称和经理；停用部门不能用于新需求。
已有需求的岗位资料及 AI 输入按保存时快照保留，重新编辑需求可选用最新部门信息。

## 储备人才库

- 招聘阶段：全部、邀约、一面、二面、已录取。“全部”是筛选项；新建人才可暂不设置阶段。
- 项目组：可新建、重命名；每份人才记录归属一个项目组，也可暂不分组。与阶段、关键词组合筛选。
- 支持手动新建和从人才寻源、投递列表保存已有候选人资料；姓名、职位、学历、工作年限、联系方式、城市、公司、备注可编辑。
- “邀约”仅标记内部跟进阶段，不向候选人发送消息。
- 数据保存在平台的 `/opt/heyi/boss-agent/runtime/talent-pool.sqlite3`，不依赖本机助手在线或 BOSS 登录。该文件必须纳入服务器常规备份；运行中备份使用 SQLite backup API。
- BOSS 标识重复入库会被拒绝。新建请求带 UUID，响应丢失后重试同一请求不会重复创建；已有资料使用版本号避免覆盖并发更新。
- `talent_events` 记录操作人、时间、修改前后内容；当前没有新增审计展示页。数据库权限为 0600。
- 仅保存白名单业务字段，不存 BOSS Cookie、token、securityId、简历原始返回。

接口由现有 Python HR 服务承载，前端直接调用平台 `/api/v1/hr/pool`，不会进入桌面 CDP 请求链路。

## 招聘需求池与驱动模型

入口 `#hr-demands`。字段包括需求日期、招聘岗位、归属部门、部门经理、岗位类别、需求状态、编制人数、最低学历、最低工作年限、岗位职责和任职要求。需求状态为待启动、招聘中、暂停、已完成、已取消。

平台 `/api/v1/hr/demands` 保存需求，`/save` 保存修改，`/generate` 明确发起一次后台生成。数据与人才库使用同一 SQLite 文件。

0.1.9 起，“招聘岗位”关联当前本机招聘账号下的具体职位 ID（同名职位也独立区分）。
选择后读取完整 JD；点击 AI 生成时再次经本机助手调用已固定版本 CLI 的 `hr jobs detail`，
保存账号标识、JD 原文、获取时间和摘要，再把原文与 HR 补充信息交给现有 DRIVER 接口。
读取失败、账号变化、返回职位不一致、JD 为空或过期均不启动模型调用；生成结果保留来源版本和 JD 摘要。
打开需求列表和查看已有分析不调用 BOSS 或 AI。原有自由文本需求可查看，编辑和生成前需补充职位关联。
Java `/api/v1/recruitment-ai/generate` 使用 `ModelConfigService.driverConnection(organizationId)` 选择该组织系统配置的启用驱动模型，复用模型并发配额。
仅调用该模型的纯文本接口，不提供工具、不创建自动操作任务，不调用 BOSS。密钥留在 Java 服务内，不下发前端或保存到人才库。

输出人才画像、4–8 项胜任能力、6–12 道含追问与评分标准的结构化面试题。结果保存实际模型名、生成时间及需求版本。
同一组织一次仅生成一项需求。生成中重复点击不会发起第二次调用；无自动重试或多模型兜底开销。模型请求最多等待 5 分钟；
服务重启或超过任务期限将给出失败状态，用户可手动重试，不无限转圈。修改岗位需求后保留旧结果并标记待更新。

## 发布

`scripts/boss-agent/release-talent-pool.py <release-dir>` 同时发布前端资源和服务端模块：

1. 收集当前线上三个旧前端文件及 `connector.py` 的 SHA256，写入 `expected-baseline.json`。
2. 将 `index.html`、`hr-workspace.js`、`hr-workspace.css`、`hr-pool.js`、`hr-demands.js`、`hr-departments.js` 放入 `staged/frontend/`；`connector.py`、`talent_pool.py`、`recruitment_demands.py`、`hr_departments.py` 放入 `staged/connector/`。部署目标已安装 `desktop_bridge.py`。
3. 将经过 Maven 测试的 `RecruitmentAiController.class` 放入 `staged/java/`，并将线上 app.jar SHA256 加入 `expected-baseline.json`。`manifest.json` 记录每个 staged 相对路径的 SHA256；`code-commit` 保存对应 Git 提交。
4. 脚本校验线上基线与上传文件、保留当前 Nginx 配置及下载挂载、构建持久化前端镜像。新 Java 类添加到实际运行 JAR，逐条验证其他资源字节不变。确认没有活动业务任务后更新应用和 HR 接口服务；视频 runtime、图像处理服务与本机助手保持运行，HR 保持 desktop-only。
5. 出错回滚代码和前端，不回退业务数据库，避免删除发布后写入的数据。

测试：`python3 -m unittest discover -s scripts/boss-agent -p 'test_*.py'`；
`node --test frontend/test/hr-workspace.test.cjs frontend/test/hr-platform-logout.test.cjs hr-desktop/test/transport.test.cjs`。
Java：`mvn -Dtest=RecruitmentAiControllerTest test`。
`frontend/test/hr-workspace-preview.py` 使用临时数据库，供本地 UI 验收；无生产账号或 BOSS 调用。
