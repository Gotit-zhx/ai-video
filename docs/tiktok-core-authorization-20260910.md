# TikTok Shop 核心账号授权接入（进行中）

## 当前目标

按用户最新指示测试马来西亚（MY）核心账号。先前菲律宾授权页在安装后两次显示 Connection Failed，尚未取得授权成功凭据；未向任何真实店铺上传商品。

## 已核实的官方规则

来源：
- https://partner.tiktokshop.com/docv2/page/authorization-overview-202407
- https://partner.tiktokshop.com/docv2/page/seller-center-development-shops

核心账号支持 Create Product API、商品/订单/履约/退货测试，不需真实企业/KYC、TikTok 账号或付款。测试店铺位于生产环境的测试池，不能把核心账号的模拟订单流视为正式环境完全一致。财务、结算、客服和真实买家流程需要完整功能测试。

美国卖家授权入口是 https://services.us.tiktokshop.com/open/authorize，其他市场是 https://services.tiktokshop.com/open/authorize。Token 获取/刷新使用统一的 https://auth.tiktok-shops.com。授权必须使用当前应用获批的市场、店铺类型和 scopes。用户的 Heyi AI 应用目前是草稿；观察到已配置六个东南亚市场，不代表所有国家均已获批。

## 本地实现变更（尚未部署）

- 授权 start 请求接受可选 market；格式只允许 ISO 风格两位代码或 ROW。
- TikTok 官方 client 根据 market 选择 US/ROW 域名；服务端生成并持久化一次性 state，回调仍严格验证组织归属与 state。
- 默认调用兼容已有的 US base URL 配置；自定义 API 测试域名不被改写。
- 店铺管理页新增 TikTok 市场选择；重新授权沿用现有店铺 region，避免美国店铺错误跳到 ROW。
- 保留已有的一次授权多店铺入库、加密凭证、同一 token 组刷新机制。
- 当前 TikTok 发品 provider 明确只支持已确认 LOCAL 的本地商品流程；CROSS_BORDER/Global Seller 尚不能直接当本地发品。此次市场授权路由修复不宣称跨境发品已实现。

## 验证

34 项后端测试通过：OfficialMarketplaceSignatureTest 14、ShopAuthorizationServiceTest 8、ShopAuthorizationGrantPersistenceTest 4、TikTokShopOfficialPublishingProviderTest 8。

4 项前端店铺管理测试通过，包含新增 market 选择、重新授权优先使用原店铺国家、Shopee 不附加 TikTok market 参数。同步修正旧导航测试：授权入口位于独立店铺管理页，不再要求商品页存在用户先前要求移除的入口。

证据：output/tiktok-core-auth-20260910/backend-tests.log、frontend-tests.log。

## 尚待完成

运行环境 APP_TIKTOK_SHOP_ENABLED=false，Service ID、App Key、App Secret 均未配置。应用后台曾显示 hoyee.org.cn 回调，本地使用 ffd45c8.r7.cpolar.cn，需验证当前有效配置并统一，不能删去 state 校验来迁就从 Partner Center 发起的不带 state 的跳转。

当前用户已在内置浏览器重新登录 Heyi AI，MY 核心账号创建表单已填 heyi-my-core（本地/个人），但点击创建未确认成功。浏览器控制台反复显示 failed to get token before request Q。不能据此断言 MY 不支持沙箱，也不能断言仅是应用回调故障。

已打开独立 ego-browser 空间 108“马来西亚 TikTok 核心授权排查”并交给用户登录，用于区分浏览器/会话问题。必须收到用户确认后才能 takeOverTaskSpace(108)。用户提供的联系邮箱可用于测试店铺授权；不在此记录任何密钥或 Token。

## 继续验证

用户继续后，内置浏览器账号已完整加载，MY 列表确认存在并选中 heyi-my-core：shop_id/merchant_id 7494895915614569685，shop_code MYMYLCMRLYXS，显示名 SANDBOX_MY7682708256049104660，核心/本地/生效。列表还存在另一个同昵称账号 7494895923909461080，本次不使用也未删除。先前创建请求不能再认定为最终失败，应以现在的已创建记录为准。

## 本地回调验证与修改

用户明确要求本次 TK 回调与 Shopee 一样回到本地。GET https://ffd45c8.r7.cpolar.cn/api/v1/shop-authorizations/callback/tiktok_shop（不带 code/state）返回 HTTP 302，Location 指向 localhost:3000 且提示 state 无效，证明公网隧道与本地回调处理器可达且保留 state 防护。GET https://hoyee.org.cn/api/v1/shop-authorizations/callback/tiktok_shop 返回 401。cpolar 的 /actuator/health 返回 404 是仅允许 OAuth 回调路径的网关策略，不能据此认定隧道不可用。

已在开发者平台原已登录标签页的编辑框填入 https://ffd45c8.r7.cpolar.cn/api/v1/shop-authorizations/callback/tiktok_shop，但自动点击保存后仍停留编辑态，没有确认保存成功；已请求用户手动点击保存。应用密码显示请求返回空内容，未获取或记录真实 App Secret。后续仍需配置本地应用凭据并从本地 start 接口生成安全 state，不得把平台直接授权无 state 的响应绑定到任意组织。

后台包已构建成功，多市场授权修复尚未部署。

## 回调已保存、接入代码已部署

已在平台确认回调最终显示 https://ffd45c8.r7.cpolar.cn/api/v1/shop-authorizations/callback/tiktok_shop，编辑态结束。真实 App Secret 已通过平台复制控件读取，使用一次性 RSA 加密传输后仅写入 live checkout 的 .env（0600）；临时传输密钥已删除、浏览器临时凭据剪贴板副本已清空，源码/文档无明文密钥。

已启用本地 TikTok 应用并部署后端与前端，运行中哈希已比对，见 output/tiktok-core-auth-20260910/deployed.json。发布/图片任务部署前均为零。保留 before-tiktok-auth-20260910 回滚镜像。后台健康检查 UP；回归测试汇总见 tests-summary.json。

JAR SHA256 ca71184dfff6bcaa1a2f9e4531bef509fb07fea6cc9162cc8de09f58e2f7b53f。

已打开本地店铺管理页面 http://localhost:3000/?view=shops#shops，目前该浏览器未登录本地系统。已请求用户登录或在原先已登录浏览器刷新进入店铺管理，以真实系统账号调用 start 接口生成一次性 state。尚未完成马来西亚 OAuth 回调、店铺入库或 Create Product API 实测，不得宣称已接通。
