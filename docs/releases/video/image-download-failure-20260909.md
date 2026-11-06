# 商品图片下载失败诊断与修复（2026-09-09）

> 历史发布记录；镜像、测试与“当前/最新”均指记录当时。 当前入口见[业务说明](../../business/README.md)与[运维指南](../../guides/operations.md)。

## 结论

本次大量“读取商品图失败”的已确认原因是代理 DNS 的 fake-IP 与下载器的公网地址校验冲突。`imgen.x.ai` 在应用运行环境中被解析为 `198.18.0.117`；下载器按既有 SSRF 防护拒绝该非公网地址。部分图片已经由模型生成并返回结果地址，失败发生在下载与归档阶段。

修复保留公网地址及 TLS 校验，只在系统 DNS 的结果全部属于 `198.18.0.0/15`、且输入不是 IP 字面量时，通过固定的公共 DNS-over-HTTPS 服务获得真实公网地址，再按原图片域名建立 HTTPS 连接。不得直接放行 `198.18/15` 或关闭证书校验。

本文不保存签名图片 URL、请求密钥、响应中的私人内容或生产数据库记录。新实例部署与本次 13 张图片恢复均已完成；验证覆盖后端状态、文件完整性和请求记录，未进行浏览器 UI 实测。

## 诊断证据

| 检查 | 结果及含义 |
| --- | --- |
| 应用环境 `getent` 与 Node DNS | 均将 `imgen.x.ai` 解析为 `198.18.0.117`，符合代理 fake-IP 特征。 |
| 时间关系 | 观察到生图返回后约 5 ms 即报下载失败，符合连接前地址校验失败的特征。 |
| 原 Java 下载器复现 | 约 40 ms 失败，底层异常为 `UnknownHostException`，原因为“商品图片解析到非公网地址”。 |
| 独立公共 DNS 查询 | Cloudflare DoH 与 Google DNS 均返回真实公网地址 `104.18.18.80`、`104.18.19.80`，查询时 TTL 为 300 秒。这些地址是诊断时的结果，不作为业务图片域名的永久配置。 |
| Node 读取三个已保存的结果地址 | 三个请求均返回 HTTP 200，用时约 0.7–1.2 秒，证明当时结果资源仍可访问。 |
| 原 Java 下载器的受控 DNS 对照 | 仅给诊断 Java 进程提供上述真实公网解析，同一个已保存地址在约 838 ms 内读取成功，返回 405,290 字节、`image/jpeg`。没有关闭 TLS 或公网地址过滤。 |
| 新下载器的动态 DoH 验证 | 隔离 Java 进程使用修复后的动态 DoH 路径读取真实结果地址，约 1,338 ms 成功，返回 405,290 字节；没有依赖固定的业务图片服务器地址。 |

受控 Java 复现的诊断日志位于 `/tmp/heyi-image-download-probe/actual-downloader.log` 与 `/tmp/heyi-image-download-probe/public-dns-downloader.log`。这里仅记录脱敏结论，不复制日志中的请求地址或其他私密内容。

动态 DoH 的隔离 Java 下载证据记录于 `dynamic-dns-downloader.log`。上述实验分别证明故障点在 DNS 地址选择，以及动态回退路径可以实际下载结果；部署后的 13 张图片下载与归档成功进一步证明新实例已经生效。

## 原实现为何显示同一个错误

`MarketplaceImageDownloader.download()` 同时用于读取采集原图和读取生图模型的结果 URL。`AiWatermarkRemovalProvider` 在取得原始参考图以及处理 `RESULT_RECEIVED` 记录时都会调用它，因此原文案无法判断失败发生在生成前还是生成后。

非 2xx 响应原本会显示 HTTP 状态码；DNS、TLS、连接池等待、断流及读超时则统一包装为“读取商品图失败”。队列工作器过去只保存顶层异常消息，底层异常类型没有进入日志，进一步掩盖了本次 `UnknownHostException`。

