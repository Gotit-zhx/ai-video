package com.yingdao.aicommerce.automation.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yingdao.aicommerce.automation.domain.AutomationCommand;
import com.yingdao.aicommerce.automation.domain.AutomationCommandEvent;
import com.yingdao.aicommerce.automation.mapper.AutomationCommandEventMapper;
import com.yingdao.aicommerce.automation.mapper.AutomationCommandMapper;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.saas.application.ModelConfigService;
import com.yingdao.aicommerce.workflow.application.RedisConcurrencyGate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AutomationModelService {

    private static final Pattern STORE_COUNT = Pattern.compile(
            "(\\d{1,4})\\s*(?:个|家)?\\s*店铺"
    );
    private final ModelConfigService modelConfigService;
    private final RedisConcurrencyGate concurrencyGate;
    private final AutomationCommandMapper commandMapper;
    private final AutomationCommandEventMapper eventMapper;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final RestClient piClient;

    public AutomationModelService(
            ModelConfigService modelConfigService,
            RedisConcurrencyGate concurrencyGate,
            AutomationCommandMapper commandMapper,
            AutomationCommandEventMapper eventMapper,
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            @Value("${app.pi-runtime-url}") String piRuntimeUrl,
            @Value("${app.pi-runtime-token}") String piRuntimeToken
    ) {
        this.modelConfigService = modelConfigService;
        this.concurrencyGate = concurrencyGate;
        this.commandMapper = commandMapper;
        this.eventMapper = eventMapper;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.piClient = RestClient.builder()
                .baseUrl(piRuntimeUrl)
                .defaultHeader("X-Internal-Token", piRuntimeToken)
                .build();
    }

    public PlanningResult plan(
            String organizationId,
            String skillCode,
            String skillVersion,
            String requirement,
            Map<String, Object> parameters,
            Map<String, Object> skillDefinition
    ) {
        String normalizedRequirement = requirement == null ? "" : requirement.trim();
        String planningRequirement = normalizedRequirement.isBlank()
                ? "按表单参数执行。请检查参数完整性、风险和执行顺序，并生成明确计划。"
                : normalizedRequirement;
        Map<String, Object> context = Map.of(
                "skillCode", skillCode,
                "skillVersion", skillVersion,
                "userRequirement", planningRequirement,
                "currentParameters", parameters,
                "skillDefinition", skillDefinition
        );
        String cacheKey = "heyi:automation:plan:" + sha256(toJson(context));
        String cached = redis.opsForValue().get(cacheKey);
        if (cached != null) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> value = objectMapper.readValue(cached, Map.class);
                return normalizePlan(value, parameters, "CACHE", 0, true);
            } catch (Exception ignored) {
                redis.delete(cacheKey);
            }
        }
        String lockKey = cacheKey + ":generating";
        Boolean locked = redis.opsForValue().setIfAbsent(
                lockKey, "1", Duration.ofMinutes(2)
        );
        if (!Boolean.TRUE.equals(locked)) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "Pi Agent 正在生成这份执行计划，请等待当前请求完成"
            );
        }
        try {
            AnalysisResponse response = analyze(
                    organizationId, "plan", "plan:" + sha256(toJson(context)), context
            );
            redis.opsForValue().set(
                    cacheKey, toJson(response.result()), Duration.ofHours(24)
            );
            return normalizePlan(
                    response.result(), parameters, response.modelId(),
                    response.latencyMs(), false
            );
        } catch (RuntimeException exception) {
            PlanningResult fallback = fallbackPlan(normalizedRequirement, parameters);
            if (fallback != null) return fallback;
            if (normalizedRequirement.isBlank()) {
                return new PlanningResult(
                        "按表单参数执行，运行中启用模型监督与自动恢复",
                        Map.copyOf(parameters),
                        List.of(),
                        List.of("入口模型暂不可用，已保留原参数；执行异常仍会请求模型诊断"),
                        "RULE_FALLBACK",
                        0,
                        false,
                        false
                );
            }
            throw exception;
        } finally {
            redis.delete(lockKey);
        }
    }

    private PlanningResult normalizePlan(
            Map<String, Object> result,
            Map<String, Object> parameters,
            String modelId,
            long latencyMs,
            boolean cached
    ) {
        Map<String, Object> merged = new LinkedHashMap<>(parameters);
        Map<String, Object> overrides = asMap(result.get("parameterOverrides"));
        if (overrides.containsKey("storeLimit")) {
            int limit = intValue(overrides.get("storeLimit"), 0);
            if (limit < 0 || limit > 10_000) {
                throw new BusinessException(HttpStatus.BAD_GATEWAY, "模型返回的店铺数量限制无效");
            }
            merged.put("storeLimit", limit);
        }
        if (overrides.containsKey("maxConcurrency")) {
            int concurrency = intValue(overrides.get("maxConcurrency"), 3);
            if (concurrency < 1 || concurrency > 5) {
                throw new BusinessException(HttpStatus.BAD_GATEWAY, "模型返回的并发数无效");
            }
            merged.put("maxConcurrency", concurrency);
            merged.put("maxLiveStoreSessions", Math.min(concurrency, 5));
        }
        String onlyPlatform = normalizePlatform(stringValue(overrides.get("onlyPlatform")));
        if (!onlyPlatform.isBlank()) {
            if (!List.of(
                    "all", "tiktok", "tiktok-global", "tiktok-all", "shopee", "lazada"
            ).contains(onlyPlatform)) {
                throw new BusinessException(HttpStatus.BAD_GATEWAY, "模型返回的平台范围无效");
            }
            merged.put("onlyPlatform", onlyPlatform);
        }
        String onlyStore = stringValue(overrides.get("onlyStore"));
        if (!onlyStore.isBlank()) merged.put("onlyStore", onlyStore);
        if (overrides.containsKey("officialWarehouseOnly")) {
            merged.put(
                    "officialWarehouseOnly",
                    booleanValue(overrides.get("officialWarehouseOnly"))
            );
        }
        return new PlanningResult(
                stringValue(result.getOrDefault("summary", "模型已生成执行计划")),
                Map.copyOf(merged),
                stringList(result.get("assumptions")),
                stringList(result.get("warnings")),
                modelId,
                latencyMs,
                cached,
                true
        );
    }

    private PlanningResult fallbackPlan(
            String requirement,
            Map<String, Object> parameters
    ) {
        Matcher matcher = STORE_COUNT.matcher(requirement);
        Integer limit = matcher.find()
                ? Integer.valueOf(matcher.group(1))
                : chineseStoreCount(requirement);
        String platform = fallbackPlatform(requirement);
        boolean officialWarehouseOnly = isOfficialWarehouseRequest(requirement);
        if ((limit == null || limit <= 0)
                && platform.isBlank()
                && !officialWarehouseOnly) return null;
        Map<String, Object> merged = new LinkedHashMap<>(parameters);
        if (limit != null && limit > 0) {
            merged.put("storeLimit", Math.min(limit, 10_000));
        }
        if (!platform.isBlank()) {
            merged.put("onlyPlatform", platform);
        }
        if (officialWarehouseOnly) {
            merged.put("officialWarehouseOnly", true);
        }
        String summary = limit != null && limit > 0
                ? "最多执行 " + limit + " 个店铺任务"
                : "仅执行 " + platform + " 平台任务";
        if (limit != null && limit > 0 && !platform.isBlank()) {
            summary += "，限定平台为 " + platform;
        }
        if (officialWarehouseOnly) {
            summary = "执行所有备注为“官方仓”的店铺";
            if (limit != null && limit > 0) {
                summary += "，最多 " + limit + " 个店铺任务";
            }
        }
        return new PlanningResult(
                summary,
                Map.copyOf(merged),
                List.of(),
                List.of("模型暂不可用，已使用确定性规则生成常用执行参数"),
                "RULE_FALLBACK",
                0,
                false,
                true
        );
    }

    private String fallbackPlatform(String requirement) {
        String normalized = requirement.toLowerCase();
        if (normalized.contains("shopee") || normalized.contains("虾皮")) return "shopee";
        if (normalized.contains("lazada")) return "lazada";
        if (normalized.contains("tiktok") || normalized.contains("抖音")) return "tiktok";
        return "";
    }

    private String normalizePlatform(String platform) {
        String normalized = platform.trim().toLowerCase();
        return switch (normalized) {
            case "虾皮", "shopee平台", "虾皮平台" -> "shopee";
            case "抖音", "抖音平台", "tiktok平台" -> "tiktok";
            case "来赞达", "来赞达平台", "lazada平台" -> "lazada";
            case "全部", "所有", "全部平台", "所有平台" -> "all";
            default -> normalized;
        };
    }

    private boolean isOfficialWarehouseRequest(String requirement) {
        String normalized = requirement.replaceAll("\\s+", "");
        return normalized.contains("官方仓")
                && (normalized.contains("备注")
                    || normalized.contains("店铺")
                    || normalized.contains("全部"));
    }

    private Integer chineseStoreCount(String requirement) {
        Map<String, Integer> values = Map.of(
                "一", 1, "二", 2, "三", 3, "四", 4, "五", 5,
                "六", 6, "七", 7, "八", 8, "九", 9, "十", 10
        );
        for (var entry : values.entrySet()) {
            if (requirement.contains(entry.getKey() + "个店铺")
                    || requirement.contains(entry.getKey() + "家店铺")) {
                return entry.getValue();
            }
        }
        return null;
    }

    @Async("automationDiagnosisExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void supervise(AutomationModelSignal signal) {
        String dedupeKey = "heyi:automation:model-signal:"
                + signal.commandId() + ":" + signal.kind();
        Boolean accepted = redis.opsForValue().setIfAbsent(
                dedupeKey, "1",
                Duration.ofHours(24)
        );
        if (!Boolean.TRUE.equals(accepted)) return;
        AutomationCommand command = commandMapper.selectById(signal.commandId());
        if (command == null) return;
        List<AutomationCommandEvent> recent = eventMapper.selectList(
                new LambdaQueryWrapper<AutomationCommandEvent>()
                        .eq(AutomationCommandEvent::getCommandId, command.getId())
                        .orderByDesc(AutomationCommandEvent::getCreatedAt)
                        .last("LIMIT 12")
        );
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("signalKind", signal.kind());
        context.put("trigger", signal.trigger());
        context.put("status", command.getStatus());
        context.put("progressPercent", command.getProgressPercent());
        context.put("progressMessage", command.getProgressMessage());
        context.put("errorMessage", command.getErrorMessage());
        context.put("input", parseJson(command.getInputPayload()));
        context.put("recentEvents", recent.stream().map(this::eventContext).toList());
        Object triggerPayload = recent.stream()
                .filter(event -> List.of(
                        "EXECUTION_UNIT_RESULT", "EXECUTION_UNIT_UPDATE"
                ).contains(event.getEventType()))
                .findFirst()
                .map(event -> parseJson(event.getPayload()))
                .orElse(Map.of());
        context.put("triggerPayload", triggerPayload);
        try {
            AnalysisResponse response = analyze(
                    command.getOrganizationId(),
                    signal.kind().startsWith("UNIT_RESULT_") ? "result" : "diagnose",
                    signal.kind().startsWith("UNIT_RESULT_")
                            ? command.getId() + ":" + signal.kind()
                            : command.getId(),
                    context
            );
            String message = stringValue(response.result().get("userMessage"));
            if (message.isBlank()) {
                message = stringValue(response.result().getOrDefault(
                        "summary", "模型已完成异常分析"
                ));
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("kind", signal.kind());
            payload.put("modelId", response.modelId());
            payload.put("latencyMs", response.latencyMs());
            payload.put("tokenUsage", response.usage());
            payload.put("diagnosis", response.result());
            if (signal.kind().startsWith("UNIT_RESULT_")) {
                payload.put("unitResult", triggerPayload);
            }
            appendModelEvent(command, eventTypeFor(signal.kind()), message, payload);
        } catch (RuntimeException exception) {
            appendModelEvent(
                    command,
                    eventTypeFor(signal.kind()),
                    "模型诊断暂不可用：" + readableMessage(exception),
                    Map.of("kind", signal.kind(), "available", false)
            );
        }
    }

    @Transactional
    protected void appendModelEvent(
            AutomationCommand command,
            String eventType,
            String message,
            Object payload
    ) {
        eventMapper.insert(new AutomationCommandEvent(
                command,
                eventType,
                "MODEL",
                command.getProgressPercent(),
                message,
                toJson(payload)
        ));
    }

    @Transactional
    public RecoveryAdvice recover(
            String organizationId,
            String agentId,
            String commandId,
            int attempt,
            String errorMessage,
            Map<String, Object> toolResult,
            String contextKey
    ) {
        AutomationCommand command = commandMapper.selectById(commandId);
        if (command == null
                || !organizationId.equals(command.getOrganizationId())
                || !agentId.equals(command.getAgentId())) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "自动化命令不存在");
        }
        if (!"RUNNING".equals(command.getStatus())) {
            throw new BusinessException(HttpStatus.CONFLICT, "任务当前不能进行自动恢复");
        }
        int safeAttempt = Math.max(1, Math.min(6, attempt));
        List<AutomationCommandEvent> recent = eventMapper.selectList(
                new LambdaQueryWrapper<AutomationCommandEvent>()
                        .eq(AutomationCommandEvent::getCommandId, commandId)
                        .orderByDesc(AutomationCommandEvent::getCreatedAt)
                        .last("LIMIT 16")
        );
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("signalKind", "RECOVERY");
        context.put("commandType", command.getCommandType());
        context.put("attempt", safeAttempt);
        context.put("maxAttempts", 6);
        context.put("errorMessage", errorMessage == null ? "" : errorMessage);
        Map<String, Object> errorContext = asMap(parseJson(errorMessage));
        context.put("errorContext", errorContext);
        context.put("taskMode", stringValue(errorContext.get("taskMode")));
        context.put("browserToolResult", toolResult == null ? Map.of() : toolResult);
        context.put("progressPercent", command.getProgressPercent());
        context.put("progressMessage", command.getProgressMessage());
        context.put("input", parseJson(command.getInputPayload()));
        context.put("recentEvents", recent.stream().map(this::eventContext).toList());

        RecoveryAdvice advice;
        Map<String, Object> recoveryTokenUsage = Map.of();
        try {
            AnalysisResponse response = analyze(
                    organizationId,
                    "diagnose",
                    contextKey == null || contextKey.isBlank()
                            ? commandId
                            : commandId + ":" + contextKey.replaceAll("[^A-Za-z0-9._-]", "-").substring(
                                    0, Math.min(80, contextKey.length())
                            ),
                    context
            );
            recoveryTokenUsage = response.usage();
            advice = normalizeRecovery(response.result(), safeAttempt, response.modelId());
        } catch (RuntimeException exception) {
            boolean canContinue = safeAttempt <= 4;
            advice = new RecoveryAdvice(
                    canContinue,
                    canContinue ? "RETRY_RESUME" : "FAIL",
                    canContinue ? 3 : 0,
                    null,
                    canContinue
                            ? "模型诊断暂不可用，先按断点恢复策略自动重试"
                            : "模型诊断持续不可用且脚本已重试，确定本轮失败",
                    "模型服务不可用：" + readableMessage(exception),
                    "RULE_FALLBACK",
                    false,
                    Map.of(),
                    Map.of()
            );
        }
        Map<String, Object> recoveryPayload = new LinkedHashMap<>();
        recoveryPayload.put("attempt", safeAttempt);
        recoveryPayload.put("continueExecution", advice.continueExecution());
        recoveryPayload.put("action", advice.action());
        recoveryPayload.put("delaySeconds", advice.delaySeconds());
        recoveryPayload.put("maxConcurrency", advice.maxConcurrency() == null
                ? "" : advice.maxConcurrency());
        recoveryPayload.put("reason", advice.reason());
        recoveryPayload.put("modelId", advice.modelId());
        recoveryPayload.put("requiresTool", advice.requiresTool());
        recoveryPayload.put("toolAction", advice.toolAction());
        recoveryPayload.put("resultData", advice.resultData());
        recoveryPayload.put("tokenUsage", recoveryTokenUsage);
        appendModelEvent(
                command,
                "MODEL_RECOVERY",
                advice.userMessage(),
                recoveryPayload
        );
        return advice;
    }

    private RecoveryAdvice normalizeRecovery(
            Map<String, Object> result,
            int attempt,
            String modelId
    ) {
        String action = stringValue(result.get("action")).toUpperCase();
        if ("FINISH".equals(action)) {
            Map<String, Object> resultData = new LinkedHashMap<>(
                    asMap(result.get("analysisResult"))
            );
            resultData.put(
                    "completionEvidence",
                    stringValue(result.get("completionEvidence"))
            );
            resultData.put("evidenceSource", stringValue(result.get("evidenceSource")));
            resultData.put("evidenceQuotes", stringList(result.get("evidenceQuotes")));
            return new RecoveryAdvice(
                    false, "FINISH", 0, null,
                    stringValue(result.getOrDefault("userMessage", "当前页面任务已完成")),
                    stringValue(result.getOrDefault("summary", "页面已出现完成证据")),
                    modelId == null ? "" : modelId,
                    false,
                    Map.of(),
                    Map.copyOf(resultData)
            );
        }
        if ("CONTINUE".equals(action)) {
            return new RecoveryAdvice(
                    true, "CONTINUE", 1, null,
                    stringValue(result.getOrDefault("userMessage", "继续复核当前页面")),
                    stringValue(result.getOrDefault("summary", "页面任务仍在进行")),
                    modelId == null ? "" : modelId,
                    false,
                    Map.of(),
                    Map.of()
            );
        }
        if (List.of(
                "BROWSER_OBSERVE", "BROWSER_NAVIGATE", "BROWSER_CLICK", "BROWSER_TYPE",
                "BROWSER_SELECT", "BROWSER_KEYS", "BROWSER_HOVER", "BROWSER_SCROLL",
                "BROWSER_HISTORY", "BROWSER_READ", "BROWSER_SCREENSHOT",
                "BROWSER_NETWORK_LIST", "BROWSER_NETWORK_READ", "BROWSER_WAIT"
        ).contains(action)) {
            return new RecoveryAdvice(
                    false, action, 0, null,
                    stringValue(result.getOrDefault(
                            "userMessage", "Pi Agent 正在调用本机浏览器工具"
                    )),
                    "需要更多页面信息后继续判断",
                    modelId == null ? "" : modelId,
                    true,
                    asMap(result.get("browserAction")),
                    Map.of()
            );
        }
        if (!List.of(
                "RETRY_RESUME", "RETRY_DELAY", "REDUCE_CONCURRENCY", "FAIL"
        ).contains(action)) {
            action = booleanValue(result.get("canRetry")) ? "RETRY_RESUME" : "FAIL";
        }
        boolean needsHuman = booleanValue(result.get("needsHuman"));
        boolean continueExecution = !"FAIL".equals(action)
                && !needsHuman
                && attempt < 6;
        if (!continueExecution) action = "FAIL";
        int delaySeconds = Math.max(0, Math.min(
                30, intValue(result.get("delaySeconds"), "RETRY_DELAY".equals(action) ? 5 : 2)
        ));
        Integer maxConcurrency = null;
        if ("REDUCE_CONCURRENCY".equals(action)) {
            maxConcurrency = Math.max(1, Math.min(
                    5, intValue(result.get("maxConcurrency"), 1)
            ));
        }
        String userMessage = stringValue(result.get("userMessage"));
        if (userMessage.isBlank()) {
            userMessage = continueExecution
                    ? "模型已分析异常，正在执行第 " + (attempt + 1) + " 次恢复"
                    : "模型判断当前错误无法继续自动恢复";
        }
        return new RecoveryAdvice(
                continueExecution,
                action,
                delaySeconds,
                maxConcurrency,
                userMessage,
                stringValue(result.getOrDefault("likelyCause", "未识别具体原因")),
                modelId == null ? "" : modelId,
                false,
                Map.of(),
                Map.of()
        );
    }

    private boolean booleanValue(Object value) {
        if (value instanceof Boolean booleanValue) return booleanValue;
        return "true".equalsIgnoreCase(String.valueOf(value));
    }

    private String eventTypeFor(String kind) {
        if (kind == null) return "MODEL_DIAGNOSIS";
        if (kind.startsWith("UNIT_RESULT_")) return "MODEL_UNIT_RESULT";
        if (kind.startsWith("UNIT_UPDATE_")) return "MODEL_DIAGNOSIS";
        if (kind.startsWith("CHECKPOINT_")) return "MODEL_CHECKPOINT";
        if ("SUCCESS".equals(kind)) return "MODEL_SUMMARY";
        return "MODEL_DIAGNOSIS";
    }

    private Map<String, Object> eventContext(AutomationCommandEvent event) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("source", event.getSource());
        value.put("type", event.getEventType());
        value.put("message", event.getMessage());
        value.put(
                "progress",
                event.getProgressPercent() == null ? 0 : event.getProgressPercent()
        );
        value.put(
                "payload",
                event.getPayload() == null ? Map.of() : parseJson(event.getPayload())
        );
        return value;
    }

    private AnalysisResponse analyze(
            String organizationId,
            String mode,
            String sessionId,
            Map<String, Object> context
    ) {
        var model = modelConfigService.driverConnection(organizationId)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.CONFLICT,
                        "请先配置并启用驱动模型"
                ));
        RedisConcurrencyGate.Permit permit = concurrencyGate.tryAcquireModelCall();
        if (permit == null) {
            throw new BusinessException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "当前模型任务较多"
            );
        }
        try (permit) {
            PiAnalysisResponse response = piClient.post()
                    .uri("/v1/automation/analyze")
                    .body(new PiAnalysisRequest(
                            mode,
                            sessionId,
                            context,
                            new PiModel(
                                    model.baseUrl(), model.modelId(),
                                    model.apiKey(), "openai-completions"
                            )
                    ))
                    .retrieve()
                    .body(PiAnalysisResponse.class);
            if (response == null || response.result() == null) {
                throw new BusinessException(
                        HttpStatus.BAD_GATEWAY,
                        "模型没有返回结构化结果"
                );
            }
            return new AnalysisResponse(
                    response.result(), response.modelId(), response.latencyMs(),
                    response.usage() == null ? Map.of() : response.usage()
            );
        } catch (RestClientException exception) {
            throw new BusinessException(
                    HttpStatus.BAD_GATEWAY,
                    "智能调度服务调用失败：" + readableMessage(exception)
            );
        }
    }

    private Object parseJson(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return objectMapper.readValue(value, Object.class);
        } catch (Exception exception) {
            return value;
        }
    }

    private Map<String, Object> asMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(String::valueOf).filter(item -> !item.isBlank()).toList();
    }

    private int intValue(Object value, int fallback) {
        if (value == null) return fallback;
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("无法序列化模型上下文", exception);
        }
    }

    private String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(bytes);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String readableMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) return "模型服务不可用";
        String normalized = message.toLowerCase();
        if (normalized.contains("connection error")
                || normalized.contains("econnreset")
                || normalized.contains("connection reset")) {
            return "模型 API 网络连接失败，请检查 API 地址以及宿主机或 Docker 的代理网络";
        }
        return message.length() > 240 ? message.substring(0, 240) : message;
    }

    public record PlanningResult(
            String summary,
            Map<String, Object> parameters,
            List<String> assumptions,
            List<String> warnings,
            String modelId,
            long latencyMs,
            boolean cached,
            boolean modelPlanned
    ) {
    }

    public record RecoveryAdvice(
            boolean continueExecution,
            String action,
            int delaySeconds,
            Integer maxConcurrency,
            String userMessage,
            String reason,
            String modelId,
            boolean requiresTool,
            Map<String, Object> toolAction,
            Map<String, Object> resultData
    ) {
    }

    private record AnalysisResponse(
            Map<String, Object> result,
            String modelId,
            long latencyMs,
            Map<String, Object> usage
    ) {
    }

    private record PiAnalysisRequest(
            String mode,
            String sessionId,
            Map<String, Object> context,
            PiModel model
    ) {
    }

    private record PiModel(
            String baseUrl,
            String modelId,
            String apiKey,
            String apiFormat
    ) {
    }

    private record PiAnalysisResponse(
            Map<String, Object> result,
            String reply,
            Map<String, Object> usage,
            long latencyMs,
            String modelId
    ) {
    }
}
