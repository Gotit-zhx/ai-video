package com.yingdao.aicommerce.assistant.application;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.automation.application.AutomationAgentService;
import com.yingdao.aicommerce.automation.application.AutomationAttachmentService;
import com.yingdao.aicommerce.automation.domain.AutomationAgent;
import com.yingdao.aicommerce.automation.domain.AutomationCommand;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.saas.application.ModelConfigService;
import com.yingdao.aicommerce.task.application.ActiveUserTaskService;
import com.yingdao.aicommerce.workflow.application.RedisConcurrencyGate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Service
public class AssistantTestService {

    private final ModelConfigService modelConfigService;
    private final AutomationAgentService automationAgentService;
    private final AutomationAttachmentService attachmentService;
    private final ActiveUserTaskService activeTaskService;
    private final RedisConcurrencyGate concurrencyGate;
    private final RestClient piClient;

    public AssistantTestService(
            ModelConfigService modelConfigService,
            AutomationAgentService automationAgentService,
            AutomationAttachmentService attachmentService,
            ActiveUserTaskService activeTaskService,
            RedisConcurrencyGate concurrencyGate,
            @Value("${app.pi-runtime-url}") String piRuntimeUrl,
            @Value("${app.pi-runtime-token}") String piRuntimeToken
    ) {
        this.modelConfigService = modelConfigService;
        this.automationAgentService = automationAgentService;
        this.attachmentService = attachmentService;
        this.activeTaskService = activeTaskService;
        this.concurrencyGate = concurrencyGate;
        this.piClient = RestClient.builder()
                .baseUrl(piRuntimeUrl)
                .defaultHeader("X-Internal-Token", piRuntimeToken)
                .build();
    }

    public AssistantStatus status(PlatformPrincipal principal, String localAgentId) {
        var model = modelConfigService.defaultDriverConnection();
        var imageModel = modelConfigService.defaultImageConnection();
        AutomationAgent agent = automationAgentService.availableAgentForUser(
                principal.organizationId(), principal.userId(), localAgentId
        );
        boolean piOnline;
        String sdk = "@earendil-works/pi-agent-core";
        try {
            PiHealth health = piClient.get().uri("/health").retrieve().body(PiHealth.class);
            piOnline = health != null && "UP".equals(health.status());
            if (health != null && health.sdk() != null) sdk = health.sdk();
        } catch (RestClientException exception) {
            piOnline = false;
        }
        return new AssistantStatus(
                piOnline,
                sdk,
                model.isPresent(),
                model.map(ModelConfigService.RuntimeModelConnection::modelId).orElse(null),
                model.map(ModelConfigService.RuntimeModelConnection::baseUrl).orElse(null),
                imageModel.isPresent(),
                imageModel.map(ModelConfigService.RuntimeModelConnection::modelId).orElse(null),
                agent != null,
                agent == null ? null : agent.getId(),
                agent == null ? null : agent.getName(),
                agent == null ? null : agent.getOsName(),
                agent == null ? null : agent.getLastSeenAt()
        );
    }

    public ChatResult chat(
            PlatformPrincipal principal,
            String sessionId,
            String prompt,
            List<String> attachmentIds,
            String localAgentId
    ) {
        activeTaskService.acquire(
                principal.organizationId(),
                principal.userId(),
                sessionId,
                "ASSISTANT_AUTOMATION",
                Duration.ofMinutes(30)
        );
        try {
            return executeChat(principal, sessionId, prompt, attachmentIds, localAgentId);
        } finally {
            if (!keepTaskForSession(principal, sessionId)) {
                activeTaskService.release(
                        principal.organizationId(),
                        principal.userId(),
                        sessionId
                );
            }
        }
    }

