import http from "node:http";
import { createHash, randomUUID } from "node:crypto";
import { Agent } from "@earendil-works/pi-agent-core";
import { streamSimple } from "@earendil-works/pi-ai/api/openai-completions";
import { jsonrepair } from "jsonrepair";
import { Type } from "typebox";
import { Agent as UndiciAgent } from "undici";

const PORT = Number(process.env.PORT || 8090);
const INTERNAL_TOKEN = process.env.PI_RUNTIME_TOKEN || "heyi-pi-runtime-dev-token";
const IMAGE_FETCH_DISPATCHER = new UndiciAgent({ connect: { timeout: 60_000 } });
const IMAGE_RESPONSE_TIMEOUT_MS = Math.max(
  180_000,
  Number.parseInt(process.env.IMAGE_RESPONSE_TIMEOUT_MS || "900000", 10) || 900_000
);
const sessions = new Map();
const automationSessions = new Map();
setInterval(() => {
  const expiresBefore = Date.now() - 24 * 60 * 60 * 1000;
  for (const [sessionId, runtime] of automationSessions) {
    if (runtime.lastUsedAt < expiresBefore && !runtime.agent.state.isStreaming) {
      automationSessions.delete(sessionId);
    }
  }
}, 60 * 60 * 1000).unref();

const SYSTEM_PROMPT = `你是赫逸智能化平台的自动化调度助手。
你的职责是理解用户的自然语言，并把需要在当前电脑执行的任务转成赫逸自动化助手已授权的指令。
  当前已接入三类能力：飞跨店铺浏览器、BrowserAct Google 账号池，以及平台图片模型。Google 账号池不仅能启动和进入网址，也能继续执行页面内的观察、点击和等待任务。
不允许猜测店铺名称；用户没有提供明确店铺名称时，先询问名称，不要调用打开工具。
启动 Google 账号池不需要用户列出账号，必须使用助手本机已配置的账号。
关闭 Google 账号时必须有唯一且明确的邮箱、账号名、账号 ID 或 sessionId；不明确时先询问，不得猜测。
不允许生成任意代码、运行任意系统命令、付款、删除业务数据或绕过登录验证。
当用户要求测试助手、检查飞跨、查看飞跨连接或统计本机店铺时，调用 inspect_feikua_environment。
当用户明确要求打开或启动某个飞跨店铺时，调用 open_feikua_store。
当用户要求启动、补齐或检查 Google 账号席位时，调用 start_google_account_pool；用户明确说了账号数量或目标网址时必须原样传入，不得改成默认值。用户明确指定唯一邮箱、账号名、账号 ID 或 sessionId 时，必须把该标识原样放入 account，并把 count 设为 1；用户点名多个账号时，必须把每个标识原样放入 accounts，并把 count 设为账号数；不得启动未被点名的账号。用户只说数量而没有点名时，按 count 启动对应数量；只有明确说全部、所有或每个账号时才启动全部已配置账号。
当用户明确要求关闭某一个 Google 账号席位时，优先调用 close_google_account。
当用户提供的是包含唯一账号标识的完整关闭指令时，也可调用 send_google_account_pool_instruction，不得用它执行关闭以外的动作。
  当用户要求在已打开的 Google 账号页面内继续点击标签、按钮或菜单时，调用 execute_google_account_page_task，不得回复“需要用户自行点击”。
  当用户要求根据文字生成图片时，调用 generate_image，mode 使用 generate；当本轮带有参考图且用户要求修改、重绘、延展或保持参考图生成时，mode 使用 edit。没有参考图时不得声称完成图生图。
  图片工具没有成功返回生成结果前，不得声称图片已经生成。每次只生成一张以控制成本；需要多张时让用户逐次确认。
  图片工具返回供应商错误时不要自动重复生图，直接说明具体错误，避免产生重复费用。
用户同时要求“启动/进入网址”和“页面内点击”时，只调用一次 start_google_account_pool，并把页面目标放入 afterStartInstruction；这样本机助手必须先完成登录恢复和账号池启动，成功后才执行页面任务，禁止把两个阶段拆成互不等待的命令。用户说“这些账号、全部账号、每个账号”或未指定唯一账号时，对当前全部活跃账号执行；只有明确给出唯一账号时才限定单个账号。
页面任务只传递用户明确提出的目标，不添加付款、发布、删除、提交表单等用户没有要求的动作。
工具返回“已加入本机执行队列”后，明确告诉用户任务已经下发，结果会由页面的执行记录回传。
如果用户只是询问功能、配置或模型，不要调用工具，直接简洁回答。`;

const AUTOMATION_PLAN_PROMPT = `你是赫逸智能化平台的自动化语义规划器。
你的工作不是匹配固定关键词，而是理解用户在当前 Skill 场景中的真实意图，再把不同口语、简称、语序和省略表达归一为脚本能够执行的明确参数。
输入中会提供 userRequirement、currentParameters 和 skillDefinition。skillDefinition.executionParameters 描述当前执行器真正支持的语义能力；只能使用其中声明的参数。
只返回一个 JSON 对象，不要使用 Markdown，不要输出 JSON 之外的文字。
返回格式：
{
  "understanding": {
    "target": "用户要处理的对象",
    "scope": "全部、部分、指定对象或指定数量",
    "filters": ["从用户语义中理解出的筛选条件"]
  },
  "summary": "使用明确对象、范围、数量和筛选条件描述的一句话执行计划",
  "parameterOverrides": {},
  "assumptions": [],
  "warnings": [],
  "confidence": 0.0
}
规则：
1. 先结合 Skill 描述和执行参数含义理解语境，再提取对象、范围、数量、平台、店铺和筛选条件；不要要求用户必须使用某个固定句式。
2. 识别同义表达、口语、倒装和省略。例如“都跑一下”“全部做掉”“一个别漏”都表示符合筛选条件的对象不限制数量；“挑三家”“先弄3个”“只走三个”都表示最多三个任务。
3. 区分“任务总数”和“同时执行数”。用户说处理几家店铺通常是任务总数；只有明确说“同时、并行、并发”时才修改并发数。
4. 筛选条件必须落到 skillDefinition.executionParameters 声明的参数。对于月账单 Skill，用户表达“只处理工作簿中备注/标记/说明属于官方仓的店铺”等相同语义时，应归一为 officialWarehouseOnly=true；表达全部处理时 storeLimit=0。
5. parameterOverrides 只返回用户本轮要求明确改变或可以高置信推断的参数；其余参数不要返回，保留 currentParameters。
6. 用户没有明确提供完整店铺名称时，不得猜测店铺名；不得凭空增加筛选条件。
7. 否定表达优先。例如“不要只做官方仓”不能归一为 officialWarehouseOnly=true。
8. 不得修改文件、日期等用户没有要求改变的参数。
9. confidence 范围 0-1。若存在不影响执行的轻微歧义，写入 assumptions；若无法安全确定关键范围，写入 warnings，不要编造。
10. 枚举参数必须输出 skillDefinition.executionParameters 声明的机器值。平台口语“虾皮”归一为 shopee，“抖音/TikTok”归一为 tiktok，“来赞达/Lazada”归一为 lazada，“全部平台”归一为 all。
11. summary 必须复述模型最终理解的实际执行范围，方便用户确认，而不是照抄原话。`;

const AUTOMATION_DIAGNOSIS_PROMPT = `你是赫逸智能化平台的自动化运行监督员。
根据任务计划、脚本进度、错误和最近事件持续判断运行状态，并优先让任务安全地继续完成。
当 signalKind=RECOVERY 时，你必须选择一个受控恢复动作；不要建议修改或执行任意代码。
当 signalKind=CHECKPOINT_* 时复核执行是否符合计划；当 signalKind=SUCCESS 时总结结果。
当 signalKind 以 UNIT_RESULT_ 开头时，必须分析 triggerPayload 中的单个执行单元结果。执行单元可以是店铺、商品、账号、文件或其他业务对象；根据 status、terminalCode、message、files 和 recoveryAttempts 判断，不要只看进程退出码。
只返回一个 JSON 对象，不要使用 Markdown，不要输出 JSON 之外的文字。
返回格式：
{
  "category": "页面|登录|网络|文件|资源|脚本|业务规则|未知",
  "summary": "简短诊断",
  "likelyCause": "最可能原因",
  "recommendedAction": "下一步建议",
  "action": "RETRY_RESUME|RETRY_DELAY|REDUCE_CONCURRENCY|FAIL",
  "delaySeconds": 2,
  "maxConcurrency": 1,
  "canRetry": true,
  "needsHuman": false,
  "userMessage": "给普通员工看的说明",
  "unitId": "执行单元 ID，仅 UNIT_RESULT 时填写",
  "unitName": "执行单元名称，仅 UNIT_RESULT 时填写",
  "businessStatus": "成功|无数据|待人工|失败",
  "resultDescription": "业务人员最关注的实际结果",
  "outputDescription": "已产生的文件或业务产物"
}
规则：
1. 普通页面波动、元素未就绪、网络超时优先 RETRY_RESUME 或 RETRY_DELAY。
2. 资源不足、并发会话冲突优先 REDUCE_CONCURRENCY，maxConcurrency 范围 1-5。
3. 不要轻易失败。只有达到输入中的 maxAttempts、输入文件损坏、权限不足、业务平台明确拒绝或确实必须人工处理时才选择 FAIL。
4. 不要覆盖 Skill 已有的页面处理逻辑；仅依据 Skill 明确返回的成功或失败做恢复决策。
5. Skill 的 terminalCode、状态契约和踩坑结果优先于泛化猜测；completed/no_data 可以是有效业务结果，manual_required 必须说明业务人员下一步做什么。
6. UNIT_RESULT 分析的 userMessage 必须以业务结果为主：对象名称、是否成功、产出了什么；失败或待人工时说明具体原因和下一步。不要堆砌技术日志。
7. 不要声称已经执行恢复动作，平台会校验并执行你选择的受控动作。`;

