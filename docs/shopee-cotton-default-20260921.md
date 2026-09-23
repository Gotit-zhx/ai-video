# Shopee 默认材质（2026-09-21）

商家要求未填写材质时默认棉。在发布前调用官方 get_attribute_tree，以目标类目 Material 的 Cotton 选项编号构造 attribute_list，不硬编码跨类目编号。保留非空显式材质及其他属性，空材质替换而非重复添加。productAttributes 为空时兼容 shopeePayload.attribute_list。没有 Material 的类目不注入该属性；定义不可读取或没有 Cotton 时发布前明确失败。

PH 类目 100352 实测官方 Material=100134，Cotton=1149。旧 get_attributes 返回 api_suspended，采用有效 get_attribute_tree。

验证：ShopeeMaterialDefaultsTest 与 ShopeeProductVideoPublicationTest 共 8 项通过。发布使用生产原始 jar，仅叠加 provider 与 default helper 编译类，保留此前视频、标题、价格、尺码表修复与环境配置。
