# 原客户端改造实验

目标：沿用用户提供的 BOSS 原客户端扫码登录、真实会话和消息接收，增加赫逸工作台。实验产物使用单独的名称、App ID 和数据目录。

Mac 使用已审核的 `1.7.4 / ARM64` 输入，保留原认证和登录窗口。Windows `1.7.5` 安装器与主程序实际都是 x86；原生启动函数已有反编译结果，加密的业务入口仍未恢复，Windows 改造应用尚未构建。

## 当前实现和验证

- Mac 原客户端登录窗口可以启动，扫码登录、手机登录与协议控件已渲染；没有完成真实账号扫码。
- 赫逸入口使用已有租约授权和用户隔离；原客户端的用户 ID 需要与绑定账号一致。
- 原客户端接收回调进入活动队列，不额外创建消息订阅或轮询。
- 文本发送等待候选人、账号和 `clientMid` 一致的消息回执。超时保持未确认，不自动重复发送。
- 现有 SDK 请求保护继续包围原生传输：额度记账、Cookie 校验、职位核对和风险停止已通过离线测试。
- 原包 Electron `22.3.24 / Node 16.17.1` 的兼容性、回执夹具、本地桥接和原生 Python worker 已有离线检查。

尚未适配完整业务流程：自动索取简历、附件下载和部分依赖网页组件的 SDK 动作。未支持的路径会停止并报错，不生成虚假的发送或下载结果。原生传输对当前服务器接口的兼容性、真实账号与 Windows 运行行为仍需验证。实验版不替换官网稳定版。

## 构建 Mac 实验产物

在 `client-workbench` 目录安装独立依赖：

```sh
pnpm install --ignore-workspace --ignore-scripts
```

使用项目已有 Python 环境构建 `hr-desktop/runtime/build.py`。随后：

```sh
python build_mac.py \
  --source-app "/Volumes/BOSS直聘 1/BOSS直聘.app" \
  --output-app "/Users/heyi/Applications/赫逸工作台-改造测试版.app" \
  --sdk-endpoints "/absolute/path/to/boss_agent_cli/api/recruiter.yaml"
```

构建器核对原 `main.js` 的 SHA-256，保留有效的 ASAR 文件和原生模块，跳过目录中超出容器范围的异常条目。认证逻辑和原生可执行文件不被补丁修改。改造应用使用本地 ad-hoc 签名，不能沿用厂商签名。内部 `CFBundleName` 保留，以兼容 Electron 22 查找原 helper 的规则；外部名称和 App ID 明确区分实验版。

`build_mac.py` 拒绝覆盖现有应用。第三方文件和派生产物在仓库外；不要将它们提交或发布到 Git。

## 离线验证

```sh
node --test native-rpc.test.cjs
```

运行派生应用的 `--heyi-workbench-smoke` 时，必须同时提供新的 `HEYI_WORKBENCH_TEST_DATA` 和 `HEYI_WORKBENCH_TEST_OUTPUT`；测试只使用夹具，不运行原客户端登录。

`--heyi-workbench-ui-check` 使用原客户端启动路径，需要 `HEYI_WORKBENCH_TEST_PORT=0`，从而不占用稳定助手端口。它仅检查原登录控件的渲染，会加载原登录页及其公共网络资源，不扫码或发送候选人消息。

## Windows 静态分析

`inspect_windows.py` 读取 PE 布局、入口地址和固定加载标记。`NativeEntryProbe.java` 在 Ghidra 的只读导入中逐个反编译选定入口，限制函数与指令数量，不执行 EXE。输出的伪代码是机器码推断，不等于还原原始源码。

本次 Rea 整个 Windows EXE 的自动分析达到 600 秒上限，随后使用同一 Ghidra 后端的定向脚本取得 8 个启动函数。Rea 的 PE 资源解析独立完成。加密 ASAR 的目录、加载标记和 `main.jsc` 字节码入口已经定位；没有恢复加密脚本、修改签名 manifest 或解除平台风控。