const AUTOMATION_RESULT_PROMPT = `你是赫逸智能化平台的业务结果分析员。
你会收到一个 Skill 的单个执行单元结果。执行单元可能是店铺、商品、账号、文件或其他业务对象。
必须结合 triggerPayload 的 status、outcome、terminalCode、message、files、recoveryAttempts 和 contractHints 判断实际业务结果；Skill 的结果契约和踩坑说明优先，不能只看进程退出码。
只返回一个 JSON 对象，不要使用 Markdown，不要输出 JSON 之外的文字。
返回格式：
{
  "unitId": "原样复制执行单元 ID",
  "unitName": "原样复制执行单元名称",
  "businessStatus": "成功|无数据|待人工|失败",
  "resultDescription": "面向业务人员说明实际完成了什么，或为什么没完成",
  "outputDescription": "已确认产出的文件或结果；没有则说明未产出",
  "nextAction": "成功时留空；待人工或失败时给出明确下一步",
  "userMessage": "对象名称 + 业务状态 + 结果/原因 + 必要的下一步",
  "confidence": 0.0
}
规则：
1. completed 且结果契约确认产物有效时为成功；no_data 是有效结果，应标为无数据而不是失败。
2. manual_required 标为待人工，并根据 terminalCode/message 给出业务人员可执行的下一步。
3. failed/unsupported 标为失败，必须说清已自动尝试的次数、最终原因和是否产生文件。
4. 不输出大段技术日志，不暴露密钥、密码或内部路径；文件只描述数量和类型。
5. 不得把“脚本进程结束”表述成“所有业务对象成功”。`;

const VIDEO_SCRIPT_ANALYSIS_PROMPT = `你是赫逸智能化平台的视频脚本分析 Pi Agent。
你会收到按时间顺序从同一条视频均匀提取的关键帧，以及视频时长、目标市场和运营补充要求。
你还会收到 cameraMotionEvidence：平台通过密集抽帧和背景特征近似光流计算出的分段运镜证据，包含固定、横移、纵移、推近/拉远、抖动、置信度和位移量。
你的任务是还原视频的叙事结构，生成可编辑的中文分镜和后续图片生成可使用的结构化提示。

只返回一个 JSON 对象，不要使用 Markdown，不要输出 JSON 之外的文字。返回格式：
{
  "title": "脚本名称",
  "summary": "视频内容和转化逻辑概述",
  "globalVisualTone": "色系｜光影｜氛围｜摄影风格",
  "durationSeconds": 15.0,
  "shots": [
    {
      "sequence": 1,
      "stage": "HOOK|SOLUTION|TRUST|CTA|CONTENT",
      "title": "分镜标题",
      "startSecond": 0.0,
      "endSecond": 2.0,
      "durationSeconds": 2.0,
      "sceneDescription": "仅写人物数量、动作、环境、构图及画面变化，不写原人物和原商品特征",
      "motionDescription": "只写可观察动作：起始姿态→肢体路径→结束姿态，并注明脚步、转身角度和手臂高度",
      "motionIntensity": "静止|微幅|小幅|中幅|大幅",
      "narration": "没有可靠音频转写时必须留空，禁止抄录画面字幕",
      "shotSize": "景别",
      "cameraMovement": "运镜",
      "emotion": "人物情绪和画面氛围",
      "imagePrompt": {
        "prompt": "可直接用于图片模型的完整中文提示词",
        "negativePrompt": "需要避免的画面问题",
        "aspectRatio": "9:16"
      }
    }
  ]
}

规则：
1. 关键帧按时间顺序提供，必须结合相邻帧推断镜头变化，不得把每一帧机械地当成一个分镜。
2. 本平台当前生产模板固定需要 9 个分镜。必须恰好输出 9 个 shots，不能少、不能多；即使原视频镜头较少，也要按动作变化、构图变化或叙事节拍细分为 9 段，但不得虚构画面中不存在的事实。
3. 时间段必须连续、递增且不超过 durationSeconds；允许合理估算，但不要伪造精确到毫秒的事实。
4. 当前脚本拆解的目标是提取可复用的动作模板，而不是复制原视频的人或商品。title、summary、globalVisualTone、sceneDescription、motionDescription、emotion 和 imagePrompt 中一律不得写入原人物的身份、年龄、脸型、五官、肤色、发型、妆容、饰品、上装、下装、鞋子或具体穿搭搭配。
5. 一律不得写入原视频商品的类别、品牌、颜色、版型、材质、领口、袖型、印花、文字、图案、Logo、卖点或搭配信息；只能使用“当前任务商品”“展示商品正面”“手靠近商品区域”等中性占位表达。后续生产会以用户在 AI 视频任务中上传的商品图作为唯一商品真值，脚本不得与其竞争。
6. 画面中的字幕、贴纸、角标、字母、数字、营销文案、标题、水印和商品印花文字都不是脚本内容，禁止识别、转写、翻译或放进任意输出字段。当前没有可靠音频转写，所有 narration 必须为空字符串，不得用画面文字冒充口播。
7. 9 个 shots 是同一个视频时间轴上的连续动作关键帧，不是 9 张独立摆拍。相邻分镜必须满足“上一镜结束姿态 = 下一镜开始姿态”；每个 sceneDescription 都要明确写出起始姿态、动作过程、结束姿态以及与上一镜的衔接。
8. 相邻分镜最多推进一个主要动作。原视频动作较少时，应把同一个真实动作拆成起势、中间态和到位状态来补足 9 镜，不得凭空添加转身、走动、抬手或换姿势。
9. imagePrompt.prompt 必须写成可连续插值的动作关键帧描述：仅固定人物数量、机位、焦段、背景、光线和地面位置，并使用“当前任务人物”“当前任务商品”等中性占位词；只描述本格相对上一格发生的小幅动作增量，不得复述原人物或原商品视觉信息。
10. 动作必须符合人体骨骼、关节角度、重心、碰撞和惯性；不得规划肢体穿过身体/衣服/家具、反关节、脚底滑动、手指融合、身体扭曲或突然位移。原视频本身疑似异常时采用最近的正常可达姿态，不复制畸变。
11. 识别开场吸引、问题呈现、解决方案、信任证明和行动引导；视频没有某阶段时不要强行编造，可继续使用 CONTENT。9 个分镜应完整覆盖原视频时间轴，不得遗漏尾段。
12. 每个分镜的 cameraMovement 必须优先采用与该时间段重叠的 cameraMotionEvidence。高置信度证据判定固定机位时不得编造推拉摇移；判定平移、推拉或抖动时应结合关键帧用中文准确表述。证据缺失或置信度低时才使用视觉判断。
13. title 必须是画面中真实发生的物理动作短语，例如“右手缓慢抬到胸前”，不得使用“卖点开场、产品展示、氛围停顿”等营销概念代替动作。
14. motionDescription 专门服务于后续图生视频引导，必须依据相邻密集抽帧写清：本段开始时身体姿态、头/肩/手臂/髋/腿的实际运动路径、转身大致角度、双脚是否移动、结束姿态。只写画面可确认的运动，不重复人物外貌、原服装、原商品、画面文字、商品卖点或情绪修辞。
15. motionIntensity 必须根据相邻抽帧的实际像素变化选择“静止、微幅、小幅、中幅、大幅”之一。原地呼吸、低头、歪头和短距离抬手通常为微幅或小幅；不得因为分镜被拆分而提高动作幅度。
16. 必须先跨全部抽帧确认画面中的实际人物数量和人物运动范围。原视频始终只有一人时，所有分镜只描述这一人的连续动作，禁止把相邻时刻误写成第二人物、并排行走或多人互动；不得把画面上的商品印花、背景图案和字幕当成独立主体。
17. 输出必须是合法 JSON，所有字符串使用中文，不要加入注释。`;

const AUTOMATION_AGENT_PROMPT = `你是赫逸智能化平台中负责一条自动化任务全生命周期的 Pi Agent。
你会持续收到同一任务的计划、脚本输出、检查点、页面异常、恢复结果和最终结果。
你必须结合此前所有消息判断下一步，并调用一个工具作出决定：
- 正常运行或检查点健康：continue_automation
- 可恢复异常：recover_automation
- 已成功完成：finish_automation
- 达到最大恢复次数或确实不可恢复：fail_automation
当日志不足以判断时，可使用浏览器工具执行“观察 → 操作 → 重新观察 → 验证”的完整闭环。
你可以导航、点击、填写、选择、按键、悬停、滚动、前进后退、刷新、等待和读取页面内容。
当文字状态不足以定位目标、页面疑似画布/图标界面、动作失败或连续没有进展时，调用 capture_browser_screenshot 进行视觉复核。需要确认异步数据、接口错误或页面内容尚未渲染时，可先调用 inspect_browser_network，再按 requestId 调用 read_browser_network_request。不得使用网络结果伪造页面已完成，网络和页面证据应相互印证。
优先完成任务，不要因为一次页面波动、元素未就绪、网络超时就判定失败。
脚本支持断点恢复；资源或会话冲突时可降低并发。
不得生成、修改或执行任意代码；现有 Skill 内部业务逻辑保持原样。
当 context.commandType=GOOGLE_ACCOUNT_PAGE_TASK 时，browserToolResult 是当前账号最近一次真实 BrowserAct 执行回执，errorContext.actionHistory 是已经由本机实际执行成功的动作记录。你必须围绕 input.instruction 完成页面任务：先读取当前 state，使用其中的临时元素编号操作；任何点击、输入、选择、按键、滚动、导航或页面变化后都必须重新观察，绝不能复用旧编号。调用工具仅表示请求本机执行，不表示动作已经成功；只有下一轮 browserToolResult.ok=true 才是执行成功证据。
禁止声称自己点击、输入、跳转或完成了某个动作，除非 actionHistory 中存在对应的成功回执，并且动作后的最新页面状态能够证明目标已完成。交互任务没有成功动作回执时不得调用 finish_automation；若目标在开始时就已满足，也必须在 completionEvidence 中准确说明证据。evidenceQuotes 必须逐字复制当前页面 title、url 或可见文本中的短句，不能概括或编造；如果只能从截图判断，必须先在动作后调用 capture_browser_screenshot，再使用 VISUAL_SCREENSHOT。不要因为一次元素未出现就失败，应先滚动、等待、重新观察或选择其他明确元素。不同 contextKey 代表不同账号，不得拿上一个账号的结果代替当前账号复核。
当 context.taskMode=READ_ONLY_ANALYSIS 时，这是只读页面分析任务：禁止点击、输入或改变页面。browserToolResult 已包含当前页面文本和元素；信息足够时必须在第一轮直接调用 finish_automation，不得用 continue_automation 重复观察。只有页面尚未稳定或内容明显为空时才允许等待后再观察一次。finish_automation 必须填写 completionEvidence 和 analysisResult，其中 overview 是页面概览，keyPoints 是用户关心的页面信息，warnings 是已观察到的异常，conclusion 是最终结论；所有内容必须来自当前页面证据。
每一轮只能调用一个决策工具。`;

