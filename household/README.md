# 拾间 · 手机家务管理

给容易忘做家务、漏掉收尾细节的人使用。手机浏览器打开即可使用，可添加到主屏幕。

## 日常使用

1. 输入家庭访问密码，设备会保持登录。
2. 在「今日」按晚上集中收拾、睡前检查两个时机处理家务；默认 20:30 开始、23:00 补漏。打开任务，逐条勾选完成标准。
3. 全部细节完成后，任务才计为完成。当天不适用的任务可以跳过。忙的时候可查看保底任务，其他任务仍保留真实状态。
4. 在「家务」修改耗时、步骤和频率，安排每天或每周的家务。
5. 在「设置」调整时区和提醒时间。开启手机通知需用户在自己的手机授权。iPhone 需用 Safari 添加到主屏幕后，从主屏图标打开并开启通知。
6. 无法使用网页推送时，可下载日历提醒，在手机日历中导入并确认开启提醒。日历应用是否保留提醒以导入结果为准。
7. 「设置」支持独立的未完成邮件提醒。默认晚上 23:00 检查，当天还有未完成且未标记不适用的家务时，发送未完成步骤和系统入口；全部完成则不发送。邮箱、时间和开关可以自行修改。

每日量化包括任务数量、完成比例和估计用时；用时来自任务配置，并非实际计时。详细打勾记录和设置保存在服务器。断网时会说明状态，不会将失败的操作显示为已保存。

## 本机运行

```sh
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
DATA_DIR=./data PUBLIC_URL=http://127.0.0.1:8787/ .venv/bin/gunicorn --workers 1 --threads 4 --bind 127.0.0.1:8787 server:app
```

首次启动会在 DATA_DIR/access-password.txt 生成访问密码。不要提交数据目录和访问密码。API 协议见 API.md。

## 日本服务器部署

服务地址：https://38.54.88.139/chores/

独立服务名 `household.service`，只监听 `127.0.0.1:8787`。Nginx 在现有 HTTPS 站点中新增 `/chores/` 路由。应用发布目录 `/opt/household/releases/`，当前版本软链接 `/opt/household/current`，持久数据 `/var/lib/household`，环境配置 `/etc/household.env`。

```sh
python3 ops/deploy.py --host root@38.54.88.139 --identity /path/to/ssh-key --public-url https://38.54.88.139/chores/
```

脚本新增专用 homecare 系统用户，以普通用户启动服务。部署前测试应用及前端，再运行部署命令。不要使用多个 Gunicorn worker，提醒调度器在单进程内运行。

```sh
systemctl status household
journalctl -u household -n 50 --no-pager
curl -fsS http://127.0.0.1:8787/health
```

数据库与密钥必须一起保留。更新应用不会清空数据。系统设置页提供不包含访问凭据的 JSON 数据导出。

邮件发送凭据单独保存在服务器 `/etc/household-mail.env`（root 600），不随源码发布。QQ 邮箱使用 `SMTP_HOST=smtp.qq.com`、`SMTP_PORT=465`、`SMTP_SECURITY=ssl`，另设 `SMTP_USER`、`SMTP_PASSWORD`（邮箱授权码）、`MAIL_FROM`。修改此配置后重启 household 服务。
