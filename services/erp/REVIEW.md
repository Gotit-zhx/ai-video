# HOYEE ERP 平台申请资料草稿

主体经用户确认：厦门寰球赫逸电子商务有限公司。本文是准备材料，不是审核通过证明，未向平台提交申请。

## 可填入申请表的链接

| 项目 | URL |
| --- | --- |
| 产品网站 | https://hoyee.org.cn/erphoyee/ |
| 演示入口 | 打开产品网站，点击「体验演示工作台」或 Explore live demo |
| 使用指南 | https://hoyee.org.cn/erphoyee/guide |
| 隐私政策 | https://hoyee.org.cn/erphoyee/privacy |
| 服务条款 | https://hoyee.org.cn/erphoyee/terms |
| 数据删除说明 | https://hoyee.org.cn/erphoyee/data-deletion |
| 联系与支持 | https://hoyee.org.cn/erphoyee/support |
| Shopee 回调 | https://hoyee.org.cn/erphoyee/callback/shopee |
| TikTok Shop 回调 | https://hoyee.org.cn/erphoyee/callback/tiktok |

## 产品介绍（与当前版本一致）

HOYEE ERP 是厦门寰球赫逸电子商务有限公司开发的店铺运营管理工具，面向公司的 Shopee 与 TikTok Shop 业务。当前提供店铺档案、本地商品/SKU、价格与库存记录、订单流程演示、CSV 导出和操作日志。访问者可使用隔离的合成数据体验工作流程。官方店铺授权适配已准备，待应用配置、权限开通及开发店铺联调；正式订单同步和平台写操作尚未开放。

HOYEE ERP is a store operations tool developed by 厦门寰球赫逸电子商务有限公司 for the company's Shopee and TikTok Shop operations. The current release provides store profiles, local product/SKU and price/inventory records, a demo order workflow, CSV exports and activity logs. Visitors can explore the workflow using isolated synthetic data. Official store authorization adapters are prepared, pending application configuration, access approval and test-store validation. Live order synchronization and marketplace write operations are not yet enabled.

## 约 3 分钟演示路径

1. 官网展示产品名称、真实公司主体、功能、平台连接状态及政策链接；可切换 English。
2. 点击演示入口，新建独立工作区；说明这是合成数据，不是平台生产数据。
3. 店铺管理：查看 Shopee/TikTok Shop 示例店铺，展示授权状态及连接要求。
4. 商品管理：搜索 SKU，新建本地商品草稿，保存并重新加载证明持久化。
5. 库存管理：修改库存与预警阈值，在操作记录中查看变更。
6. 订单管理：打开待处理订单 → 模拟备货 → 输入 DEMO 物流单号 → 模拟发货 → 完成。此流程不向平台提交。
7. 导出 CSV；数据与设置中展示删除和重置入口。
8. 展示隐私政策与支持表单。

如果平台要求演示真实授权、读取真实/开发店铺订单或执行平台功能，现有演示不应替代这些证明；先获取所需测试凭证后完成相应功能与录屏。

## 仍需主体准备和确认

- 营业执照、公司地址、申请联系人及联系方式：按平台实际表单提供，不从本项目虚构。
- 目标市场、应用类别（自用/服务商）、需要的权限：目前未提供，按真实使用场景选择。
- 如平台强制企业客服邮箱或电话号码：需提供可实际接收和处理请求的联系方式；当前网站提供有效的站内工单入口，未配置外发邮件。
- 平台 App Key / Secret、Shopee Partner ID / Key、TikTok Service ID，以及可用测试店铺：尚未提供，不得把管理员密码当成平台凭证。
- 当前服务器在中国大陆；目标市场若要求不同的数据存储或处理区域，需要先调整后再启用真实业务。
- 已准备的授权适配不是平台认证。后续真实同步还需按权限实现分页、限流、刷新 Token、解授权通知和重试，完成平台 contract/integration tests。

## 验证记录

- 16 项自动测试通过（本地及 Python 3.12 服务器容器），覆盖会话、CSRF、隔离、库存并发冲突、订单状态机、CSV 注入防护、支持请求权限、数据删除、过期清理、限流和 OAuth state。
- 浏览器已验证桌面官网、演示入口、商品库存保存、订单模拟备货/发货、中英文切换。
- 上线时原网站首页、app.js 和 Logo 内容哈希一致；五个原业务容器的 ID 和启动时间一致；只对 Nginx 执行平滑重载。

- 公网真实浏览器验证：管理员登录/退出、商品新增与刷新持久化、支持表单成功落库、英文隐私政策、390px 手机菜单与页面无横向溢出。
- en0 物理网卡直连服务器，HTTPS 证书验证成功；14 个路径状态校验通过，包括原站健康检查。
