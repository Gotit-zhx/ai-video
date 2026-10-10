# 采集器账号登录与视频归档

- 密码登录端点 /api/v1/extension-auth/login 使用原 AuthenticationManager，按配置默认企业或企业代码/手机号认证。密码不写本地 storage，仍签发具备采集权限的可撤销短期设备令牌。
- 浏览器自动登录使用 /extension-auth/session，仅接受对应 chrome-extension Origin 并验证正常会话。该单一路径允许带 Cookie 的扩展 CORS；其他扩展 API 继续不携带 Cookie。若浏览器 Cookie 策略阻止复用，使用已授权同源平台标签页在隔离脚本世界中完成内部短期凭据交换，无需用户复制绑定码。
- 本地/生产默认域名支持自动发现；自定义域名在弹窗授权。登录来源自动带入平台地址。退出后暂停自动识别，避免立即重新登录。
- 商品视频元数据保存于 domData.videos；后台在允许的视频 CDN 下载，流式累计限制64MiB，上传 multipart 文件至当前公司商品。服务端确认 MP4/WebM 文件头，归档到持久卷 capture-videos，V89 记录内容哈希和来源链接。无后台远程URL下载入口。
- 商品详情异步展示视频播放和下载；文件接口按公司/商品权限读取。
- 10 项后端测试通过；扩展原有检查套件与新自动登录同源/并发测试通过。实际 Shopee 视频页在测试浏览器被站点登录拦截，没有进行绕过，故真实视频采集尚待用户已登录浏览器验证。
- 新版源码 browser-extension，发行包 output/collector/heyi-collector-0.2.0.zip；浏览器需重新加载扩展才能启用新的权限和代码。

## 详情图修正

详情区图片不再因懒加载时尺寸为零而过滤；优先读取 data-srcset/data-src/data-original/data-lazy-src，避免使用占位图。描述字段中的 HTML 图片也纳入解析。新增零尺寸、占位图、去重和非法 URL 回归检查，扩展全套检查通过。仍保留现有前后端 30 张总上限；没有登录源商品页完成真实详情图端到端验证。旧采集记录需要重新采集。