另外发现一个容量问题：使用中的 HttpClient 5.6.1 默认连接池为总共 25 个连接、每个目标路由 5 个连接，原租用等待仅 15 秒，而水印队列全局上限为 500 个任务。该设置可能放大批量下载压力，但本次真实 URL 的快速失败已经明确定位为 fake-IP 校验，不把连接池问题当作已证实的本次根因。

## 修复范围

### 下载器与 DNS

涉及文件：

- `src/main/java/com/yingdao/aicommerce/shop/infrastructure/MarketplaceImageDownloader.java`
- `src/main/java/com/yingdao/aicommerce/shop/infrastructure/PublicImageDnsResolver.java`

处理规则：

1. 正常公网系统 DNS 结果沿用原连接方式；真实私网、回环地址、混合公网/非公网结果继续拒绝。系统解析失败不会自动改走 DoH。
2. 仅“全部系统地址属于 `198.18/15` 且主机不是 IP 字面量”触发 DoH。直接输入 fake-IP、私网 IP 或回环 IP 仍然失败。
3. DoH 默认使用固定 Cloudflare HTTPS 服务。该客户端使用固定公共服务器 IP 启动连接，避免查询 DoH 服务本身时再次落入 fake-IP；TLS 仍按服务域名校验。
4. 可通过 `app.product-capture.image-dns.google-fallback=true` 启用一次 Google 备选，默认关闭。仅主 DoH 服务的传输失败允许尝试备选；DoH 返回私网或无效地址时直接拒绝。
5. DoH 仅解析 A 记录，每个结果地址都再次通过原公网校验。响应不跟重定向、不带业务密钥，响应体最多 64 KiB，记录数最多 64，并设置连接、响应和读取时限。
6. 按返回记录 TTL 缓存，最长 300 秒；最多缓存 256 个域名、最多保留 64 个并发域名解析。同一域名的并发请求共享一次查询，缓存地址通过副本返回，避免调用方修改缓存。
7. 下载连接池明确设为总共 128 个、每路由 32 个连接，租用等待调整为 60 秒。原图片连接超时 15 秒、socket/response 超时 45 秒保持现有配置。本次没有新增自动生图或 GET 重试机制。
8. 下载错误现在区分 DNS/公网校验、连接池等待、建连、读超时、TLS 和连接中断。对外消息只附加主机名，不拼接完整 URL、签名参数或底层异常消息。

### 业务阶段与恢复

涉及文件：

- `src/main/java/com/yingdao/aicommerce/product/application/AiWatermarkRemovalProvider.java`
- `src/main/java/com/yingdao/aicommerce/product/application/ProductWatermarkQueueWorker.java`

生图结果已保存时，处理器优先复用同租户、同商品、同图片 ID 的已归档原始参考图；图片 ID 绑定的原始 URL 没有更新路径。这样，远端原图暂时不可读不会阻止继续下载已经生成的结果，也不会重新计算出另一张参考图而触发重复生成。

错误消息明确分为“读取采集原图失败”与“生图已成功，下载结果图片失败；结果已保存，重试只继续下载”。生成返回的 `generation_json` 及 `RESULT_RECEIVED` 状态保留，下载恢复继续使用已保存结果地址。工作器记录图片 ID、异常类型及根异常类型，不序列化签名 URL、响应正文或模型密钥。

本次修复不改变前置水印检测、判断不清时提交生图、取消生成后复核的既有流程；也不改变全局 500 任务上限。

## 验证

相关测试共 **30 项通过，0 失败、0 错误、0 跳过**：

