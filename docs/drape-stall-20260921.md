# 搭放停止排查

执行 275ca06c-3b7a-46c9-a4d8-43bc617d6ea9，批次 3c0ed604-fbd7-4d43-a1c2-6c3ca2b9b217，停在 MOCKUP / WAITING_INPUT。

实物复现：背景高对比网格耗尽全局 7000 SIFT 特征额度，印花参考通常仅 0–3 个匹配。提高至有界 24000（本图实际 11666）后可匹配数十至数百点，未放松几何/多尺度/稳定性阈值。27 项处理器测试通过，线上最小替换 drape_reuse.py。

完整本地 prepare 接着发现 GARMENT_CROPPED：服装下摆触及边缘。目视比对 guide 与 master 也发现小胸花被模型放大并居中。因此没有将任务推进发布，也未覆盖原图/付费重生。暂停信息已更新为真实诊断。仍需合格母图才能产出并恢复。

页面修复：状态/说明独占整行，按钮另行，桌面批次列表 240px。线上 CSS 最小追加并更新 HTML 版本，浏览器隔离 DOM 布局验证 1200px 容器下状态行 830px、侧栏 240px，无横向溢出；登录态未继承，未冒充真实登录页面完整验收。

线上镜像 heyi-watermark-custom:drape-features-20260921、heyi-ai-frontend:workbench-layout-20260921。原图和诊断输出在 output/drape-stall-20260921，未提交私有业务图片到版本控制。