const AUTOMATION_VERIFICATION_RULES = `
补充硬性规则：
1. 你负责复核信息和辅助恢复，不得猜测、编造或擅自改变事实。
2. 必须同时核对用户原始需求、执行参数、对象是否属于目标范围、终态代码和已验证存在的文件。
3. EXECUTION_UNIT_UPDATE 是单次尝试的临时状态，不是最终结果；应优先继续恢复，不能提前宣布失败。
4. EXECUTION_UNIT_RESULT 才是恢复结束后的最终候选结果；范围外对象不得计入统计。
5. 只有 verifiedFileCount 和业务所需文件类型足以证明产出完整时，才可确认成功；计划路径不是成功证据。
6. 遇到可恢复的页面、网络、会话或元素异常时，应使用受控浏览器工具协助流程继续。
7. 只有平台明确拒绝、必要资料缺失且无法补齐、文件证据仍不完整，或恢复上限确实耗尽时，才输出最终错误。
8. 未完成时必须简洁说明具体原因和下一步，不得堆砌内部日志、文件路径或重复信息。
`;

function json(response, status, body) {
  const payload = JSON.stringify(body);
  response.writeHead(status, {
    "Content-Type": "application/json; charset=utf-8",
    "Content-Length": Buffer.byteLength(payload)
  });
  response.end(payload);
}

function readBody(request) {
  return new Promise((resolve, reject) => {
    let content = "";
    request.setEncoding("utf8");
    request.on("data", (chunk) => {
      content += chunk;
      if (content.length > 35_000_000) {
        request.destroy(new Error("请求内容过大"));
      }
    });
    request.on("end", () => {
      try {
        resolve(content ? JSON.parse(content) : {});
      } catch {
        reject(new Error("请求不是有效 JSON"));
      }
    });
    request.on("error", reject);
  });
}

