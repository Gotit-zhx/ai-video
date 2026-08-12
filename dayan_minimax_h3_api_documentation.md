# Dayan MiniMax H3 视频生成 API

## 基本信息

- API 根地址：`https://api.dayan.design`
- 模型：`minimax-h3`
- 认证：`Authorization: Bearer sk_xxx`
- 请求类型：`application/json`
- 输出分辨率：720p
- 默认时长：5 秒
- 默认比例：16:9
- 支持比例：`21:9`、`16:9`、`4:3`、`1:1`、`3:4`、`9:16`

## 1. 创建生成任务

### 请求

```http
POST /api/v3/contents/generations/tasks
Authorization: Bearer sk_xxx
Content-Type: application/json
```

### 最简图生视频示例

```bash
curl https://api.dayan.design/api/v3/contents/generations/tasks \
  -H "Authorization: Bearer sk_xxx" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "minimax-h3",
    "content": [
      {
        "type": "text",
        "text": "保持参考人物、动作节奏和画面风格，生成连贯镜头。"
      },
      {
        "type": "image_url",
        "image_url": "https://example.com/person.jpg"
      }
    ],
    "duration": 5,
    "ratio": "9:16",
    "resolution": "720p"
  }'
```

### 多素材示例

```json
{
  "model": "minimax-h3",
  "content": [
    {
      "type": "text",
      "text": "保持参考人物和动作节奏，生成连贯镜头。"
    },
    {
      "type": "image_url",
      "image_url": "https://example.com/person-a.jpg"
    },
    {
      "type": "image_url",
      "image_url": "https://example.com/scene-b.jpg"
    },
    {
      "type": "video_url",
      "video_url": "https://example.com/reference.mp4"
    },
    {
      "type": "audio_url",
      "audio_url": "https://example.com/music.mp3"
    }
  ],
  "duration": 12,
  "ratio": "9:16",
  "resolution": "720p"
}
```

### 请求字段

| 字段 | 必填 | 说明 |
|---|---:|---|
| `model` | 是 | MiniMax H3 固定使用 `minimax-h3` |
| `content` | 是 | 提示词与参考素材数组 |
| `duration` | 否 | 输出时长，不传默认 5 秒 |
| `ratio` | 否 | 默认 `16:9`，竖屏使用 `9:16` |
| `resolution` | 否 | MiniMax H3 使用 `720p`，不传时默认 720p |
| `callback_url` | 否 | 异步任务回调地址 |

### `content` 元素类型

| `type` | URL/文本字段 | 用途 |
|---|---|---|
| `text` | `text` | 提示词 |
| `image_url` | `image_url` | 参考图片 |
| `video_url` | `video_url` | 参考视频 |
| `audio_url` | `audio_url` | 参考音频 |

### 内容规则

- `content` 必须包含一条文本提示词。
- 必须至少包含 1 张图片或 1 段视频。
- 不能只传音频。
- 参考项的 `role` 可省略。
- 音视频参考项的 `duration` 可省略，服务端会自动探测。
- 无需传入 `watermark`。
- 可直接传入服务端可读的 HTTP/HTTPS 公网 URL，包括仍有效的签名 URL。
- 创建任务时，服务端会自动安全归档外部素材并绑定当前 API 账号。

### 素材数量限制

- 图片：最多 9 张。
- 视频：最多 3 段。
- 音频：最多 3 段。
- 所有素材合计：最多 15 个。
- 参考视频总时长：不超过 15 秒。
- 参考音频总时长：不超过 15 秒。

### 成功响应

HTTP 200 表示创建成功，此时任务通常仍在运行，响应中不包含最终视频和最终扣费。

```json
{
  "id": "task_xxx",
  "model": "minimax-h3",
  "status": "running"
}
```

## 2. 查询任务

### 请求

```bash
curl https://api.dayan.design/api/v3/contents/generations/tasks/task_xxx \
  -H "Authorization: Bearer sk_xxx"
```

### 任务状态

- `created`：已创建。
- `running`：正在生成。
- `succeeded`：生成成功。
- `failed`：生成失败。

`created`、`running` 和 `failed` 状态不返回最终扣费或 token 用量。

### 成功查询响应

```json
{
  "id": "task_xxx",
  "model": "minimax-h3",
  "status": "succeeded",
  "content": {
    "video_url": "https://api.dayan.design/media/video/task_xxx"
  },
  "usage": {
    "total_seconds": 5,
    "input_seconds": 0,
    "output_seconds": 5,
    "input_image_count": 1
  },
  "resolution": "720p",
  "duration": 5,
  "cost": 2.5
}
```

### 用量字段

- `cost`：最终实际扣费，单位为人民币元；仅在 `succeeded` 查询响应和 `task.succeeded` 回调中返回。
- `output_seconds`：成功任务的输出秒数。
- `input_image_count`：输入图片数量。
- `input_seconds`：MiniMax H3 固定为 0。
- `total_seconds`：等于 `output_seconds`。

## 3. 参考素材归档（可选）

MiniMax H3 创建任务时可直接传入外部 HTTP/HTTPS URL，不必预先归档。需要提前验证或长期复用素材时，可使用 `ArchiveReference`。

