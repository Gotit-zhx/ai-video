# 一面资料交接与钉钉设置

储备人才库“邀约”阶段使用“开始一面”。具备完整配置时，HR 点击后直接提交；缺少配置时显示缺项，不发送。简历、岗位 JD、含追问和评分标准的结构化面试题库打成一份 ZIP 文件发送给企业指定员工，解压后的 HTML 题库可浏览和打印。

只使用该候选人审批通过的简历，并按招聘账号 + 具体职位 ID 关联招聘需求，不按岗位名称猜测。多项同职位需求需由 HR 选择。题库必须来自当前版本需求的已完成分析，不自动付费生成。

## 管理员配置步骤

1. 企业钉钉管理员进入 [开发者后台](https://open-dev.dingtalk.com/)，创建企业内部应用，命名例如“赫逸招聘助手”。此接入使用应用给员工发送个人工作通知，群机器人 Webhook 不适用。
2. 在“凭证与基础信息”取得 Client ID / AppKey、Client Secret / AppSecret、AgentId。
3. 开通“成员信息读权限”，通讯录范围覆盖面试负责人；工作通知、媒体上传权限通常默认开通。核对发布状态和应用可使用范围，包含收件负责人。
4. 如果启用服务器出口 IP 白名单，使用实际服务器出口；当前部署服务器地址为 `8.163.34.81`。
5. 获取该企业内负责人的 UserID。员工详情如不展示，可由管理员用 API Explorer 的“根据手机号查询用户”获取，仅用于确定 UserID；本系统不保存或批量读取钉钉手机号。
6. 进入“储备人才库 → 钉钉设置”，填写三项应用信息并“保存并校验应用”。该步骤只校验凭证，不发测试消息，也不验证 AgentId 对应关系，需管理员核对。
7. 在同一设置窗选择对应招聘需求、填写负责人 UserID，“校验并绑定负责人”。系统读取这个员工的姓名，显示姓名供 HR 核对。
8. 招聘需求池中关联准确职位并生成当前版本面试题库。回到邀约卡片点击“开始一面”，即可交接资料。

应用密钥在服务器使用 Fernet 加密存储，独立 0600 密钥文件为 `/opt/heyi/boss-agent/runtime/private/dingtalk.key`；备份数据库时也需独立安全备份该文件。接口只返回 `hasSecret`，不回传密钥，配置审计不含凭证。更换应用会使既有负责人映射失效，需重新校验绑定。密钥不应发在聊天或进入 Git。

## 回执、去重和恢复

一次一面交接仅发送一条文件工作通知，避免两份资料部分送达。资料包最大 20MB，超限在发送前失败。点击、HTTP 重试和页面刷新通过人才唯一交接记录与请求 UUID 去重；已送达或结果不明的记录不可重发。明确失败后仅由 HR 点击“手动重试”，并重新核对最新配置与岗位资料。

进度为准备、发送、钉钉已接受、已送达。`asyncsend_v2` 返回 task_id 只说明已接受；`getsendresult` 的目标员工出现在 read/unread 列表才确认送达，之后将“邀约”推进为“一面”。若 HR 在发送期间自行修改人才阶段/版本，保留其操作，送达审计仍记录。发送失败或结果不明不推进阶段。后台持续查询已知任务回执，即使关闭弹窗也可更新；不自动重发。

重启时未开始发送的准备记录标为可手动重试，已开始但未得到任务 ID 的标为待核对。已有 task_id 继续只读查询；超过回执查询期限标为待核对。真实姓名、收件范围、权限或限额异常需管理员处理。

本功能不调用 BOSS，不发送候选人聊天、不触碰 BOSS Cookie 或助手会话。

## 官方接口依据（2026-10-08 核对）

- [企业内部应用 accessToken](https://open.dingtalk.com/document/orgapp/obtain-the-access_token-of-an-internal-app)：新版 POST `api.dingtalk.com/v1.0/oauth2/accessToken`，按应用缓存 token。
- [查询用户详情](https://open.dingtalk.com/document/orgapp/query-user-details)：`topapi/v2/user/get`，成员信息读权限；只保留核对后的姓名与 UserID。
- [上传媒体文件](https://open.dingtalk.com/document/orgapp/upload-media-files)：multipart `media/upload`，ZIP 文件最大 20MB。
- [发送工作通知](https://open.dingtalk.com/document/orgapp/asynchronous-sending-of-enterprise-session-messages)：`asyncsend_v2`，只指定单个 userid_list，to_all_user=false。接口成功仍可能因重复或限额无法送达。
- [获取发送结果](https://open.dingtalk.com/document/orgapp/gets-the-result-of-sending-messages-asynchronously-to-the-enterprise)：仅支持 24 小时内任务，本实现 23 小时后转为人工核对。

验证使用合成简历及模拟钉钉返回，覆盖精确职位关联、加密与审计、单次发送、未知结果、明确失败重试、重启、资料包完整性和发送期间配置变化。未向真实负责人发送测试消息。

发布目录：`/opt/heyi/releases/hr-dingtalk-interviews-20261008`。前端文件与连接器逐项校验后发布，应用、模型和图片容器保持原实例。70 项前端检查、12 项钉钉交接检查，以及 122 项连接器/人才库/简历池/招聘需求回归检查通过（共 204 项）。生产连接器 active，主应用健康 UP，临时上传包已删除。

线上只读验证：卡片已显示“开始一面”，钉钉设置正常读取，两项招聘需求可选择。截图候选人的原简历本地可用且 SHA-256 与入库记录一致，关联的图像算法需求已有 7 道有效面试题；当前缺应用凭证及负责人绑定。验证后交接记录数仍为 0，未上传到钉钉或发送消息。真实凭证的授权范围、AgentId 与通知送达仍需管理员完成配置后验证，不能将模拟检查视为真实送达测试。
