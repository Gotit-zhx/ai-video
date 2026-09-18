# 大言云对象存储

生成成片与视频 ZIP 不上传对象存储。可公开读取的厂商 HTTPS 成片链接在审核成功、数据库提交后成为下载地址，临时审核视频随后删除。需要厂商鉴权的成片仍通过平台授权下载，不向浏览器暴露厂商密钥。

正式接入使用私有 Bucket、大言云 OSS endpoint 和 Java SDK 官方示例的 V1 签名。大言云不支持图片处理，预览链接不添加 `x-oss-process`。

在保留原 `APP_OSS_*` 阿里云配置的基础上设置：

- `APP_DAYAN_OSS_BUCKET`：大言云 Bucket，必须与旧 Bucket 名不同。
- `APP_DAYAN_OSS_CREDENTIALS_FILE`：容器内只读凭证文件，包含 `accessKeyId` 与 `accessKeySecret` 两个 Properties 字段。凭证不进入镜像或仓库。
- `APP_MATERIAL_ARCHIVE_ENABLED=true`：启用本地工作素材自动归档。

新写入使用大言云，旧 `oss://旧Bucket/…` 路径仍通过原阿里云客户端读取。仅替换 endpoint 不会迁移历史对象。

归档覆盖拆解目录、任务目录内的图片/参考视频/音频及自动化附件。成片、下载包、正在写入的文件不归档。每个文件有已验证的大小、修改时间与 OSS 地址标记，重复扫描跳过未变化的文件。保留拆解和重试需要的本地工作缓存；该归档不自动删除原文件。

服务器存量迁移使用逐文件 Content-MD5、HEAD 大小和 ETag 校验，写入私有迁移清单，支持断点续传。只有校验成功且本地文件未变化时才更新可使用 OSS 的素材记录。分析源视频和工作路径保留本地缓存。

验证覆盖新旧 Bucket 路由、私有签名、禁用图片处理、归档幂等性、成片与 ZIP 不写 OSS，以及审核提交后的清理顺序。`DayanOssLiveTest` 默认禁用，设置 `DAYAN_LIVE_TEST=true` 并只读挂载测试凭证到 `/run/dayan.properties` 可运行小文件读写测试；另设置 `DAYAN_LIVE_MULTIPART=true` 可验证 9 MB 分片。