### 请求

```bash
curl 'https://api.dayan.design/v1/assets?Action=ArchiveReference' \
  -H "Authorization: Bearer sk_xxx" \
  -H "Content-Type: application/json" \
  -d '{
    "URL": "https://example.com/person.png",
    "AssetType": "Image"
  }'
```

`AssetType` 可选值：

- 图片：`Image`
- 视频：`Video`
- MP3/WAV 音频：`Audio`

### 成功响应

```json
{
  "Result": {
    "DayanSourceURL": "https://cdn.dayan.design/.../reference.png",
    "AssetType": "image",
    "Bytes": 123456
  }
}
```

应完整保存并原样复用 `DayanSourceURL`。同一 API 账号下，已归档素材无需重复归档。其他账号的平台素材不可复用。

## 4. 素材格式限制

### 归档接口

- URL 必须是服务端可读的公网 HTTP/HTTPS 地址。
- 单文件上限：256 MB。

### 生成接口

#### 图片

- 格式：JPG、JPEG、PNG、WEBP、HEIC、HEIF。
- 单张不超过 30 MB。

#### 视频

- 格式：MP4、MOV。
- 编码：H.264、H.265。
- 单段时长：2–15 秒。
- 单段不超过 50 MB。
- 帧率：23.976–60 FPS。
- 宽高：256–5760 像素。
- 画面比例：0.4–2.5。

#### 音频

- 格式：MP3、WAV。
- 单段时长：2–15 秒。
- 单段不超过 15 MB。

## 5. 异步回调

创建任务时传入：

```json
{
  "model": "minimax-h3",
  "content": [
    {
      "type": "text",
      "text": "提示词"
    },
    {
      "type": "image_url",
      "image_url": "https://example.com/reference.png"
    }
  ],
  "duration": 5,
  "ratio": "9:16",
  "callback_url": "https://example.com/webhook/dayan"
}
```

### 成功回调

```http
POST /webhook/dayan
X-Dayan-Event: task.succeeded
X-Dayan-Timestamp: 1780643099000
X-Dayan-Signature: sha256=...
X-Dayan-Key-Prefix: sk_abcd1234
```

```json
{
  "event": "task.succeeded",
  "task_id": "task_xxx",
  "status": "succeeded",
  "model": "minimax-h3",
  "duration": 5,
  "resolution": "720p",
  "content": {
    "video_url": "https://api.dayan.design/media/video/task_xxx"
  },
  "cost": 2.5,
  "usage": {
    "total_seconds": 5,
    "input_seconds": 0,
    "output_seconds": 5,
    "input_image_count": 1
  },
  "created_at": "2026-06-05T07:01:21.000Z",
  "updated_at": "2026-06-05T07:04:59.000Z"
}
```

### Node.js 验签

```js
const crypto = require('crypto')
const express = require('express')
const app = express()

const SECRET = process.env.DAYAN_WEBHOOK_SECRET

app.post('/webhook/dayan', express.raw({ type: () => true }), (req, res) => {
  const raw = req.body.toString('utf8')
  const timestamp = req.header('X-Dayan-Timestamp')
  const signature = req.header('X-Dayan-Signature')

  const expected = 'sha256=' + crypto
    .createHmac('sha256', SECRET)
    .update(timestamp + '.' + raw)
    .digest('hex')

  const valid = signature && crypto.timingSafeEqual(
    Buffer.from(signature),
    Buffer.from(expected)
  )

  if (!valid) return res.status(401).end()

  const event = JSON.parse(raw)
  // 按 event.task_id 去重
  // 处理 event.status、event.content.video_url、event.cost

  return res.status(200).end()
})
```

注意：

- 验签必须使用原始 HTTP 请求体。
- 签名原文为 `timestamp + "." + rawBody`。
- 按 `task_id` 实现幂等去重。
- 成功处理后必须返回 2xx，否则服务端会重试。

## 6. 建议的轮询实现

```js
async function waitForDayanTask(taskId, apiKey) {
  const endpoint =
    `https://api.dayan.design/api/v3/contents/generations/tasks/${taskId}`

  while (true) {
    const response = await fetch(endpoint, {
      headers: { Authorization: `Bearer ${apiKey}` }
    })

    if (!response.ok) {
      throw new Error(`Query failed: ${response.status} ${await response.text()}`)
    }

    const task = await response.json()

    if (task.status === 'succeeded') return task
    if (task.status === 'failed') throw new Error(JSON.stringify(task))

    await new Promise(resolve => setTimeout(resolve, 20_000))
  }
}
```

## 7. 常见错误

### 余额不足

```json
{
  "code": "credit_limit_exceeded",
  "message": "可用余额或信控额度不足"
}
```

此时任务不会被创建，也不会产生最终扣费。

## 8. 计费说明

- 只按成功任务的输出时长计费。
- 参考图片、视频和音频不另行计费。
- 以成功响应中的 `cost` 为最终实际扣费依据。
- 创建任务和运行中响应不返回最终扣费。

> 文档中的 Key、URL 和任务 ID 均为示例，不包含真实密钥。
