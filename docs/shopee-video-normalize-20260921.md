# Shopee 商品视频预处理（2026-09-21）

实际失败为 `Video is too short`，源视频 `f48a5adc-a087-46b3-9283-0ecb691a3430` 容器时长 9.938005 秒，1,031,247 字节。不是店铺或文件体积超限。

接口资料：`v2.media_space.init_video_upload`，10–60 秒（含端点），最大 30 MB。此次以十进制 30,000,000 字节保守执行。可核对的 schema 镜像：https://github.com/congminh1254/shopee-sdk/blob/9865d050057f763344a0064fb831f6157ec7b15c/schemas/v2.media_space.init_video_upload.json 。

实现：
- FFprobe 优先读取首个视频流时长，缺失时读取容器时长。
- 9.5 秒至不足 10 秒的视频末帧延长至 10.2 秒；更短或超过 60 秒阻止提交，不裁剪或循环。
- 超过 30 MB 自动转码 H.264/AAC，保持画面比例，最长边最多 1280，CRF 23 配合按时长计算的码率上限，并复核输出大小和时长。
- 原件不变，转换产物保存于 `capture-videos/shopee-normalized-v1`，按原件 SHA256 缓存并校验产物 SHA256。上传账本使用产物真实 SHA256、字节数和 MD5，避开旧文件永久失败记录；续传和商品发布幂等逻辑保持不变。
- 预处理源文件最多 200 MiB，FFmpeg 180 秒超时，FFprobe 每次 15 秒；禁止网络协议读取。

验证：14 项上传/续传/幂等测试，4 项实际 FFmpeg 集成测试，3 项媒体签名测试，全通过、无跳过。生产原片转换得到 10.2 秒、1,173,908 字节，再次调用产物字节一致。

发布仅替换 3 个 class（上传器、内部 record、预处理器），以正在运行的生产 jar 依赖重新编译。镜像 `ai-commerce-platform:shopee-video-normalize-20260921`。上线前无活动图像/视频/发布任务，健康 UP，环境变量和存储挂载逐项保持一致。构建、备份、回滚入口在服务器 `/opt/heyi/deployments/shopee-video-normalize-20260921`。

真实媒体接口验证：对同一店铺上传处理后的原片，初始化、分片和完成接口均成功；连续按同一上传编号查询返回 TRANSCODING，尚未取得最终 SUCCEEDED 回执。此次只测试媒体上传，没有创建或重复发布商品。线上公网 `/actuator/health` 为 UP。
