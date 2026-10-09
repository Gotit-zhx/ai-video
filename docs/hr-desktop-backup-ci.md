# 招聘助手免费备用构建

GitHub Actions 仍是主构建环境。额度、排队或平台故障导致 Windows 虚拟机不可用时，使用 CircleCI Free 的原生 Windows 备用环境；Mac 可继续使用已验证的本机 arm64 构建。不切换到收费套餐，不改变私有仓库可见性。

2026-10-09 核对官方资料：Free 计划支持私有仓库及 Windows/macOS，提供每月 30,000 credits，不结转。免费额度不是无限机器时间。Windows 配置使用官方文档中的 `windows-server-2022-gui:current`、`windows.medium` 和 PowerShell。

参考：[定价与免费额度](https://circleci.com/pricing/)、[Windows 执行环境](https://circleci.com/docs/guides/execution-managed/using-windows/)、[触发 pipeline API](https://circleci.com/docs/api/v2/index.html#operation/triggerPipeline)。

`.circleci/config.yml` 默认 `desktop_build=false`，普通提交不会启动虚拟机。备用构建按需触发，复用与 Actions 相同的 Node 22、Python 3.12、锁文件安装、运行时测试、网卡直连检查、源码及安装后程序自检。只有安装后自检成功才生成 SHA256、大小、版本、源码提交和 QA 压缩包；产物留在 CircleCI，不自动覆盖线上下载。

一次性接入：

1. 使用仓库所属 GitHub 账号登录 CircleCI，选择 **Free**，连接私有仓库 `HaLoBAI/ai-video`。优先使用仅授权该仓库的 GitHub App；若授权页面要求全部私有仓库访问，应先核对范围。
2. 项目配置读取 `.circleci/config.yml`。不填生产 BOSS、钉钉、SSH 或模型凭证。不要设置生产自动发布。
3. 在 CircleCI 创建个人 API token，只放在本机环境变量 `CIRCLECI_TOKEN`，不提交源码或打印日志。
4. 推送要构建的源码后，执行 `python3 scripts/ci/trigger-backup.py --branch codex/test-current-20260831`，或在 CircleCI 手动运行 pipeline，设置 `desktop_build=true`。
5. 查看 pipeline 的 `installers`、`verification` 产物。下载后校验 `verified-Windows.json` 的源码提交、SHA256 和大小，再按现有双平台协调发布流程上线。

触发只发一次请求；超时或响应丢失时先在控制台检查，不自动重复启动虚拟机。免费额度不足时停止并提示，不自动升级付费计划。

当前状态：配置与触发程序已准备；接入需要账号登录及仓库授权。备用 Windows 流程尚未在 CircleCI 实际运行，不能当作已经启用的自动切换。本批 0.1.15 Windows 安装包已由 GitHub 原生环境成功构建和自检，不依赖备用接入。