    private ChatResult executeChat(
            PlatformPrincipal principal,
            String sessionId,
            String prompt,
            List<String> attachmentIds,
            String localAgentId
    ) {
        ModelConfigService.RuntimeModelConnection model = modelConfigService
                .defaultDriverConnection()
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.CONFLICT,
                        "请先在模型接入中添加并启用一个驱动模型"
                ));
        if (!"OPENAI_COMPATIBLE".equals(model.apiFormat())) {
            throw new BusinessException(HttpStatus.CONFLICT, "当前驱动模型不是 OpenAI 兼容协议");
        }
        var imageModel = modelConfigService.defaultImageConnection();
        List<PiInputImage> inputImages = loadInputImages(attachmentIds, principal);
        PiResponse response;
        RedisConcurrencyGate.Permit modelPermit = concurrencyGate.tryAcquireModelCall();
        if (modelPermit == null) {
            throw new BusinessException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "当前模型任务较多，请等待正在执行的任务完成后再试"
            );
        }
        try (modelPermit) {
            response = piClient.post()
                        .uri("/v1/chat")
                        .body(new PiRequest(
                                sessionId,
                                prompt,
                                new PiModel(
                                        model.baseUrl(),
                                        model.modelId(),
                                        model.apiKey(),
                                        "openai-completions"
                                ),
                                imageModel.map(connection -> new PiImageModel(
                                        connection.baseUrl(),
                                        connection.modelId(),
                                        connection.apiKey(),
                                        connection.apiFormat(),
                                        connection.copyrightFallbackModels().stream()
                                                .map(ModelConfigService.RuntimeModelConnection::imageRequestModel).toList()
                                )).orElse(null),
                                inputImages
                        ))
                        .retrieve()
                        .body(PiResponse.class);
        } catch (RestClientException exception) {
            throw new BusinessException(
                    HttpStatus.BAD_GATEWAY,
                    "智能调度服务调用失败：" + readableMessage(exception)
            );
        }
        if (response == null) {
            throw new BusinessException(HttpStatus.BAD_GATEWAY, "智能调度服务没有返回结果");
        }

        List<QueuedCommand> queued = new ArrayList<>();
        for (PiAction action : response.actions() == null ? List.<PiAction>of() : response.actions()) {
            AutomationCommand command = automationAgentService.enqueue(
                    principal.organizationId(),
                    principal.userId(),
                    sessionId,
                    action.commandType(),
                    action.payload(),
                    localAgentId
            );
            queued.add(new QueuedCommand(command.getId(), command.getCommandType(), command.getStatus()));
        }
        List<GeneratedArtifact> artifacts = storeGeneratedArtifacts(
                response.artifacts(), principal
        );
        return new ChatResult(
                response.sessionId(),
                response.reply(),
                response.modelId(),
                response.latencyMs(),
                response.usage() == null ? Map.of() : response.usage(),
                response.trace() == null ? List.of() : response.trace(),
                queued,
                artifacts
        );
    }

    private List<PiInputImage> loadInputImages(
            List<String> attachmentIds,
            PlatformPrincipal principal
    ) {
        if (attachmentIds == null || attachmentIds.isEmpty()) return List.of();
        if (attachmentIds.size() > 1) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "每次图生图只能上传 1 张参考图");
        }
        List<PiInputImage> images = new ArrayList<>();
        for (String attachmentId : attachmentIds) {
            var attachment = attachmentService.loadForUser(attachmentId, principal);
            if (!attachment.contentType().startsWith("image/")) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "图生图只支持图片文件");
            }
            if (attachment.size() > 20L * 1024 * 1024) {
                throw new BusinessException(HttpStatus.PAYLOAD_TOO_LARGE, "单张参考图不能超过 20MB");
            }
            try {
                images.add(new PiInputImage(
                        attachment.name(),
                        attachment.contentType(),
                        Base64.getEncoder().encodeToString(java.nio.file.Files.readAllBytes(attachment.path()))
                ));
            } catch (java.io.IOException exception) {
                throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "参考图读取失败");
            }
        }
        return images;
    }

    private List<GeneratedArtifact> storeGeneratedArtifacts(
            List<PiArtifact> values,
            PlatformPrincipal principal
    ) {
        if (values == null || values.isEmpty()) return List.of();
        List<GeneratedArtifact> stored = new ArrayList<>();
        for (PiArtifact value : values) {
            if (value == null || value.dataBase64() == null || value.dataBase64().isBlank()) continue;
            if (value.dataBase64().length() > 50_000_000) {
                throw new BusinessException(HttpStatus.PAYLOAD_TOO_LARGE, "生成图片返回内容过大");
            }
            byte[] bytes;
            try {
                bytes = Base64.getDecoder().decode(value.dataBase64());
            } catch (IllegalArgumentException exception) {
                throw new BusinessException(HttpStatus.BAD_GATEWAY, "图片模型返回了无效图片数据");
            }
            var attachment = attachmentService.storeGenerated(
                    bytes,
                    value.name(),
                    value.mimeType(),
                    principal.organizationId(),
                    principal.userId()
            );
            stored.add(new GeneratedArtifact(
                    attachment.id(),
                    attachment.name(),
                    attachment.size(),
                    value.mimeType(),
                    value.modelId(),
                    value.mode(),
                    value.prompt(),
                    value.revisedPrompt(),
                    3
            ));
        }
        return stored;
    }

    private boolean keepTaskForSession(
            PlatformPrincipal principal,
            String sessionId
    ) {
        return automationAgentService
                .sessionCommands(principal.organizationId(), sessionId)
                .stream()
                .anyMatch(command -> "QUEUED".equals(command.getStatus())
                        || "RUNNING".equals(command.getStatus()));
    }

    public List<CommandStatus> commands(PlatformPrincipal principal, String sessionId) {
        return automationAgentService
                .sessionCommands(principal.organizationId(), sessionId)
                .stream()
                .filter(command -> principal.userId().equals(command.getCreatedBy()))
                .map(command -> new CommandStatus(
                        command.getId(),
                        command.getCommandType(),
                        command.getStatus(),
                        automationAgentService.parseJson(command.getOutputPayload()),
                        command.getErrorMessage(),
                        commandTokenUsage(principal, command),
                        command.getCreatedAt(),
                        command.getCompletedAt()
                ))
                .toList();
    }

    private Map<String, Object> commandTokenUsage(
            PlatformPrincipal principal,
            AutomationCommand command
    ) {
        long inputTokens = 0;
        long outputTokens = 0;
        long cacheReadTokens = 0;
        long cacheWriteTokens = 0;
        long reasoningTokens = 0;
        long totalTokens = 0;
        long modelCalls = 0;
        boolean reported = false;
        for (var event : automationAgentService.commandEvents(
                principal.organizationId(), principal.userId(), command.getId()
        )) {
            Object payloadValue = automationAgentService.parseJson(event.getPayload());
            if (!(payloadValue instanceof Map<?, ?> payload)) continue;
            Object usageValue = payload.get("tokenUsage");
            if (!(usageValue instanceof Map<?, ?> usage)) continue;
            inputTokens += longValue(usage.get("inputTokens"));
            outputTokens += longValue(usage.get("outputTokens"));
            cacheReadTokens += longValue(usage.get("cacheReadTokens"));
            cacheWriteTokens += longValue(usage.get("cacheWriteTokens"));
            reasoningTokens += longValue(usage.get("reasoningTokens"));
            totalTokens += longValue(usage.get("totalTokens"));
            modelCalls += longValue(usage.get("modelCalls"));
            reported = reported || Boolean.TRUE.equals(usage.get("reported"));
        }
        return Map.of(
                "inputTokens", inputTokens,
                "outputTokens", outputTokens,
                "cacheReadTokens", cacheReadTokens,
                "cacheWriteTokens", cacheWriteTokens,
                "reasoningTokens", reasoningTokens,
                "totalTokens", totalTokens,
                "modelCalls", modelCalls,
                "reported", reported
        );
    }

    private long longValue(Object value) {
        if (value instanceof Number number) return Math.max(0, number.longValue());
        try {
            return Math.max(0, Long.parseLong(String.valueOf(value)));
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private String readableMessage(RestClientException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) return "模型服务不可用";
        String normalized = message.toLowerCase();
        if (normalized.contains("system_cpu_overloaded")
                || normalized.contains("status_code=503")
                || normalized.contains("模型服务当前繁忙")) {
            return "模型服务当前繁忙，系统已自动重试 3 次，请稍后再试";
        }
        if (normalized.contains("status_code=429")
                || normalized.contains("rate limit")
                || normalized.contains("too many requests")) {
            return "模型服务请求过于频繁，请稍后再试";
        }
        if (normalized.contains("timeout") || normalized.contains("timed out")) {
            return "模型服务响应超时，请稍后再试";
        }
        if (normalized.contains("connection error")
                || normalized.contains("econnreset")
                || normalized.contains("connection reset")) {
            return "模型 API 网络连接失败，请检查 API 地址以及宿主机或 Docker 的代理网络";
        }
        return message.length() > 240 ? message.substring(0, 240) : message;
    }

    public record AssistantStatus(
            boolean piOnline,
            String sdk,
            boolean modelConfigured,
            String modelId,
            String baseUrl,
            boolean imageModelConfigured,
            String imageModelId,
            boolean agentOnline,
            String agentId,
            String agentName,
            String agentOs,
            Instant lastSeenAt
    ) {
    }

    public record ChatResult(
            String sessionId,
            String reply,
            String modelId,
            long latencyMs,
            Map<String, Object> usage,
            List<Map<String, Object>> trace,
            List<QueuedCommand> commands,
            List<GeneratedArtifact> artifacts
    ) {
    }

    public record QueuedCommand(String id, String commandType, String status) {
    }

    public record GeneratedArtifact(
            String id,
            String name,
            long size,
            String contentType,
            String modelId,
            String mode,
            String prompt,
            String revisedPrompt,
            int retentionDays
    ) {
    }

    public record CommandStatus(
            String id,
            String commandType,
            String status,
            Object output,
            String errorMessage,
            Map<String, Object> tokenUsage,
            Instant createdAt,
            Instant completedAt
    ) {
    }

    private record PiRequest(
            String sessionId,
            String prompt,
            PiModel model,
            PiImageModel imageModel,
            List<PiInputImage> inputImages
    ) {
    }

    private record PiModel(String baseUrl, String modelId, String apiKey, String apiFormat) {
    }

    private record PiImageModel(String baseUrl, String modelId, String apiKey, String apiFormat,
                                List<Map<String, Object>> copyrightFallbackModels) {
    }

    private record PiInputImage(String name, String mimeType, String dataBase64) {
    }

    private record PiResponse(
            String sessionId,
            String reply,
            List<PiAction> actions,
            List<PiArtifact> artifacts,
            List<Map<String, Object>> trace,
            Map<String, Object> usage,
            long latencyMs,
            String modelId
    ) {
    }

    private record PiAction(String actionId, String commandType, Object payload) {
    }

    private record PiArtifact(
            String name,
            String mimeType,
            String dataBase64,
            String modelId,
            String mode,
            String prompt,
            String revisedPrompt
    ) {
    }

    private record PiHealth(
            String status,
            String sdk,
            int chatSessions,
            int automationAgentSessions
    ) {
    }
}