| 测试类 | 数量 | 覆盖内容 |
| --- | ---: | --- |
| `PublicImageDnsResolverTest` | 11 | fake-IP 转公网、正常公网沿用、真实私网/混合结果拒绝、IP 字面量拒绝、DoH 私网拒绝、TTL/容量、缓存副本、并发合并、失败后恢复、响应解析与体积限制。 |
| `MarketplaceImageDownloaderTest` | 4 | 公网地址过滤、混合地址拒绝、脱敏错误分类。 |
| `SafeProductImageUrlTest` | 2 | 原 URL 安全边界。 |
| `AiWatermarkLocalScreenTest` | 7 | 前置检测与生成规则、已收到结果不重生图、下载失败后重试仅继续下载、不增加生成后复核。 |
| `ProductWatermarkQueueIntegrationTest` | 6 | 真实隔离 MySQL 下的 500 并发共享上限、超过上限排队、失败不取消其他图片、恢复及聚合状态。 |

队列集成测试使用单独的临时 MySQL 8.4 容器，无宿主端口映射、无外部网络；Maven 容器共享其隔离网络进行本地 JDBC 测试。测试未使用生产数据库，临时数据库已销毁。其他 HTTP 测试均为 mock 或本机回环请求，没有调用收费生成 API。

Maven 环境为 `maven:3.9.11-eclipse-temurin-21`。测试选择参数：

```text
-Dtest=PublicImageDnsResolverTest,MarketplaceImageDownloaderTest,AiWatermarkLocalScreenTest,SafeProductImageUrlTest,ProductWatermarkQueueIntegrationTest test
```

随后串行执行 `mvn -q -DskipTests package`。相关代码 `git diff --check` 通过。日志：`/tmp/heyi-image-dns-all-tests.log`、`/tmp/heyi-image-dns-package.log`。

## 部署与恢复结果

- JAR：`target/ai-commerce-platform-0.1.0-SNAPSHOT.jar`
- 大小：75,229,344 字节
- SHA256：`f72f03ebad28cc9232eba428a823c2809e65f1bf1b46af81c002bdeeaabd7f8f`
- 已检查 JAR 内包含 `PublicImageDnsResolver` 及下载器接线。
- 新应用镜像已通过实际部署目录的 Compose 配置完成 `app` 重建；运行实例 JAR 的 SHA256 与上述产物一致。

| 部署后检查 | 状态 |
| --- | --- |
| 新应用启动及健康检查 | 已完成；health HTTP 200，状态 `UP`，实例 JAR SHA256 匹配。 |
| 新运行实例通过动态 DoH 下载真实结果地址 | 已完成；部署后本次 13 个结果下载并归档成功。 |
| 本次 13 张图片恢复 | 13 张全部成功，无本次遗留失败或处理中的图片。 |
| 来源与输出归档 | 13 份原图、13 份输出全部可完整解码，内容 hash 与记录一致；只读恢复后校验 `ok=true`。 |
| 重复生成检查 | 本次恢复没有调用模型；13 份生成记录 hash 均保持不变，没有新增检查记录。 |
| 商品与处理任务 | 目标任务 `SUCCEEDED`，成功数 16/16；目标商品状态 `READY`。 |
| 全局队列快照 | 恢复完成时为 112 条 `SUCCEEDED`，`FAILED`、`RUNNING`、`QUEUED` 均为 0。 |
| 历史检查记录 | 目标商品检查记录总数仍为 17：16 条 `READY`，1 条既有 `REVIEW_REQUIRED`；未删除历史记录。 |
| 近期应用日志 | 恢复完成后未见 `Watermark image failed` 或提交队列结果错误。 |

恢复使用带状态条件的 SQL 事务并已 `COMMIT`，操作范围为 1 个任务、13 条队列记录、13 条图片记录和 1 个商品。原 `generation_json`、已收到的结果地址及来源归档均保留，仅继续处理尚未成功归档的结果，没有清空生成记录或再次提交模型。

恢复与部署的脱敏汇总证据文件：

- `output/image-download-failure-20260909/recovery-execution.log`
- `output/image-download-failure-20260909/archives-after-verification.json`
- `output/image-download-failure-20260909/deployment-verification.json`

本次验证没有打开浏览器核对页面或拍摄截图，因此不声称商品详情的 UI 标记已经做过视觉实测。后端商品、任务、队列状态及实际归档文件已经核验完成。
