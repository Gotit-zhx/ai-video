# 本地启动与授权回调恢复

2026-09-15 已启动 Docker Desktop，现有前端、后端、MySQL、Redis、模型 runtime 和去水印服务恢复运行。前端 http://localhost:3000/ 返回 200，后端健康状态 UP。

本次公网回调：

- Shopee：`https://477d1f52.r7.cpolar.cn/api/v1/shop-authorizations/callback/shopee`
- TikTok Shop：`https://477d1f52.r7.cpolar.cn/api/v1/shop-authorizations/callback/tiktok_shop`

两个公网 GET 回调均通过无凭据连通性检查，返回 302 到本地错误提示（未提供 state，符合预期）。公网首页和健康接口返回 404；仅开放上述回调。没有发起真实授权或修改店铺数据。

原临时目录已不包含 Compose 和环境文件。从现有容器配置恢复了本地 `.env`（0600，Git 忽略），保留现有凭据。后端只更新回调根域，并保留全部现有环境设置及额外域名映射；未重新编译或更换运行 JAR。完整环境覆盖保存在用户私有配置目录，不写入文档。

隧道使用 `scripts/start-cpolar-secure.sh` 的独立后台进程。启动器修复日志延迟，支持可选 `CPOLAR_SUBDOMAIN`，退出时同时清理隧道和回调网关；日志只输出规范化域名和固定错误类别，不保存 OAuth 查询。

旧随机域名 ffd45c8.r7.cpolar.cn 已失效；实测指定旧子域名被当前套餐拒绝，未购买套餐。新地址未保留为固定域名，后续以实际分配结果为准。

平台登记已完成：用户在内嵌浏览器登录后，TikTok Heyi AI 应用的重定向链接已保存为上面的完整回调，刷新后读回一致；Shopee 中国站 HOYEE Commerce（应用 241309，测试 Partner ID 1243570）的 Test Redirect URL Domain 已保存为 `https://477d1f52.r7.cpolar.cn`，提交后的详情页读回一致。TikTok 中途发生 Network Error，恢复后已确认保存结果。未发起真实店铺授权或重置任何凭据。隔离浏览器空间 166 已关闭，用户的两个配置页面保留。

验证记录：[verification.json](../output/startup-cpolar-20260915/verification.json)。