function modelFrom(config) {
  const baseUrl = String(config.baseUrl || "").trim().replace(/\/+$/, "");
  const modelId = String(config.modelId || "").trim();
  if (!/^https?:\/\//.test(baseUrl)) throw new Error("模型 API 地址无效");
  if (!modelId) throw new Error("模型 ID 不能为空");
  return {
    id: modelId,
    name: modelId,
    api: "openai-completions",
    provider: "heyi-custom",
    baseUrl,
    reasoning: false,
    input: ["text", "image"],
    cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
    contextWindow: 128000,
    maxTokens: 8192,
    compat: {
      supportsStore: false,
      supportsDeveloperRole: false,
      supportsReasoningEffort: false,
      supportsUsageInStreaming: true
    }
  };
}

function modelSignature(config = {}) {
  const baseUrl = String(config.baseUrl || "").trim().replace(/\/+$/, "");
  const modelId = String(config.modelId || "").trim();
  const apiFormat = String(config.apiFormat || config.api || "openai-completions").trim();
  const keyDigest = createHash("sha256")
    .update(String(config.apiKey || ""), "utf8")
    .digest("hex");
  return `${baseUrl}|${modelId}|${apiFormat}|${keyDigest}`;
}

function messageText(message) {
  if (!message || message.role !== "assistant") return "";
  return (message.content || [])
    .filter((block) => block.type === "text")
    .map((block) => block.text)
    .join("")
    .trim();
}

function emptyTokenUsage() {
  return {
    inputTokens: 0,
    outputTokens: 0,
    cacheReadTokens: 0,
    cacheWriteTokens: 0,
    reasoningTokens: 0,
    totalTokens: 0,
    modelCalls: 0,
    reported: false
  };
}

function tokenUsageFromMessages(messages = []) {
  return messages.reduce((total, message) => {
    if (message?.role !== "assistant" || !message.usage) return total;
    const usage = message.usage;
    total.inputTokens += Number(usage.input || 0);
    total.outputTokens += Number(usage.output || 0);
    total.cacheReadTokens += Number(usage.cacheRead || 0);
    total.cacheWriteTokens += Number(usage.cacheWrite || 0);
    total.reasoningTokens += Number(usage.reasoning || 0);
    total.totalTokens += Number(
      usage.totalTokens || Number(usage.input || 0) + Number(usage.output || 0)
    );
    total.modelCalls += 1;
    if (Number(usage.totalTokens || 0) > 0 || Number(usage.input || 0) > 0
        || Number(usage.output || 0) > 0) total.reported = true;
    return total;
  }, emptyTokenUsage());
}

function addTokenUsage(target, value) {
  for (const key of [
    "inputTokens", "outputTokens", "cacheReadTokens", "cacheWriteTokens",
    "reasoningTokens", "totalTokens", "modelCalls"
  ]) target[key] = Number(target[key] || 0) + Number(value?.[key] || 0);
  target.reported = Boolean(target.reported || value?.reported);
  return target;
}

function imageApiUsage(value = {}) {
  const source = value?.usage || {};
  const inputTokens = Number(
    source.input_tokens ?? source.inputTokens ?? source.prompt_tokens ?? 0
  );
  const outputTokens = Number(
    source.output_tokens ?? source.outputTokens ?? source.completion_tokens ?? 0
  );
  const totalTokens = Number(
    source.total_tokens ?? source.totalTokens ?? inputTokens + outputTokens
  );
  return {
    inputTokens,
    outputTokens,
    cacheReadTokens: Number(source.cached_tokens ?? source.cacheReadTokens ?? 0),
    cacheWriteTokens: 0,
    reasoningTokens: Number(source.reasoning_tokens ?? source.reasoningTokens ?? 0),
    totalTokens,
    modelCalls: 1,
    reported: totalTokens > 0 || inputTokens > 0 || outputTokens > 0
  };
}

function imageEndpoint(baseUrl, mode) {
  const normalized = String(baseUrl || "").trim().replace(/\/+$/, "");
  if (!/^https?:\/\//i.test(normalized)) throw new Error("图片模型 API 地址无效");
  const target = mode === "edit" ? "edits" : "generations";
  if (new RegExp(`/images/${target}$`, "i").test(normalized)) return normalized;
  if (/\/images\/(generations|edits)$/i.test(normalized)) {
    return normalized.replace(/\/images\/(generations|edits)$/i, `/images/${target}`);
  }
  const parsed = new URL(normalized);
  if (!parsed.pathname || parsed.pathname === "/") {
    return `${normalized}/v1/images/${target}`;
  }
  return `${normalized}/images/${target}`;
}

function imageMime(buffer, fallback = "image/png") {
  if (buffer.length >= 12 && buffer[0] === 0x89 && buffer[1] === 0x50) return "image/png";
  if (buffer.length >= 3 && buffer[0] === 0xff && buffer[1] === 0xd8) return "image/jpeg";
  if (buffer.length >= 12 && buffer.toString("ascii", 0, 4) === "RIFF"
      && buffer.toString("ascii", 8, 12) === "WEBP") return "image/webp";
  return String(fallback || "image/png").split(";")[0].trim().toLowerCase();
}

function extensionForMime(mimeType) {
  if (mimeType === "image/jpeg") return "jpg";
  if (mimeType === "image/webp") return "webp";
  return "png";
}

async function responseImageBytes(item, apiKey) {
  const encoded = String(item?.b64_json || item?.b64Json || item?.image_base64 || "");
  if (encoded) return Buffer.from(encoded.replace(/^data:image\/[^;]+;base64,/i, ""), "base64");
  const url = String(item?.url || "").trim();
  if (!/^https?:\/\//i.test(url)) throw new Error("图片模型没有返回图片数据");
  let result = await fetch(url, {
    dispatcher: IMAGE_FETCH_DISPATCHER,
    signal: AbortSignal.timeout(120_000)
  });
  if ((result.status === 401 || result.status === 403) && apiKey) {
    result = await fetch(url, {
      headers: { Authorization: `Bearer ${apiKey}` },
      dispatcher: IMAGE_FETCH_DISPATCHER,
      signal: AbortSignal.timeout(120_000)
    });
  }
  if (!result.ok) throw new Error(`生成图片下载失败（${result.status}）`);
  const declaredSize = Number(result.headers.get("content-length") || 0);
  if (declaredSize > 64 * 1024 * 1024) throw new Error("生成图片超过 64MB 限制");
  const bytes = Buffer.from(await result.arrayBuffer());
  if (bytes.length > 64 * 1024 * 1024) throw new Error("生成图片超过 64MB 限制");
  return bytes;
}

function imageFailureCode(error) {
  return String(error?.cause?.code || error?.code || "").trim();
}

function requestWasDefinitelyNotSent(error) {
  const code = imageFailureCode(error);
  if (/^(UND_ERR_CONNECT_TIMEOUT|ENOTFOUND|EAI_AGAIN|ECONNREFUSED|ENETUNREACH|EHOSTUNREACH)$/i.test(code)) {
    return true;
  }
  const details = `${String(error?.message || "")} ${String(error?.cause?.message || "")}`;
  return /^ECONNRESET$/i.test(code)
    && /before secure TLS connection was established/i.test(details);
}

function isExplicitFalse(value) {
  return value === false || String(value).toLowerCase() === "false";
}

function providerExplicitlyRejectedWithoutCharge(payload) {
  const candidates = [payload, payload?.error, payload?.meta, payload?.error?.meta].filter(Boolean);
  const notAccepted = candidates.some((item) =>
    isExplicitFalse(item.requestAccepted) || isExplicitFalse(item.request_accepted) || isExplicitFalse(item.accepted)
  );
  const notCharged = candidates.some((item) =>
    isExplicitFalse(item.charged) || isExplicitFalse(item.billed) || isExplicitFalse(item.costDeducted)
      || isExplicitFalse(item.cost_deducted)
  );
  return notAccepted && notCharged;
}

function annotateImageFailure(error, details) {
  Object.assign(error, details);
  return error;
}

async function requestImageModel(endpoint, headers, bodyFactory) {
  const requestId = randomUUID();
  const requestHeaders = { ...headers, "Idempotency-Key": requestId };
  let safeRetryCount = 0;

  while (true) {
    let result;
    try {
      result = await fetch(endpoint, {
        method: "POST",
        headers: requestHeaders,
        body: bodyFactory(),
        dispatcher: IMAGE_FETCH_DISPATCHER,
        signal: AbortSignal.timeout(IMAGE_RESPONSE_TIMEOUT_MS)
      });
    } catch (error) {
      const definitelyNotSent = requestWasDefinitelyNotSent(error);
      annotateImageFailure(error, {
        requestState: definitelyNotSent ? "NOT_SENT" : "ACCEPTED_OR_UNKNOWN",
        retrySafe: definitelyNotSent,
        safeRetryCount,
        failureCode: imageFailureCode(error)
      });
      if (definitelyNotSent && safeRetryCount < 1) {
        safeRetryCount += 1;
        await new Promise((resolve) => setTimeout(resolve, 1_000));
        continue;
      }
      error.safeRetryCount = safeRetryCount;
      throw error;
    }

    const responseText = await result.text();
    let payload;
    try {
      payload = responseText ? JSON.parse(responseText) : {};
    } catch {
      payload = {};
    }
    if (!result.ok) {
      const message = payload?.error?.message || payload?.message || responseText || `HTTP ${result.status}`;
      const retrySafe = providerExplicitlyRejectedWithoutCharge(payload);
      const error = annotateImageFailure(
        new Error(`图片模型调用失败：${String(message).slice(0, 400)}`),
        {
          requestState: "REJECTED",
          retrySafe,
          safeRetryCount,
          providerStatus: result.status
        }
      );
      if (retrySafe && safeRetryCount < 1) {
        safeRetryCount += 1;
        await new Promise((resolve) => setTimeout(resolve, 1_000));
        continue;
      }
      throw error;
    }
    return { payload, safeRetryCount };
  }
}

async function callImageModel(config, params, inputImages) {
  if (!config?.apiKey || !config?.modelId || !config?.baseUrl) {
    throw new Error("请先在模型接入中配置并启用图片模型");
  }
  if (config.apiFormat && config.apiFormat !== "OPENAI_COMPATIBLE") {
    throw new Error("当前图片模型不是 OpenAI 兼容协议");
  }
  const mode = params.mode === "edit" ? "edit" : "generate";
  if (mode === "edit" && !inputImages.length) {
    throw new Error("图生图需要先上传参考图片");
  }
  const prompt = String(params.prompt || "").trim();
  if (!prompt) throw new Error("生图描述不能为空");
  const size = String(params.size || "auto").trim();
  const quality = String(params.quality || "").trim();
  const inputFidelity = String(params.inputFidelity || "").trim();
  const headers = { Authorization: `Bearer ${config.apiKey}` };
  let bodyFactory;
  if (mode === "edit") {
    bodyFactory = () => {
      const form = new FormData();
      form.append("model", String(config.modelId));
      form.append("prompt", prompt);
      form.append("size", size);
      if (quality) form.append("quality", quality);
      if (inputFidelity) form.append("input_fidelity", inputFidelity);
      inputImages.forEach((input, index) => {
        const bytes = Buffer.from(String(input.dataBase64 || ""), "base64");
        const field = inputImages.length === 1 ? "image" : "image[]";
        form.append(
          field,
          new Blob([bytes], { type: String(input.mimeType || "image/png") }),
          String(input.name || `reference-${index + 1}.png`)
        );
      });
      return form;
    };
  } else {
    headers["Content-Type"] = "application/json";
    const jsonBody = JSON.stringify({ model: config.modelId, prompt, n: 1, size, ...(quality ? { quality } : {}) });
    bodyFactory = () => jsonBody;
  }
  const endpoint = imageEndpoint(config.baseUrl, mode);
  const { payload, safeRetryCount } = await requestImageModel(endpoint, headers, bodyFactory);
  const item = Array.isArray(payload.data) ? payload.data[0]
    : Array.isArray(payload.images) ? payload.images[0]
      : payload.output?.[0] || payload.image || null;
  let bytes;
  try {
    bytes = await responseImageBytes(item, config.apiKey);
  } catch (error) {
    throw annotateImageFailure(error, {
      requestState: "COMPLETED_RESPONSE_RECEIVED",
      retrySafe: false,
      safeRetryCount
    });
  }
  if (!bytes.length) throw new Error("图片模型返回了空图片");
  if (bytes.length > 64 * 1024 * 1024) throw new Error("生成图片超过 64MB 限制");
  const mimeType = imageMime(bytes, item?.mime_type || item?.mimeType);
  return {
    artifact: {
      name: `heyi-image-${Date.now()}.${extensionForMime(mimeType)}`,
      mimeType,
      dataBase64: bytes.toString("base64"),
      modelId: String(config.modelId),
      mode,
      prompt,
      revisedPrompt: String(item?.revised_prompt || item?.revisedPrompt || "")
    },
    usage: imageApiUsage(payload),
    requestMeta: {
      requestState: "COMPLETED",
      safeRetryCount
    }
  };
}

function publicErrorMessage(error) {
  const message = String(error?.message || "");
  const causeCode = String(error?.cause?.code || error?.code || "");
  if (error?.requestState === "NOT_SENT" || /UND_ERR_CONNECT_TIMEOUT|ENOTFOUND|EAI_AGAIN|ECONNREFUSED|ENETUNREACH|EHOSTUNREACH/i.test(causeCode)) {
    const retried = Number(error?.safeRetryCount || 0) > 0;
    return retried
      ? "图片模型请求未送达供应商；已安全重试 1 次仍未连接，请检查模型地址或稍后再试"
      : "图片模型请求未送达供应商，请检查模型地址或稍后再试";
  }
  if (/system_cpu_overloaded|status_code=503|503.*overload|service unavailable/i.test(message)) {
    return "模型服务当前繁忙；系统没有自动重试，请稍后手动重试";
  }
  if (/status_code=429|rate.?limit|too many requests/i.test(message)) {
    return "模型服务请求过于频繁，请稍后再试";
  }
  if (/timeout|timed out|ETIMEDOUT/i.test(message)) {
    return "模型服务响应超时，请稍后再试";
  }
  return message || "智能调度服务调用失败";
}

function createSession(sessionId, config) {
  const runtime = {
    id: sessionId,
    signature: modelSignature(config),
    pendingActions: [],
    trace: [],
    apiKey: String(config.apiKey || ""),
    tokenUsage: emptyTokenUsage(),
    imageModel: null,
    inputImages: [],
    pendingArtifacts: [],
    toolTurnUsage: emptyTokenUsage()
  };
  if (!runtime.apiKey) throw new Error("模型 API Key 不能为空");

  const inspectTool = {
    name: "inspect_feikua_environment",
    label: "检查飞跨环境",
    description: "向赫逸自动化助手下发只读检查，返回飞跨连接状态和可用店铺数量。",
    parameters: Type.Object({
      reason: Type.Optional(Type.String({ description: "执行这次检查的原因" }))
    }),
    executionMode: "sequential",
    execute: async (toolCallId, params) => {
      const action = {
        actionId: randomUUID(),
        commandType: "FEIKUA_INSPECT",
        payload: { reason: String(params.reason || "对话测试") }
      };
      runtime.pendingActions.push(action);
      return {
        content: [{
          type: "text",
          text: `只读检查已加入本机执行队列，动作编号 ${action.actionId}。等待赫逸自动化助手回传结果。`
        }],
        details: action
      };
    }
  };

  const openStoreTool = {
    name: "open_feikua_store",
    label: "打开飞跨店铺",
    description: "按用户提供的精确店铺名称，启动或复用对应的飞跨隔离浏览器。仅负责打开，不点击店铺页面内的业务按钮。",
    parameters: Type.Object({
      storeName: Type.String({
        minLength: 1,
        maxLength: 160,
        description: "用户明确提供的完整飞跨店铺名称，禁止猜测"
      })
    }),
    executionMode: "sequential",
    execute: async (toolCallId, params) => {
      const action = {
        actionId: randomUUID(),
        commandType: "FEIKUA_OPEN_STORE",
        payload: { storeName: String(params.storeName || "").trim() }
      };
      runtime.pendingActions.push(action);
      return {
        content: [{
          type: "text",
          text: `店铺“${action.payload.storeName}”的打开任务已加入本机执行队列，动作编号 ${action.actionId}。等待赫逸自动化助手回传结果。`
        }],
        details: action
      };
    }
  };

  const startGoogleAccountPoolTool = {
    name: "start_google_account_pool",
    label: "启动 Google 账号池",
    description: "让赫逸自动化助手使用本机已配置的 BrowserAct 环境启动或补齐 Google 账号席位，并直接进入用户指定页面。",
    parameters: Type.Object({
      count: Type.Optional(Type.Integer({
        minimum: 1,
        maximum: 10,
        description: "用户明确要求启动的账号数量；没有说时不传"
      })),
      targetUrl: Type.Optional(Type.String({
        minLength: 8,
        maxLength: 2000,
        description: "用户明确指定的 HTTP/HTTPS 目标网址；没有说时不传"
      })),
      account: Type.Optional(Type.String({
        minLength: 1,
        maxLength: 254,
        description: "用户明确指定的唯一邮箱、账号名、账号 ID 或 sessionId；没有指定唯一账号时不传"
      })),
      accounts: Type.Optional(Type.Array(Type.String({
        minLength: 1,
        maxLength: 254
      }), {
        minItems: 1,
        maxItems: 10,
        description: "用户明确点名多个账号时，按原顺序填写邮箱、账号名、账号 ID 或 sessionId；不得补充未点名账号"
      })),
      reason: Type.Optional(Type.String({ description: "启动账号池的原因" })),
      afterStartInstruction: Type.Optional(Type.String({
        minLength: 1,
        maxLength: 1000,
        description: "仅当用户在同一请求中还要求进入页面后继续点击/观察时填写，必须保留原始页面目标"
      }))
    }),
    executionMode: "sequential",
    execute: async (toolCallId, params) => {
      const accountSelectors = [
        ...(Array.isArray(params.accounts) ? params.accounts : []),
        ...(params.account ? [params.account] : [])
      ].map(value => String(value || "").trim()).filter(Boolean)
        .filter((value, index, items) =>
          items.findIndex(item => item.toLocaleLowerCase() === value.toLocaleLowerCase()) === index
        );
      const action = {
        actionId: randomUUID(),
        commandType: "GOOGLE_ACCOUNT_POOL_START",
        payload: {
          ...(accountSelectors.length
            ? {
                count: accountSelectors.length,
                accountSelectors,
                ...(accountSelectors.length === 1
                  ? { accountSelector: accountSelectors[0] }
                  : {})
              }
            : Number.isInteger(params.count) ? { count: params.count } : {}),
          ...(params.targetUrl ? { targetUrl: String(params.targetUrl).trim() } : {}),
          ...(params.afterStartInstruction
            ? { afterStartInstruction: String(params.afterStartInstruction).trim() }
            : {}),
          reason: String(params.reason || "启动本机 Google 账号池")
        }
      };
      runtime.pendingActions.push(action);
      return {
        content: [{
          type: "text",
          text: `Google 账号池启动任务已加入本机执行队列，动作编号 ${action.actionId}。等待赫逸自动化助手回传结果。`
        }],
        details: action
      };
    }
  };

  const closeGoogleAccountTool = {
    name: "close_google_account",
    label: "关闭 Google 账号席位",
    description: "按用户明确提供的邮箱、账号名、账号 ID 或 sessionId 关闭唯一席位，并由助手自动补位。",
    parameters: Type.Object({
      account: Type.String({
        minLength: 1,
        maxLength: 254,
        description: "用户明确提供的唯一账号标识，禁止猜测"
      })
    }),
    executionMode: "sequential",
    execute: async (toolCallId, params) => {
      const account = String(params.account || "").trim();
      const payload = account.includes("@") ? { email: account.toLowerCase() } : { accountName: account };
      const action = {
        actionId: randomUUID(),
        commandType: "GOOGLE_ACCOUNT_CLOSE",
        payload
      };
      runtime.pendingActions.push(action);
      return {
        content: [{
          type: "text",
          text: `Google 账号“${account}”的关闭补位任务已加入本机执行队列，动作编号 ${action.actionId}。`
        }],
        details: action
      };
    }
  };

  const googleAccountInstructionTool = {
    name: "send_google_account_pool_instruction",
    label: "下发 Google 账号池指令",
    description: "仅向本机助手传递包含唯一账号标识的明确关闭指令。",
    parameters: Type.Object({
      instruction: Type.String({
        minLength: 1,
        maxLength: 500,
        description: "用户的完整关闭指令，必须包含关闭/close 和唯一账号标识"
      })
    }),
    executionMode: "sequential",
    execute: async (toolCallId, params) => {
      const instruction = String(params.instruction || "").trim();
      const action = {
        actionId: randomUUID(),
        commandType: "GOOGLE_ACCOUNT_COMMAND",
        payload: { instruction }
      };
      runtime.pendingActions.push(action);
      return {
        content: [{
          type: "text",
          text: `Google 账号池指令已加入本机执行队列，动作编号 ${action.actionId}。`
        }],
        details: action
      };
    }
  };

  const googleAccountPageTaskTool = {
    name: "execute_google_account_page_task",
    label: "执行 Google 页面任务",
    description: "让本机助手在当前活跃的 Google 账号页面内继续完成用户明确要求的观察、点击标签/按钮/菜单或等待任务。任务由 Pi Agent 根据每次最新页面状态逐步执行并复核。",
    parameters: Type.Object({
      instruction: Type.String({
        minLength: 1,
        maxLength: 1000,
        description: "用户明确要求的页面内操作目标，保留对象、数量和完成条件，不得扩写未授权动作"
      }),
      account: Type.Optional(Type.String({
        minLength: 1,
        maxLength: 254,
        description: "仅当用户明确指定唯一账号时填写邮箱、账号名、账号 ID 或 sessionId"
      })),
      allActiveAccounts: Type.Boolean({
        description: "用户要求全部/每个账号，或没有指定唯一账号时为 true"
      })
    }),
    executionMode: "sequential",
    execute: async (toolCallId, params) => {
      const action = {
        actionId: randomUUID(),
        commandType: "GOOGLE_ACCOUNT_PAGE_TASK",
        payload: {
          instruction: String(params.instruction || "").trim(),
          accountSelector: String(params.account || "").trim(),
          allActiveAccounts: params.allActiveAccounts !== false
        }
      };
      runtime.pendingActions.push(action);
      return {
        content: [{
          type: "text",
          text: `Google 页面自动化任务已加入本机执行队列，动作编号 ${action.actionId}。Pi Agent 会逐个账号观察、执行并回传结果。`
        }],
        details: action
      };
    }
  };

  const generateImageTool = {
    name: "generate_image",
    label: "生成图片",
    description: "调用平台已配置的图片模型执行文字生图或图生图。图生图使用本轮用户上传的参考图片。每次只生成一张。",
    parameters: Type.Object({
      mode: Type.String({
        description: "文字生图使用 generate；有参考图并要求修改或重绘时使用 edit"
      }),
      prompt: Type.String({
        minLength: 1,
        maxLength: 4000,
        description: "完整、明确的图片生成或编辑描述"
      }),
      size: Type.Optional(Type.String({
        maxLength: 32,
        description: "图片尺寸，例如 auto、1024x1024、1536x1024 或 1024x1536"
      })),
      quality: Type.Optional(Type.String({
        maxLength: 20,
        description: "仅在用户明确要求时传 low、medium、high、standard、hd 或 auto"
      }))
    }),
    executionMode: "sequential",
    execute: async (toolCallId, params) => {
      const result = await callImageModel(
        runtime.imageModel,
        params,
        runtime.inputImages
      );
      runtime.pendingArtifacts.push(result.artifact);
      addTokenUsage(runtime.toolTurnUsage, result.usage);
      return {
        content: [{
          type: "text",
          text: `${result.artifact.mode === "edit" ? "图生图" : "文字生图"}已完成，已生成 1 张图片。图片将由平台保存并显示给用户。`
        }],
        details: {
          modelId: result.artifact.modelId,
          mode: result.artifact.mode,
          mimeType: result.artifact.mimeType,
          name: result.artifact.name
        }
      };
    }
  };

  runtime.agent = new Agent({
    initialState: {
      systemPrompt: SYSTEM_PROMPT,
      model: modelFrom(config),
      thinkingLevel: "off",
      messages: [],
      tools: [
        inspectTool,
        openStoreTool,
        startGoogleAccountPoolTool,
        closeGoogleAccountTool,
        googleAccountInstructionTool,
        googleAccountPageTaskTool,
        generateImageTool
      ]
    },
    streamFn: (model, context, options) =>
      streamSimple(model, context, {
        ...options,
        apiKey: runtime.apiKey,
        maxTokens: 2048,
        timeoutMs: 90_000,
        maxRetries: 3,
        maxRetryDelayMs: 12_000
      }),
    convertToLlm: (messages) =>
      messages.filter((message) =>
        ["user", "assistant", "toolResult"].includes(message.role)
      )
  });

  runtime.agent.subscribe((event) => {
    if (event.type === "tool_execution_start") {
      const toolLabels = {
        inspect_feikua_environment: "检查飞跨环境",
        open_feikua_store: "打开飞跨店铺",
        start_google_account_pool: "启动 Google 账号池",
        close_google_account: "关闭 Google 账号席位",
        send_google_account_pool_instruction: "下发 Google 账号池指令",
        execute_google_account_page_task: "执行 Google 页面任务",
        generate_image: "生成图片"
      };
      runtime.trace.push({
        type: "tool",
        label: `智能调度已调用：${toolLabels[event.toolName] || event.toolName}`,
        toolName: event.toolName,
        at: Date.now()
      });
    }
    if (event.type === "tool_execution_end") {
      runtime.trace.push({
        type: event.isError ? "error" : "success",
        label: event.isError
          ? "工具调用失败"
          : event.toolName === "generate_image" ? "图片已生成" : "命令已生成",
        toolName: event.toolName,
        at: Date.now()
      });
    }
  });
  return runtime;
}

function createAutomationSession(sessionId, config) {
  const runtime = {
    id: sessionId,
    signature: modelSignature(config),
    apiKey: String(config.apiKey || ""),
    decisions: [],
    tail: Promise.resolve(),
    lastUsedAt: Date.now()
  };
  if (!runtime.apiKey) throw new Error("模型 API Key 不能为空");
  const decisionTool = (name, label, description, parameters, normalize) => ({
    name,
    label,
    description,
    parameters,
    executionMode: "sequential",
    execute: async (toolCallId, params) => {
      const decision = normalize(params || {});
      runtime.decisions.push(decision);
      return {
        content: [{ type: "text", text: `平台已接收决策：${decision.action}` }],
        details: decision,
        // Browser tools are executed on the employee's machine after this HTTP
        // response. Ending the Pi turn here prevents the model from requesting a
        // click and then hallucinating success before the real receipt arrives.
        terminate: true
      };
    }
  });
  const tools = [
    decisionTool(
      "observe_browser", "观察飞跨页面",
      "读取当前店铺页面、可交互元素并按需获取截图。",
      Type.Object({
        storeName: Type.Optional(Type.String()),
        includeScreenshot: Type.Optional(Type.Boolean())
      }),
      params => ({
        action: "BROWSER_OBSERVE",
        browserAction: {
          type: "OBSERVE",
          storeName: String(params.storeName || ""),
          includeScreenshot: Boolean(params.includeScreenshot)
        },
        userMessage: "Pi Agent 正在观察当前飞跨页面"
      })
    ),
    decisionTool(
      "navigate_browser", "导航到页面",
      "在当前账号浏览器会话中打开指定 HTTP/HTTPS 地址。",
      Type.Object({ url: Type.String({ maxLength: 2000 }) }),
      params => ({
        action: "BROWSER_NAVIGATE",
        browserAction: { type: "NAVIGATE", url: String(params.url || "").slice(0, 2000) },
        userMessage: "Pi Agent 正在打开目标页面"
      })
    ),
    decisionTool(
      "click_browser_element", "点击页面元素",
      "点击最近一次观察返回的元素引用。",
      Type.Object({ storeName: Type.Optional(Type.String()), elementRef: Type.String() }),
      params => ({
        action: "BROWSER_CLICK",
        browserAction: {
          type: "CLICK",
          storeName: String(params.storeName || ""),
          elementRef: String(params.elementRef || "")
        },
        userMessage: `Pi Agent 请求点击元素 ${String(params.elementRef || "")}`
      })
    ),
    decisionTool(
      "type_browser_element", "填写页面字段",
      "向最近一次观察返回的字段输入内容。",
      Type.Object({
        storeName: Type.Optional(Type.String()),
        elementRef: Type.String(),
        text: Type.String(),
        clearFirst: Type.Optional(Type.Boolean())
      }),
      params => ({
        action: "BROWSER_TYPE",
        browserAction: {
          type: "TYPE",
          storeName: String(params.storeName || ""),
          elementRef: String(params.elementRef || ""),
          text: String(params.text || "").slice(0, 2000),
          clearFirst: params.clearFirst !== false
        },
        userMessage: `Pi Agent 请求填写元素 ${String(params.elementRef || "")}`
      })
    ),
    decisionTool(
      "select_browser_option", "选择下拉选项",
      "在最近一次观察返回的下拉框中选择可见选项。",
      Type.Object({ elementRef: Type.String(), option: Type.String({ maxLength: 1000 }) }),
      params => ({
        action: "BROWSER_SELECT",
        browserAction: {
          type: "SELECT",
          elementRef: String(params.elementRef || ""),
          option: String(params.option || "").slice(0, 1000)
        },
        userMessage: `Pi Agent 请求选择元素 ${String(params.elementRef || "")}`
      })
    ),
    decisionTool(
      "press_browser_keys", "发送键盘按键",
      "向当前页面发送按键，例如 Enter、Escape、Tab 或 Control+A。",
      Type.Object({ keys: Type.String({ maxLength: 200 }) }),
      params => ({
        action: "BROWSER_KEYS",
        browserAction: { type: "KEYS", keys: String(params.keys || "").slice(0, 200) },
        userMessage: "Pi Agent 正在发送键盘操作"
      })
    ),
    decisionTool(
      "hover_browser_element", "悬停页面元素",
      "悬停最近一次观察返回的元素，用于展开菜单或显示提示。",
      Type.Object({ elementRef: Type.String() }),
      params => ({
        action: "BROWSER_HOVER",
        browserAction: { type: "HOVER", elementRef: String(params.elementRef || "") },
        userMessage: `Pi Agent 请求悬停元素 ${String(params.elementRef || "")}`
      })
    ),
    decisionTool(
      "scroll_browser", "滚动页面",
      "向上或向下滚动页面，以查找当前视口外的内容。",
      Type.Object({
        direction: Type.Union([Type.Literal("up"), Type.Literal("down")]),
        amount: Type.Optional(Type.Number({ minimum: 100, maximum: 5000 }))
      }),
      params => ({
        action: "BROWSER_SCROLL",
        browserAction: {
          type: "SCROLL",
          direction: params.direction === "up" ? "up" : "down",
          amount: Math.max(100, Math.min(5000, Number(params.amount || 700)))
        },
        userMessage: "Pi Agent 正在滚动页面查找目标"
      })
    ),
    decisionTool(
      "browser_history", "浏览器历史操作",
      "在当前会话中后退、前进或刷新。",
      Type.Object({ operation: Type.Union([
        Type.Literal("back"), Type.Literal("forward"), Type.Literal("reload")
      ]) }),
      params => ({
        action: "BROWSER_HISTORY",
        browserAction: { type: String(params.operation || "reload").toUpperCase() },
        userMessage: "Pi Agent 正在更新当前页面"
      })
    ),
    decisionTool(
      "read_browser_content", "读取页面内容",
      "读取页面标题、Markdown，或指定元素的文本和值。",
      Type.Object({
        contentType: Type.Union([
          Type.Literal("title"), Type.Literal("markdown"), Type.Literal("html"),
          Type.Literal("text"), Type.Literal("value")
        ]),
        elementRef: Type.Optional(Type.String())
      }),
      params => ({
        action: "BROWSER_READ",
        browserAction: {
          type: `GET_${String(params.contentType || "markdown").toUpperCase()}`,
          elementRef: String(params.elementRef || "")
        },
        userMessage: "Pi Agent 正在读取页面内容"
      })
    ),
    decisionTool(
      "capture_browser_screenshot", "观察页面截图",
      "获取当前页面截图并在下一轮作为视觉输入分析。",
      Type.Object({ fullPage: Type.Optional(Type.Boolean()) }),
      params => ({
        action: "BROWSER_SCREENSHOT",
        browserAction: { type: "SCREENSHOT", full: Boolean(params.fullPage) },
        userMessage: "Pi Agent 正在进行页面视觉复核"
      })
    ),
    decisionTool(
      "inspect_browser_network", "检查页面网络请求",
      "列出当前页面捕获到的网络请求，可按地址、资源类型、方法和状态筛选。",
      Type.Object({
        filter: Type.Optional(Type.String({ maxLength: 500 })),
        resourceTypes: Type.Optional(Type.String({ maxLength: 200 })),
        method: Type.Optional(Type.String({ maxLength: 20 })),
        status: Type.Optional(Type.String({ maxLength: 20 }))
      }),
      params => ({
        action: "BROWSER_NETWORK_LIST",
        browserAction: {
          type: "NETWORK_REQUESTS",
          filter: String(params.filter || "").slice(0, 500),
          resourceTypes: String(params.resourceTypes || "").slice(0, 200),
          method: String(params.method || "").slice(0, 20),
          status: String(params.status || "").slice(0, 20)
        },
        userMessage: "Pi Agent 正在检查页面数据请求"
      })
    ),
    decisionTool(
      "read_browser_network_request", "读取网络请求详情",
      "根据上一轮网络请求列表返回的 requestId 读取请求与响应详情。",
      Type.Object({ requestId: Type.String({ maxLength: 200 }) }),
      params => ({
        action: "BROWSER_NETWORK_READ",
        browserAction: {
          type: "NETWORK_REQUEST",
          requestId: String(params.requestId || "").slice(0, 200)
        },
        userMessage: "Pi Agent 正在读取页面接口结果"
      })
    ),
    decisionTool(
      "wait_browser", "等待页面变化",
      "等待短时间后重新观察页面。",
      Type.Object({ seconds: Type.Number({ minimum: 1, maximum: 15 }) }),
      params => ({
        action: "BROWSER_WAIT",
        browserAction: {
          type: "WAIT",
          seconds: Math.max(1, Math.min(15, Number(params.seconds || 2)))
        },
        userMessage: "Pi Agent 正在等待页面完成变化"
      })
    ),
    decisionTool(
      "continue_automation",
      "继续自动化",
      "当前执行健康，允许脚本继续运行。",
      Type.Object({
        summary: Type.String(),
        userMessage: Type.String()
      }),
      params => ({
        action: "CONTINUE",
        canRetry: true,
        needsHuman: false,
        summary: String(params.summary || "执行状态正常"),
        userMessage: String(params.userMessage || "模型复核通过，继续执行")
      })
    ),
    decisionTool(
      "recover_automation",
      "恢复自动化",
      "从断点恢复，可延迟重试或降低并发；不能执行任意代码。",
      Type.Object({
        action: Type.String({
          description: "RETRY_RESUME、RETRY_DELAY 或 REDUCE_CONCURRENCY"
        }),
        delaySeconds: Type.Optional(Type.Number({ minimum: 0, maximum: 30 })),
        maxConcurrency: Type.Optional(Type.Number({ minimum: 1, maximum: 5 })),
        likelyCause: Type.String(),
        userMessage: Type.String()
      }),
      params => ({
        action: ["RETRY_RESUME", "RETRY_DELAY", "REDUCE_CONCURRENCY"]
          .includes(String(params.action || "").toUpperCase())
          ? String(params.action).toUpperCase()
          : "RETRY_RESUME",
        delaySeconds: Math.max(0, Math.min(30, Number(params.delaySeconds || 2))),
        maxConcurrency: Math.max(1, Math.min(5, Number(params.maxConcurrency || 1))),
        canRetry: true,
        needsHuman: false,
        likelyCause: String(params.likelyCause || "运行环境短暂异常"),
        userMessage: String(params.userMessage || "模型正在执行断点恢复")
      })
    ),
    decisionTool(
      "finish_automation",
      "完成自动化",
      "确认任务结果完整并生成员工可读的总结。",
      Type.Object({
        summary: Type.String(),
        userMessage: Type.String(),
        completionEvidence: Type.String({
          description: "当前最新页面中能够证明任务确实完成的可见证据",
          minLength: 1,
          maxLength: 3000
        }),
        evidenceSource: Type.Union([
          Type.Literal("PAGE_TEXT"), Type.Literal("PAGE_URL"),
          Type.Literal("VISUAL_SCREENSHOT"), Type.Literal("NETWORK_AND_PAGE")
        ]),
        evidenceQuotes: Type.Array(Type.String({ maxLength: 500 }), {
          minItems: 1,
          maxItems: 8,
          description: "从当前页面逐字复制的短证据；视觉证据可填写截图中实际看到的短文字"
        }),
        analysisResult: Type.Optional(Type.Object({
          overview: Type.String({ description: "页面或任务结果概览" }),
          keyPoints: Type.Array(Type.String(), { maxItems: 20 }),
          warnings: Type.Array(Type.String(), { maxItems: 10 }),
          conclusion: Type.String({ description: "面向业务人员的最终结论" })
        }))
      }),
      params => ({
        action: "FINISH",
        canRetry: false,
        needsHuman: false,
        summary: String(params.summary || "自动化执行完成"),
        userMessage: String(params.userMessage || "任务已完成"),
        completionEvidence: String(params.completionEvidence || "").slice(0, 3000),
        evidenceSource: ["PAGE_TEXT", "PAGE_URL", "VISUAL_SCREENSHOT", "NETWORK_AND_PAGE"]
          .includes(String(params.evidenceSource || "").toUpperCase())
          ? String(params.evidenceSource).toUpperCase()
          : "PAGE_TEXT",
        evidenceQuotes: (Array.isArray(params.evidenceQuotes) ? params.evidenceQuotes : [])
          .map(value => String(value || "").trim().slice(0, 500))
          .filter(Boolean).slice(0, 8),
        analysisResult: params.analysisResult && typeof params.analysisResult === "object"
          ? {
              overview: String(params.analysisResult.overview || "").slice(0, 3000),
              keyPoints: (Array.isArray(params.analysisResult.keyPoints)
                ? params.analysisResult.keyPoints : [])
                .map(value => String(value || "").slice(0, 1000)).filter(Boolean).slice(0, 20),
              warnings: (Array.isArray(params.analysisResult.warnings)
                ? params.analysisResult.warnings : [])
                .map(value => String(value || "").slice(0, 1000)).filter(Boolean).slice(0, 10),
              conclusion: String(params.analysisResult.conclusion || "").slice(0, 3000)
            }
          : {}
      })
    ),
    decisionTool(
      "fail_automation",
      "确认无法恢复",
      "仅在达到恢复上限或确实需要人工处理时使用。",
      Type.Object({
        likelyCause: Type.String(),
        userMessage: Type.String(),
        needsHuman: Type.Boolean()
      }),
      params => ({
        action: "FAIL",
        canRetry: false,
        needsHuman: Boolean(params.needsHuman),
        likelyCause: String(params.likelyCause || "自动恢复条件不足"),
        userMessage: String(params.userMessage || "任务无法继续自动恢复")
      })
    )
  ];
  runtime.agent = new Agent({
    initialState: {
      systemPrompt: AUTOMATION_AGENT_PROMPT,
      model: modelFrom(config),
      thinkingLevel: "medium",
      messages: [],
      tools
    },
    streamFn: (model, context, options) =>
      streamSimple(model, context, {
        ...options,
        apiKey: runtime.apiKey,
        maxTokens: 3200,
        timeoutMs: 90_000,
        maxRetries: 3,
        maxRetryDelayMs: 12_000
      }),
    convertToLlm: messages =>
      messages.filter(message =>
        ["user", "assistant", "toolResult"].includes(message.role)
      )
  });
  return runtime;
}

function getAutomationSession(sessionId, config) {
  const signature = modelSignature(config);
  let runtime = automationSessions.get(sessionId);
  if (!runtime || runtime.signature !== signature) {
    runtime = createAutomationSession(sessionId, config);
    automationSessions.set(sessionId, runtime);
  }
  runtime.apiKey = String(config.apiKey || "");
  runtime.lastUsedAt = Date.now();
  return runtime;
}

function getSession(sessionId, config) {
  const signature = modelSignature(config);
  let runtime = sessions.get(sessionId);
  if (!runtime || runtime.signature !== signature) {
    runtime = createSession(sessionId, config);
    sessions.set(sessionId, runtime);
  } else {
    runtime.apiKey = String(config.apiKey || "");
  }
  return runtime;
}

async function handleChat(request, response) {
  if (request.headers["x-internal-token"] !== INTERNAL_TOKEN) {
    json(response, 401, { message: "智能调度服务认证失败" });
    return;
  }
  const body = await readBody(request);
  const sessionId = String(body.sessionId || "").trim();
  const prompt = String(body.prompt || "").trim();
  if (!sessionId || !prompt) {
    json(response, 400, { message: "sessionId 和 prompt 不能为空" });
    return;
  }
  const runtime = getSession(sessionId, body.model || {});
  runtime.imageModel = body.imageModel || null;
  runtime.inputImages = (Array.isArray(body.inputImages) ? body.inputImages : [])
    .filter(input => /^image\//i.test(String(input?.mimeType || "")))
    .slice(0, 1);
  if (runtime.agent.state.isStreaming) {
    json(response, 409, { message: "当前会话正在处理上一条消息" });
    return;
  }

  runtime.pendingActions = [];
  runtime.pendingArtifacts = [];
  runtime.toolTurnUsage = emptyTokenUsage();
  runtime.trace = [{
    type: "model",
    label: `正在调用 ${body.model.modelId}`,
    at: Date.now()
  }];
  const startedAt = Date.now();
  const messageCountBeforeTurn = runtime.agent.state.messages.length;
  const effectivePrompt = runtime.inputImages.length
    ? `${prompt}\n\n[平台上下文：用户本轮上传了 ${runtime.inputImages.length} 张参考图片；如用户要求基于图片修改，请调用 generate_image 并使用 edit 模式。]`
    : prompt;
  await runtime.agent.prompt(effectivePrompt);
  const messages = runtime.agent.state.messages;
  const turnUsage = tokenUsageFromMessages(messages.slice(messageCountBeforeTurn));
  addTokenUsage(turnUsage, runtime.toolTurnUsage);
  addTokenUsage(runtime.tokenUsage, turnUsage);
  const assistant = [...messages].reverse().find((message) => message.role === "assistant");
  if (!assistant || assistant.stopReason === "error") {
    throw new Error(assistant?.errorMessage || "模型没有返回有效回复");
  }
  runtime.trace.push({
    type: "success",
    label: "智能调度本轮完成",
    at: Date.now()
  });
  json(response, 200, {
    sessionId,
    reply: messageText(assistant) || "任务已处理。",
    actions: runtime.pendingActions,
    artifacts: runtime.pendingArtifacts,
    trace: runtime.trace,
    usage: {
      ...turnUsage,
      session: { ...runtime.tokenUsage }
    },
    latencyMs: Date.now() - startedAt,
    modelId: body.model.modelId
  });
}

function extractJsonObject(text) {
  const normalized = String(text || "").trim()
    .replace(/^```(?:json)?\s*/i, "")
    .replace(/\s*```$/, "");
  try {
    return JSON.parse(normalized);
  } catch {
    const start = normalized.indexOf("{");
    const end = normalized.lastIndexOf("}");
    if (start >= 0 && end > start) {
      const candidate = normalized.slice(start, end + 1);
      try {
        return JSON.parse(candidate);
      } catch {
        return JSON.parse(jsonrepair(candidate));
      }
    }
    throw new Error("模型没有返回有效的结构化结果");
  }
}

async function parseVideoScriptResult(runtimeAgent, initialReply) {
  try {
    return { result: extractJsonObject(initialReply), reply: initialReply, repairedByModel: false };
  } catch (firstError) {
    await runtimeAgent.prompt(`你上一轮返回的 JSON 无法通过解析：${String(firstError.message || firstError)}。
请根据同一段视频分析上下文修复上一轮结果。只输出一个完整、合法的 JSON 对象；不得使用 Markdown，不得遗漏 shots，也不得添加新事实。`);
    const repairAssistant = [...runtimeAgent.state.messages]
      .reverse()
      .find(message => message.role === "assistant");
    if (!repairAssistant || repairAssistant.stopReason === "error") {
      throw new Error(repairAssistant?.errorMessage || "模型 JSON 自动修复失败");
    }
    const repairedReply = messageText(repairAssistant);
    return {
      result: extractJsonObject(repairedReply),
      reply: repairedReply,
      repairedByModel: true
    };
  }
}

async function enforceVideoShotCount(runtimeAgent, parsed, desiredShotCount) {
  const currentShots = Array.isArray(parsed?.result?.shots) ? parsed.result.shots : [];
  if (currentShots.length === desiredShotCount) {
    return { ...parsed, adjustedShotCount: false };
  }
  await runtimeAgent.prompt(`上一轮输出了 ${currentShots.length} 个分镜，但平台生产模板要求恰好 ${desiredShotCount} 个分镜。
请基于同一段视频、同一批关键帧和上一轮结果重新整理，只输出一个完整、合法的 JSON 对象：
1. shots 必须恰好 ${desiredShotCount} 个，sequence 必须为 1 到 ${desiredShotCount}；
2. 通过拆分动作变化、构图变化或叙事节拍补足，不得复制同一个分镜，也不得虚构画面中不存在的主体、商品、文字或动作；
3. startSecond 与 endSecond 必须连续覆盖完整视频时间轴，不重叠、不留空档；
4. 每个分镜都必须保留完整的 sceneDescription、motionDescription、motionIntensity、shotSize、cameraMovement、emotion 和 imagePrompt；
5. 相邻分镜必须是同一动作的连续可达状态，上一镜结束姿态必须等于下一镜开始姿态；每镜最多推进一个主要动作，禁止姿势重置、瞬移、反关节和穿模；
6. 重整过程中仍然不得带入原人物的身份、外貌、发型、妆容、饰品和穿搭，不得带入原视频商品的类别、颜色、版型、印花、文字、Logo、卖点或搭配；商品只写“当前任务商品”；
7. 不得识别、转写或保留画面字幕、贴纸、字母、数字、水印和营销文案；没有音频转写时所有 narration 必须为空字符串；
8. 不得使用 Markdown，不得输出 JSON 之外的文字。`);
  const adjustedAssistant = [...runtimeAgent.state.messages]
    .reverse()
    .find(message => message.role === "assistant");
  if (!adjustedAssistant || adjustedAssistant.stopReason === "error") {
    throw new Error(adjustedAssistant?.errorMessage || "模型未能补足 9 个分镜");
  }
  const adjustedReply = messageText(adjustedAssistant);
  const adjusted = await parseVideoScriptResult(runtimeAgent, adjustedReply);
  const adjustedShots = Array.isArray(adjusted?.result?.shots) ? adjusted.result.shots : [];
  if (adjustedShots.length !== desiredShotCount) {
    throw new Error(`模型补足后仍返回 ${adjustedShots.length} 个分镜，要求为 ${desiredShotCount} 个`);
  }
  return {
    ...adjusted,
    adjustedShotCount: true
  };
}

async function runVideoScriptAnalysis(body) {
  const config = body.model || {};
  const apiKey = String(config.apiKey || "");
  if (!apiKey) throw new Error("模型 API Key 不能为空");
  const frames = (Array.isArray(body.frames) ? body.frames : [])
    .filter(frame => /^image\/(jpeg|png|webp)$/i.test(String(frame?.mimeType || ""))
      && String(frame?.data || "").length > 0)
    .slice(0, 48);
  if (!frames.length) throw new Error("没有可供模型分析的视频关键帧");

  const runtimeAgent = new Agent({
    initialState: {
      systemPrompt: VIDEO_SCRIPT_ANALYSIS_PROMPT,
      model: modelFrom(config),
      thinkingLevel: "medium",
      messages: [],
      tools: []
    },
    streamFn: (model, context, options) =>
      streamSimple(model, context, {
        ...options,
        apiKey,
        maxTokens: 6500,
        timeoutMs: 180_000,
        maxRetries: 2,
        maxRetryDelayMs: 15_000
      }),
    convertToLlm: messages =>
      messages.filter(message => ["user", "assistant"].includes(message.role))
  });
  const startedAt = Date.now();
  const context = body.context && typeof body.context === "object" ? body.context : {};
  const desiredShotCount = 9;
  const prompt = JSON.stringify({
    ...context,
    desiredShotCount,
    analysisMode: "KEYFRAMES_WITH_BACKGROUND_MOTION",
    frameOrder: frames.map((frame, index) => ({
      imagePosition: index + 1,
      sourceIndex: Number(frame.index || index + 1),
      timestampSeconds: Number(frame.timestampSeconds || 0)
    })),
    note: "以下图片按视频时间先后顺序排列，并附原视频时间点；只提取可复用的动作、人物数量、机位、运镜、场景、光线、节奏和微表情。不得带入原人物身份、外貌、发型、妆容、饰品、穿搭，不得带入原视频商品类别、颜色、版型、印花、文字、Logo、卖点或搭配，不得抄录字幕。后续商品唯一来源是用户在 AI 视频任务中上传的商品图。运镜优先参考 cameraMotionEvidence；当前没有可靠音频转写，所有 narration 必须为空。"
  });
  await runtimeAgent.prompt(
    prompt,
    frames.map(frame => ({
      type: "image",
      data: String(frame.data),
      mimeType: String(frame.mimeType)
    }))
  );
  const assistant = [...runtimeAgent.state.messages]
    .reverse()
    .find(message => message.role === "assistant");
  if (!assistant || assistant.stopReason === "error") {
    throw new Error(assistant?.errorMessage || "Pi Agent 没有返回有效视频脚本");
  }
  const reply = messageText(assistant);
  const parsed = await enforceVideoShotCount(
    runtimeAgent,
    await parseVideoScriptResult(runtimeAgent, reply),
    desiredShotCount
  );
  return {
    result: parsed.result,
    reply: parsed.reply,
    usage: tokenUsageFromMessages(runtimeAgent.state.messages),
    latencyMs: Date.now() - startedAt,
    modelId: config.modelId,
    analysisMode: "KEYFRAMES_WITH_BACKGROUND_MOTION",
    repairedByModel: parsed.repairedByModel,
    adjustedShotCount: parsed.adjustedShotCount
  };
}

async function handleVideoScriptAnalysis(request, response) {
  if (request.headers["x-internal-token"] !== INTERNAL_TOKEN) {
    json(response, 401, { message: "视频脚本分析服务认证失败" });
    return;
  }
  const body = await readBody(request);
  json(response, 200, await runVideoScriptAnalysis(body));
}

async function runAutomationAnalysis(body) {
  const mode = String(body.mode || "").trim().toLowerCase();
  if (!["plan", "diagnose", "result"].includes(mode)) {
    throw new Error("不支持的自动化分析模式");
  }
  const config = body.model || {};
  const apiKey = String(config.apiKey || "");
  if (!apiKey) throw new Error("模型 API Key 不能为空");
  const sessionId = String(body.sessionId || "").trim();
  if (mode === "diagnose" && sessionId) {
    const runtime = getAutomationSession(sessionId, config);
    const executeTurn = async () => {
      runtime.decisions = [];
      const messageCountBeforeTurn = runtime.agent.state.messages.length;
      const context = structuredClone(body.context || {});
      const screenshot = context?.browserToolResult?.result?.screenshot;
      const screenshotMimeType = context?.browserToolResult?.result?.screenshotMimeType;
      if (context?.browserToolResult?.result && screenshot) {
        delete context.browserToolResult.result.screenshot;
        context.browserToolResult.result.screenshotAttached = true;
      }
      await runtime.agent.prompt(
        JSON.stringify(context),
        screenshot
          ? [{
              type: "image",
              data: String(screenshot),
              mimeType: String(screenshotMimeType || "image/jpeg")
            }]
          : []
      );
      const assistant = [...runtime.agent.state.messages]
        .reverse()
        .find(message => message.role === "assistant");
      if (!assistant || assistant.stopReason === "error") {
        throw new Error(assistant?.errorMessage || "Agent 没有返回有效决策");
      }
      const reply = messageText(assistant);
      let result = runtime.decisions.at(-1);
      if (!result) result = extractJsonObject(reply);
      return {
        result,
        reply,
        usage: tokenUsageFromMessages(
          runtime.agent.state.messages.slice(messageCountBeforeTurn)
        ),
        latencyMs: Date.now() - startedAt,
        modelId: config.modelId
      };
    };
    const startedAt = Date.now();
    runtime.tail = runtime.tail.then(executeTurn, executeTurn);
    const response = await runtime.tail;
    if (response.result?.action === "FINISH"
        || response.result?.action === "FAIL") {
      automationSessions.delete(sessionId);
    }
    return response;
  }
  const runtimeAgent = new Agent({
    initialState: {
      systemPrompt: mode === "plan"
        ? AUTOMATION_PLAN_PROMPT
        : mode === "result"
          ? AUTOMATION_RESULT_PROMPT + AUTOMATION_VERIFICATION_RULES
          : AUTOMATION_DIAGNOSIS_PROMPT + AUTOMATION_VERIFICATION_RULES,
      model: modelFrom(config),
      thinkingLevel: "off",
      messages: [],
      tools: []
    },
    streamFn: (model, context, options) =>
      streamSimple(model, context, {
        ...options,
        apiKey,
        maxTokens: 1600,
        timeoutMs: 90_000,
        maxRetries: 3,
        maxRetryDelayMs: 12_000
      }),
    convertToLlm: (messages) =>
      messages.filter((message) => ["user", "assistant"].includes(message.role))
  });
  const startedAt = Date.now();
  await runtimeAgent.prompt(JSON.stringify(body.context || {}));
  const assistant = [...runtimeAgent.state.messages]
    .reverse()
    .find((message) => message.role === "assistant");
  if (!assistant || assistant.stopReason === "error") {
    throw new Error(assistant?.errorMessage || "模型没有返回有效回复");
  }
  const reply = messageText(assistant);
  return {
    result: extractJsonObject(reply),
    reply,
    usage: tokenUsageFromMessages(runtimeAgent.state.messages),
    latencyMs: Date.now() - startedAt,
    modelId: config.modelId
  };
}

async function handleAutomationAnalysis(request, response) {
  if (request.headers["x-internal-token"] !== INTERNAL_TOKEN) {
    json(response, 401, { message: "智能调度服务认证失败" });
    return;
  }
  const body = await readBody(request);
  json(response, 200, await runAutomationAnalysis(body));
}

async function handlePlatformImageGeneration(request, response) {
  if (request.headers["x-internal-token"] !== INTERNAL_TOKEN) {
    json(response, 401, { message: "图片生成服务认证失败" });
    return;
  }
  const body = await readBody(request);
  const references = (Array.isArray(body.references) ? body.references : [])
    .filter(value => /^image\//i.test(String(value?.mimeType || "")) && String(value?.dataBase64 || "").length > 0)
    .slice(0, 2);
  const result = await callImageModel(body.model || {}, {
    mode: references.length ? "edit" : "generate",
    prompt: String(body.prompt || ""),
    size: String(body.size || "1024x1536"),
    quality: String(body.quality || ""),
    inputFidelity: String(body.inputFidelity || "")
  }, references);
  json(response, 200, result);
}

const server = http.createServer(async (request, response) => {
  try {
    if (request.method === "GET" && request.url === "/health") {
      json(response, 200, {
        status: "UP",
        sdk: "@earendil-works/pi-agent-core",
        chatSessions: sessions.size,
        automationAgentSessions: automationSessions.size
      });
      return;
    }
    if (request.method === "POST" && request.url === "/v1/chat") {
      await handleChat(request, response);
      return;
    }
    if (request.method === "POST" && request.url === "/v1/automation/analyze") {
      await handleAutomationAnalysis(request, response);
      return;
    }
    if (request.method === "POST" && request.url === "/v1/video-script/analyze") {
      await handleVideoScriptAnalysis(request, response);
      return;
    }
    if (request.method === "POST" && request.url === "/v1/images/generate") {
      await handlePlatformImageGeneration(request, response);
      return;
    }
    json(response, 404, { message: "接口不存在" });
  } catch (error) {
    console.error(error);
    const payload = { message: publicErrorMessage(error) };
    if (error?.requestState) {
      payload.requestState = String(error.requestState);
      payload.retrySafe = Boolean(error.retrySafe);
      payload.safeRetryCount = Number(error.safeRetryCount || 0);
    }
    json(response, 502, payload);
  }
});

server.listen(PORT, "0.0.0.0", () => {
  console.log(`Heyi Pi runtime listening on ${PORT}`);
});
