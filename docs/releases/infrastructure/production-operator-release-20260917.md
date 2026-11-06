# 测试版本发布到正式环境：角色页面与运营权限

> 历史发布记录；镜像、测试与“当前/最新”均指记录当时。 当前入口见[业务说明](../../business/README.md)与[运维指南](../../guides/operations.md)。

正式环境 https://hoyee.org.cn 已部署代码提交 `9d29c9a`，基于测试分支 `codex/test-current-20260831` 的完整代码快照 `d065e29`。

- 角色与权限页面：修复角色条目、四列概要、权限分组和列表排版，支持窄屏换列。
- 内置 OPERATOR 角色：仅授予 video:monitor:view、video:script:view、video:script:manage、video:task:view、video:task:manage。通用生成复用视频任务权限。
- V102 同步存量角色，企业创建和启动种子保持相同规则；其他角色权限不变。
- 旧运营登录会话已清理，需重新登录。具有额外角色的人员仍按多角色权限并集授权。

## 发布验证

- 视频、认证、企业管理相关测试 299 项，0 失败，7 项跳过。
- 真实浏览器检查角色布局无横向溢出，运营只显示视频工作台，通用生成入口及提交按钮可见。
- 正式库原 V1–V43 文件名及 Flyway checksum 全部匹配；生产数据副本完成 V44–V102 升级和角色授权验证后，才执行正式升级。
- 发布前完整数据库备份保存在服务器发布目录的 private 下；仅切换 app/frontend/pi-runtime 镜像，原 Compose 环境变量和挂载保留。
- 三个服务健康检查通过，正式 JAR、app.js、resend-theme.css 与发布源文件一致。
- 正式临时运营账号验证：视频脚本、AI 视频任务、通用生成 GET 返回 200；人员、模型配置、商品采集、自动化返回 403；通用生成 multipart POST 能到达业务参数校验，返回预期 400，没有提交付费生成。临时账号及会话已删除。
- 单独核对 OSS Bucket、region、内部/公网 endpoint、签名凭证文件路径、签名/直链有效期、public-images、public base URL、reference link TTL：全部与发布前一致；全部原有挂载保持一致。
- 全量历史测试的既有失败仍参见 d065e29 提交说明；本次未宣称其他模块全量测试通过。

服务器发布目录：`/opt/heyi/deployments/test-to-prod-20260917`。
镜像：`ai-commerce-platform:test-release-20260917`、`heyi-ai-frontend:test-release-20260917`、`heyi-pi-runtime:test-release-20260917`。
