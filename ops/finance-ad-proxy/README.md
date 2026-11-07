# 财务广告专用代理

此目录管理一个原生 Xray 核心进程，向 Docker 私网提供 HTTP CONNECT，再经私有 VMess AEAD/TCP 节点访问 TikTok 广告 API。没有界面、TUN、全局代理、订阅转换、规则订阅或 GeoIP/GeoSite 数据库。

仅接受 `business-api.tiktok.com:443`，其他目标默认拒绝。节点访问凭据由运维在仓库外保存，应用只接触本地代理的主机和端口；原有店铺与其他业务客户端不使用该通道。目标 HTTPS 由 Java/curl 验证，不在代理中解密。

## 固定运行制品与边界

官方项目为 `XTLS/Xray-core`，版本及归档/二进制 SHA-256 见 `runtime.json`。从官方版本附件取得 `Xray-linux-64.zip` 和 `.dgst` 后，同时核对固定归档摘要及解压后 `xray` 摘要，仅安装二进制及许可文件。版本升级需重新验证和记录，不跟随 latest 自动更新。

服务文件 `heyi-finance-ad-proxy.service` 默认设置一个 Go 调度核、32 MiB Go 内存目标、48 MiB 内存软上限、64 MiB 硬上限、10% CPU 配额和 64 项任务上限。限额不是实际占用；必须在部署回执中保存实测峰值。动态低权限用户运行，凭据通过 systemd `LoadCredential` 提供，输出与错误均为 null，不保存访问日志。

## 私有配置

`render.py` 从私有文件读取单个 VMess 分享链接，只支持当前验证范围：AEAD（Alter ID 0）、TCP 无伪装、VMess 自身加密、未配置额外传输 TLS。无伪装的 `type` 可以缺省、为空或为 `none`，接受省略末尾填充符的 Base64；非法编码与其他协议/参数仍会拒绝，不能静默省略或降级。转换完全在本地进行。

示例（路径和地址按现场确定）：

```bash
python3 ops/finance-ad-proxy/render.py \
  --input /private/node-share.txt \
  --output /private/finance-ad-proxy.json \
  --listen 172.18.0.1 --port 18089 --source 172.18.0.0/16
```

只允许私有 IPv4 监听地址和来源网段，禁止 `0.0.0.0`、公网监听及任意来源。输出文件权限为 0600，拒绝覆盖已有文件；先在私有暂存路径生成并运行 `xray run -test -config PATH`，再安装到 `/etc/heyi/finance-ad-proxy/config.json`（目录 0700、文件 0600）。禁止把分享链接、UUID 或生成的真实 JSON 放入 Git、命令行参数或诊断输出。

应用已有的广告 properties 文件保留原 `app-id` / `app-secret`，可以另外加入：

```properties
proxy-host=172.18.0.1
proxy-port=18089
```

这避免新增应用环境或挂载。两项都缺失时沿用原直连；显式 `APP_TIKTOK_ADS_PROXY_HOST/PORT` 优先。更改在应用重启后生效。配置读取错误不会输出文件内容；上线前应验证可读性和端口格式，避免应用启动失败。加上网络键不改变应用凭据 revision，也不替换应用密钥或现有密文。

## 安装与发布

这是明确的主机维护，不能只同步源码便宣称服务已安装。该目录归入 `operations` 指纹，须验证文件与运行状态后更新已安装操作基线；不得直接刷新指纹掩盖未安装的改动。

1. 协调实际基线，按 `/run/lock/heyi-application-release.lock` → `/opt/heyi/operations/.project-release.lock` 顺序持锁。原配置保存为服务器私有恢复副本。
2. 将核验后的二进制安装在 `/opt/heyi/finance-ad-proxy/releases/VERSION/`，根目录和二进制父目录只含公开程序文件，权限 0755。`current` 指向受验收版本；凭据独立保存在 `/etc/heyi/finance-ad-proxy/`。
3. 核对应用实际网络和 Docker 网关，安装配置及服务，验证允许的目标、拒绝的目标、证书校验、资源占用与重启恢复。
4. 保留既有广告应用配置的内容、权限和挂载，加入两项代理连接参数；业务密钥和加密 revision 必须不变。按已批准维护范围登记 `operations` 指纹及回执。
5. 应用通过必需 CI、候选制品与 PR 后，走 Gitflow 正式标签和统一运维入口激活。核对实际 Java 客户端的官方 HTTPS 响应；真实账户授权仍需由账户管理员完成。

回滚时恢复本次保存的应用连接配置和程序指针，验证后停止本次代理服务。业务账户、令牌密文、已入库广告记录与检查点保留。旧应用不识别代理键时不会得到代理连通能力；镜像回滚也需要明确连接配置的效果。
