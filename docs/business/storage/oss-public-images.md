# OSS 图片公开直链

生产环境已启用 `APP_OSS_PUBLIC_IMAGES=true`。正式版后续部署需要保留该环境变量；默认关闭用于避免其他环境意外公开文件。

- 图片按 OSS Content-Type 判断，普通上传设置 `public-read`；分片上传必须在 CompleteMultipartUpload 请求设置对象 ACL。
- 图片原图和下载链接不包含签名、凭证或有效期；缩略图保留无签名的 `x-oss-process` 处理参数。
- 非图片文件保持私有，继续使用原有签名链路。不会修改整个 Bucket 为公共读，更不会开放匿名写入、删除或列举。
- ECS 角色需要项目对象范围内的上传和对象 ACL 权限；OSS 的“阻止公共访问”不能阻止这些图片。
- 现有图片需要单独迁移对象 ACL，开启应用配置不会自动修改旧对象。线上现有 1,074 张图片已完成迁移；图片 URL 不再因签名过期失效，但删除对象或改回私有仍会使其不可访问。

## 可选私有素材签名

`APP_OSS_SIGNING_CREDENTIALS_FILE` 可指向容器内只读挂载的 properties 文件（`accessKeyId`、`accessKeySecret`），`APP_OSS_DIRECT_LINK_TTL=72h` 控制长期 RAM 身份的私有素材签名有效期。未配置时使用原 ECS 身份及短期签名。真实密钥、Cookie 和运行环境文件不得提交到仓库。

以前发出的应用网关 OSS 链接由独立 `OssReferenceLinkService` 与 `PublicOssReferenceController` 兼容。新图片不走该网关；正式分支原有 `VideoReferenceLinkService` 行为不变。

## 验证

测试覆盖公开图片的普通上传与分片合并 ACL、无扩展名图片、缩略图参数、非图片私有权限、跨 Bucket 拒绝、可选签名身份及旧网关签名/HEAD/Range。部署后需验证新上传图片的匿名 GET/HEAD 均为 200，非图片匿名访问仍为 403。

对象公开和视频厂商成功读取是两个验收项，不能只根据 OSS 测试通过声称视频生成成功。
