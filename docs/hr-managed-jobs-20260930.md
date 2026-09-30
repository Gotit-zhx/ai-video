# 正式职位列表同步

现场确认：BOSS 官方职位管理页有“总经理秘书”（开放中）、“图像算法”（开放中、尚无沟通）、“数字IC验证工程师”（已失效）。平台只显示秘书职位。

原因：锁定的 `boss-agent-cli 2.0.0` 的 `hr jobs list` 使用 `/wapi/zpjob/job/chatted/jobList`，范围是已沟通过的职位，不能作为完整的职位管理列表。

修复：

- 助手 0.1.14 的职位列表使用官方职位管理页面正在使用的只读 `GET /wapi/zpjob/job/data/list`，按 `hasMore` 顺序翻页，按 `encryptJobId` 去重。
- 参数及字段来源：官方 `job-v2/v5768/assets/index-Jsl3Sgnf.js` 和 `index-BQvxdP07.js`。`type=0` 表示全部；页码从 1 开始；返回数据位于 `zpData.data`。
- 继续通过已有 SDK 的 `_request`，保留网卡直连、会话一致性、共享预算、限频、风控停机和禁止自动重试。
- 新路径不会获得“恢复登录探测”的特殊授权。原来登录验证使用的只读探测和职位详情路径不变。
- 显示官方字段对应的开放、审核、关闭和失效状态。新职位不会自动开启被动邀约。
- 职位页新增刷新入口，读取中禁止重复提交；失败保留上次列表，不用空列表覆盖。
- Windows 共用同一份跨平台源码，构建流程加入完整职位列表回归测试；安装包仍需经过 Windows 原生构建和打包后测试才发布。

验证：本机已升级 0.1.14，保留原有账号目录和 Cookie。2026-09-30 17:45，通过线上职位管理页面发起一次读取，审计显示新接口 HTTP 200、平台 code 0，页面正确显示上述 3 个职位及开放/失效状态。没有发送候选人消息，也没有执行 AI 寻源。

离线验证：职位列表 5 项 Python 测试、现有请求保护 30 项测试、桌面及前端相关测试通过；Mac 安装包签名校验和隔离账号目录的打包后自检通过。

发布记录：

- 代码提交 `320e902271facc4d810d684d65a27621bdbf319d` 已推送测试分支和桌面分支。
- 线上只替换 `index.html`、`hr-workspace.js`，部署记录在 `/opt/heyi/releases/hr-managed-jobs-20260930/activation.json`。业务容器 ID 和挂载保持不变，公开静态文件哈希、未认证接口拒绝检查通过。
- Windows 构建标签 `hr-desktop-build-windows-v0.1.14-320e902` 对应 Actions run `36698286369`。2026-09-30 的构建在任何步骤运行前被 GitHub 拦截，原文为 “The job was not started because recent account payments have failed or your spending limit needs to be increased.” 没有生成 Windows 0.1.14 安装包，现有公开 Windows 包仍为 0.1.11。仓库没有自托管 runner。
- 后续只读检查发现：17:47:04 的聊天记录操作被本机会话核验以 `SESSION_MISMATCH` 拦截，无 HTTP 返回；BOSS 浏览器已显示未登录首页。保留暂停记录，未删除 Cookie、绕过会话校验或自动重新登录。现有证据不能确定退出登录的原因，也不能将先前职位读取成功解释为会话已长期稳定。
