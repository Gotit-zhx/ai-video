# HOYEE ERP

独立于现有 AI 平台的 ERP 首版。公司主体：厦门寰球赫逸电子商务有限公司。

- 官网：https://hoyee.org.cn/erphoyee/
- 管理员：https://hoyee.org.cn/erphoyee/login
- 演示：官网「体验演示工作台」，每个访客独立空间，24 小时有效。
- 使用指南：https://hoyee.org.cn/erphoyee/guide
- 审核材料和当前能力边界见 [REVIEW.md](REVIEW.md)。

## 当前功能

中英文官网和工作台、独立管理员登录、访客演示空间、店铺档案、商品草稿、SKU 和价格、库存/阈值编辑、订单筛选与模拟履约、CSV 导出、操作日志、隐私/条款/删除说明、支持工单管理、数据清理。

后端 Python 3.12 / Waitress / SQLite WAL。密码 PBKDF2-HMAC-SHA256（600,000 次迭代）；独立 HttpOnly/Secure/SameSite Cookie、同源校验、空间隔离、限流、CSV 公式注入防护、库存版本检查。OAuth state 按会话与平台绑定，一次消费；可配置的凭证使用 AES-256-GCM（工作区/店铺 AAD）加密。

## 正式平台接入状态

Shopee / TikTok Shop 官方店铺级授权适配已编写，默认关闭。未提供平台应用密钥，因此未完成真实平台授权端到端验证。当前不提供真实订单同步、库存推送、商品发布或平台发货；不要在审核材料中宣称这些能力已经可用。

Shopee 使用 `/auth` 新授权入口；TikTok 使用 `/open/authorize?service_id=...`。协议基于主项目 `docs/business/commerce/official-shop-integration.md` 中 2026-09-04 的核验记录。默认不重用主项目任何数据库、账号、密钥或接口。

专用回调：

- https://hoyee.org.cn/erphoyee/callback/shopee
- https://hoyee.org.cn/erphoyee/callback/tiktok

对真实应用配置 `.env.example` 中对应字段之前，需按目标市场验证应用类型、授权范围、数据区域和开发店铺。没有凭证不能完成平台端测试；配置布尔开关本身不代表审核通过。首版不支持 Shopee 主账号多店授权；需要选择具体店铺。

## 本地运行和测试

```sh
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
ERP_ORIGIN=http://127.0.0.1:8137 ERP_COOKIE_SECURE=false .venv/bin/python server.py
python3 -m unittest discover -s tests -v
```

从 http://127.0.0.1:8137/erphoyee/ 进入演示。首次创建管理员时通过环境变量设置至少 16 字符的随机 `ERP_ADMIN_PASSWORD`；不会提供默认管理员密码。生产必须使用 HTTPS 和 Secure cookie。

## 服务器位置与隔离

- 发布目录：`/opt/heyi/erphoyee/releases/20260908`
- 当前链接：`/opt/heyi/erphoyee/current`
- 环境变量：`/opt/heyi/erphoyee/shared/.env`（权限 600）
- SQLite：`/opt/heyi/erphoyee/shared/data/erp.sqlite3`
- 容器：`hoyee-erphoyee`，Compose 项目 `hoyee_erp`
- 网络：接入现有 `web_sdk__default`，别名 `services/erp`，内部 8137，不向宿主机公开端口
- 容器以 UID 10001 运行，根文件系统只读，独立数据挂载，256 MiB / 0.5 CPU / 64 PIDs 限制
- 既有 Nginx 仅新增 exact `/erphoyee` 和 prefix `/erphoyee/`；既有业务容器未重新启动
- 路由已写入本仓库 `frontend/nginx.conf`，未来构建仍包含该路由；动态 DNS 避免 ERP 下线阻止 Nginx 启动
- 线上当前前端 filesystem 已无暂停快照为 `heyi-ai-frontend:erphoyee-route-20260908`，活动 Compose 最后一个覆盖文件已更新镜像名，以保留容器重建后的路由。未改主站页面/JS/数据库。

## 更新、回滚与备份

仅在本目录执行：

```sh
cd /opt/heyi/erphoyee/current
docker compose build
docker compose up -d --no-deps erphoyee
```

不要运行主项目的 `docker compose down`。变更 Nginx 前备份、`nginx -t`，仅 graceful reload。
首次上线备份：`/opt/heyi/erphoyee/backups/20260908-101126`，包含原 Nginx、原最后一个 Compose 覆盖文件、原资产哈希和容器 ID/启动时间。

回滚本次入口：先确认上线后主 Nginx 无其他修改；有修改时只删除 BEGIN/END HOYEE ERP 区块，不整份覆盖。恢复活动覆盖文件中的原镜像 `heyi-ai-frontend:company-footer-20260905`。测试配置后 reload；最后仅停止 `hoyee-erphoyee`。保留独立数据目录。

SQLite 备份请用 SQLite backup API，不能在有写入时只复制主 `.sqlite3` 文件而遗漏 WAL。生产数据目前无自动备份；如启用备份，需同步更新隐私政策的保存与删除规则。自动清理每 5 分钟检查一次，删除过期演示、会话、授权 state 和 90 天前工单。支持工单仅存储，不发送电子邮件；管理员须登录定期处理。

管理员密码只在首次创建账号时读环境变量。后续改密码使用 `deploy/reset-admin.py`（交互输入），不要仅修改环境变量并假定数据库密码同步改变。
