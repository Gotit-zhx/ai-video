package com.yingdao.aicommerce.automation.web;

import com.yingdao.aicommerce.automation.application.AutomationAgentService;
import com.yingdao.aicommerce.automation.application.AutomationAttachmentService;
import com.yingdao.aicommerce.automation.application.AutomationModelService;
import com.yingdao.aicommerce.automation.domain.AutomationCommand;
import com.yingdao.aicommerce.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/automation-agent")
public class AutomationAgentController {

    private final AutomationAgentService service;
    private final AutomationAttachmentService attachmentService;
    private final AutomationModelService modelService;

    public AutomationAgentController(
            AutomationAgentService service,
            AutomationAttachmentService attachmentService,
            AutomationModelService modelService
    ) {
        this.service = service;
        this.attachmentService = attachmentService;
        this.modelService = modelService;
    }

    @PostMapping("/heartbeat")
    public ApiResponse<HeartbeatResponse> heartbeat(
            @RequestHeader("X-Agent-Token") String token,
            @Valid @RequestBody HeartbeatRequest request
    ) {
        var agent = service.heartbeat(
                token,
                request.agentId(),
                request.organizationCode(),
                request.name(),
                request.osName(),
                request.version(),
                request.acceptingJobs()
        );
        return ApiResponse.ok(new HeartbeatResponse(agent.getId(), agent.getStatus(), agent.getLastSeenAt()));
    }

    @GetMapping("/{agentId}/commands/next")
    public ApiResponse<CommandResponse> next(
            @RequestHeader("X-Agent-Token") String token,
            @PathVariable String agentId
    ) {
        AutomationCommand command = service.claimNext(token, agentId);
        return ApiResponse.ok(command == null ? null : CommandResponse.from(command, service));
    }

    @GetMapping("/{agentId}/attachments/{attachmentId}")
    public ResponseEntity<Resource> attachment(
            @RequestHeader("X-Agent-Token") String token,
            @PathVariable String agentId,
            @PathVariable String attachmentId
    ) {
        service.authorizeAgent(token, agentId);
        var attachment = attachmentService.load(attachmentId);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(attachment.size())
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(attachment.name(), StandardCharsets.UTF_8)
                                .build().toString()
                )
                .body(new FileSystemResource(attachment.path()));
    }

    @PostMapping("/{agentId}/commands/{commandId}/artifacts")
    public ApiResponse<ArtifactResponse> uploadArtifact(
            @RequestHeader("X-Agent-Token") String token,
            @PathVariable String agentId,
            @PathVariable String commandId,
            @org.springframework.web.bind.annotation.RequestParam("file") MultipartFile file
    ) {
        AutomationCommand command = service.authorizeCommand(token, agentId, commandId);
        var attachment = attachmentService.storeOutput(
                file, command.getOrganizationId(), command.getCreatedBy()
        );
        return ApiResponse.created(new ArtifactResponse(
                attachment.id(), attachment.name(), attachment.size(), 3
        ));
    }

    @PostMapping("/{agentId}/commands/{commandId}/complete")
    public ApiResponse<CommandResponse> complete(
            @RequestHeader("X-Agent-Token") String token,
            @PathVariable String agentId,
            @PathVariable String commandId,
            @Valid @RequestBody CompleteCommandRequest request
    ) {
        return ApiResponse.ok(CommandResponse.from(service.complete(
                token,
                agentId,
                commandId,
                request.success(),
                request.output(),
                request.errorMessage()
        ), service));
    }

    @PostMapping("/{agentId}/commands/{commandId}/progress")
    public ApiResponse<CommandResponse> progress(
            @RequestHeader("X-Agent-Token") String token,
            @PathVariable String agentId,
            @PathVariable String commandId,
            @Valid @RequestBody ProgressCommandRequest request
    ) {
        return ApiResponse.ok(CommandResponse.from(service.progress(
                token,
                agentId,
                commandId,
                request.percent(),
                request.message(),
                request.eventType(),
                request.source(),
                request.payload()
        ), service));
    }

    @PostMapping("/{agentId}/commands/{commandId}/recover")
    public ApiResponse<AutomationModelService.RecoveryAdvice> recover(
            @RequestHeader("X-Agent-Token") String token,
            @PathVariable String agentId,
            @PathVariable String commandId,
            @Valid @RequestBody RecoveryRequest request
    ) {
        return ApiResponse.ok(service.recover(
                token,
                agentId,
                commandId,
                request.attempt(),
                request.errorMessage(),
                request.toolResult(),
                request.contextKey(),
                modelService
        ));
    }

    @GetMapping("/{agentId}/commands/{commandId}/control")
    public ApiResponse<ControlResponse> control(
            @RequestHeader("X-Agent-Token") String token,
            @PathVariable String agentId,
            @PathVariable String commandId
    ) {
        AutomationCommand command = service.agentControl(token, agentId, commandId);
        String action = command.getStatus().endsWith("_REQUESTED")
                ? command.getStatus().replace("_REQUESTED", "")
                : null;
        return ApiResponse.ok(new ControlResponse(command.getStatus(), action));
    }

    @PostMapping("/{agentId}/commands/{commandId}/control/ack")
    public ApiResponse<CommandResponse> acknowledgeControl(
            @RequestHeader("X-Agent-Token") String token,
            @PathVariable String agentId,
            @PathVariable String commandId,
            @Valid @RequestBody ControlAcknowledgeRequest request
    ) {
        return ApiResponse.ok(CommandResponse.from(service.acknowledgeControl(
                token, agentId, commandId, request.action()
        ), service));
    }

    public record HeartbeatRequest(
            @NotBlank String agentId,
            @NotBlank String organizationCode,
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Size(max = 80) String osName,
            @NotBlank @Size(max = 40) String version,
            boolean acceptingJobs
    ) {
    }

    public record HeartbeatResponse(String agentId, String status, java.time.Instant lastSeenAt) {
    }

    public record CompleteCommandRequest(
            @NotNull Boolean success,
            Object output,
            @Size(max = 2000) String errorMessage
    ) {
    }

    public record ProgressCommandRequest(
            @jakarta.validation.constraints.Min(0)
            @jakarta.validation.constraints.Max(99)
            int percent,
            @NotBlank @Size(max = 4000) String message,
            @Size(max = 32) String eventType,
            @Size(max = 32) String source,
            Object payload
    ) {
    }

    public record RecoveryRequest(
            @jakarta.validation.constraints.Min(1)
            @jakarta.validation.constraints.Max(6)
            int attempt,
            @NotBlank @Size(max = 4000) String errorMessage,
            Map<String, Object> toolResult,
            @Size(max = 120) String contextKey
    ) {
    }

    public record ControlResponse(String status, String action) {
    }

    public record ArtifactResponse(String id, String name, long size, int retentionDays) {
    }

    public record ControlAcknowledgeRequest(@NotBlank String action) {
    }

    public record CommandResponse(
            String id,
            String sessionId,
            String commandType,
            String automationCode,
            String status,
            int progressPercent,
            String progressMessage,
            Object input,
            Object output,
            String errorMessage,
            java.time.Instant createdAt,
            java.time.Instant completedAt
    ) {
        static CommandResponse from(AutomationCommand command, AutomationAgentService service) {
            return new CommandResponse(
                    command.getId(),
                    command.getSessionId(),
                    command.getCommandType(),
                    command.getAutomationCode(),
                    command.getStatus(),
                    command.getProgressPercent(),
                    command.getProgressMessage(),
                    service.parseJson(command.getInputPayload()),
                    service.parseJson(command.getOutputPayload()),
                    command.getErrorMessage(),
                    command.getCreatedAt(),
                    command.getCompletedAt()
            );
        }
    }
}
