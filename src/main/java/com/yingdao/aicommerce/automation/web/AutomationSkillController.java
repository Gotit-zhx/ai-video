package com.yingdao.aicommerce.automation.web;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.automation.application.AutomationAgentService;
import com.yingdao.aicommerce.automation.application.AutomationAttachmentService;
import com.yingdao.aicommerce.automation.application.AutomationSkillService;
import com.yingdao.aicommerce.automation.application.AutomationModelService;
import com.yingdao.aicommerce.automation.domain.AutomationAgent;
import com.yingdao.aicommerce.automation.domain.AutomationCapability;
import com.yingdao.aicommerce.automation.domain.AutomationCommand;
import com.yingdao.aicommerce.automation.domain.AutomationCommandEvent;
import com.yingdao.aicommerce.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
public class AutomationSkillController {
    private final AutomationSkillService service;
    private final AutomationAgentService agentService;
    private final AutomationAttachmentService attachmentService;

    public AutomationSkillController(
            AutomationSkillService service,
            AutomationAgentService agentService,
            AutomationAttachmentService attachmentService
    ) {
        this.service = service;
        this.agentService = agentService;
        this.attachmentService = attachmentService;
    }

    @GetMapping("/api/v1/automations")
    public ApiResponse<List<SkillResponse>> granted(
            @AuthenticationPrincipal PlatformPrincipal principal
    ) {
        return ApiResponse.ok(service.granted(principal).stream()
                .map(SkillResponse::from)
                .toList());
    }

    @PostMapping("/api/v1/automations/attachments")
    public ApiResponse<AttachmentResponse> uploadAttachment(
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal PlatformPrincipal principal
    ) {
        var attachment = attachmentService.store(file, principal);
        return ApiResponse.created(new AttachmentResponse(
                attachment.id(), attachment.name(), attachment.size(), attachment.reference()
        ));
    }

    @GetMapping("/api/v1/automations/attachments/{attachmentId}")
    public ResponseEntity<Resource> downloadAttachment(
            @PathVariable String attachmentId,
            @AuthenticationPrincipal PlatformPrincipal principal
    ) {
        var attachment = attachmentService.loadForUser(attachmentId, principal);
        return attachmentResponse(attachment);
    }

    private ResponseEntity<Resource> attachmentResponse(
            AutomationAttachmentService.AttachmentFile attachment
    ) {
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

    @PostMapping("/api/v1/automations/{code}/execute")
    public ApiResponse<JobResponse> execute(
            @PathVariable String code,
            @RequestBody(required = false) Map<String, Object> input,
            @RequestHeader(value = "X-Heyi-Agent-Id", required = false) String localAgentId,
            @AuthenticationPrincipal PlatformPrincipal principal
    ) {
        return ApiResponse.created(JobResponse.from(
                service.execute(code, input, principal, localAgentId), agentService
        ));
    }

    @PostMapping("/api/v1/automations/{code}/plan")
    public ApiResponse<AutomationPlanResponse> plan(
            @PathVariable String code,
            @RequestBody(required = false) Map<String, Object> input,
            @AuthenticationPrincipal PlatformPrincipal principal
    ) {
        return ApiResponse.ok(AutomationPlanResponse.from(
                service.plan(code, input, principal)
        ));
    }

    @PostMapping("/api/v1/automations/assistant/connect")
    public ApiResponse<AssistantConnectionResponse> connectAssistant(
            @Valid @RequestBody AssistantConnectionRequest request,
            @AuthenticationPrincipal PlatformPrincipal principal
    ) {
        var connection = agentService.connectCurrentUser(
                principal.organizationId(), principal.userId(), request.agentId()
        );
        return ApiResponse.ok(AssistantConnectionResponse.from(connection));
    }

    @GetMapping("/api/v1/automations/jobs")
    public ApiResponse<List<JobResponse>> jobs(
            @AuthenticationPrincipal PlatformPrincipal principal
    ) {
        return ApiResponse.ok(agentService.userCommands(
                principal.organizationId(), principal.userId()
        ).stream().map(command -> JobResponse.from(command, agentService)).toList());
    }

    @GetMapping("/api/v1/automations/jobs/{jobId}")
    public ApiResponse<JobResponse> job(
            @PathVariable String jobId,
            @AuthenticationPrincipal PlatformPrincipal principal
    ) {
        return ApiResponse.ok(JobResponse.from(
                agentService.userCommand(
                        principal.organizationId(), principal.userId(), jobId
                ),
                agentService
        ));
    }

    @GetMapping("/api/v1/automations/jobs/{jobId}/events")
    public ApiResponse<List<JobEventResponse>> events(
            @PathVariable String jobId,
            @AuthenticationPrincipal PlatformPrincipal principal
    ) {
        return ApiResponse.ok(agentService.commandEvents(
                principal.organizationId(), principal.userId(), jobId
        ).stream().map(event -> JobEventResponse.from(event, agentService)).toList());
    }

    @PostMapping("/api/v1/automations/jobs/{jobId}/control")
    public ApiResponse<JobResponse> control(
            @PathVariable String jobId,
            @Valid @RequestBody JobControlRequest request,
            @AuthenticationPrincipal PlatformPrincipal principal
    ) {
        return ApiResponse.ok(JobResponse.from(
                agentService.controlByUser(
                        principal.organizationId(), principal.userId(),
                        jobId, request.action()
                ),
                agentService
        ));
    }

    @GetMapping("/api/v1/system/automation-skills")
    public ApiResponse<List<SkillResponse>> all() {
        return ApiResponse.ok(service.all().stream().map(SkillResponse::from).toList());
    }

    @PostMapping("/api/v1/system/automation-skills/upload")
    public ApiResponse<SkillResponse> upload(
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "files", required = false) List<MultipartFile> files,
            @RequestParam(value = "name", required = false) String name
    ) {
        return ApiResponse.created(SkillResponse.from(service.upload(uploadParts(file, files), name)));
    }

    @PostMapping("/api/v1/system/automation-skills/convert")
    public ApiResponse<SkillResponse> convert(
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "files", required = false) List<MultipartFile> files,
            @RequestParam(value = "name", required = false) String name
    ) {
        return ApiResponse.created(SkillResponse.from(service.upload(uploadParts(file, files), name)));
    }

    private List<MultipartFile> uploadParts(
            MultipartFile file,
            List<MultipartFile> files
    ) {
        List<MultipartFile> parts = new ArrayList<>();
        if (files != null) parts.addAll(files);
        if (file != null && !file.isEmpty()) parts.add(file);
        return parts;
    }

    @PostMapping("/api/v1/system/automation-skills/{code}/activate")
    public ApiResponse<SkillResponse> activate(@PathVariable String code) {
        return ApiResponse.ok(SkillResponse.from(service.activate(code)));
    }

    @DeleteMapping("/api/v1/system/automation-skills/{code}")
    public ApiResponse<Void> delete(@PathVariable String code) {
        service.deleteImported(code);
        return ApiResponse.ok(null);
    }

    @PutMapping("/api/v1/system/automation-skills/{code}/form-schema")
    public ApiResponse<SkillResponse> updateFormSchema(
            @PathVariable String code,
            @RequestBody Map<String, Object> schema
    ) {
        return ApiResponse.ok(SkillResponse.from(
                service.updateInputSchema(code, schema)
        ));
    }

    @PutMapping("/api/v1/system/automation-skills/{code}/runtime-config")
    public ApiResponse<SkillResponse> updateRuntimeConfig(
            @PathVariable String code,
            @RequestBody Map<String, Object> runtimeConfig
    ) {
        return ApiResponse.ok(SkillResponse.from(
                service.updateRuntimeConfig(code, runtimeConfig)
        ));
    }

    @GetMapping("/api/v1/system/automation-agents")
    public ApiResponse<List<AgentResponse>> agents() {
        return ApiResponse.ok(service.agents().stream().map(AgentResponse::from).toList());
    }

    @GetMapping("/api/v1/system/users/{userId}/automation-access")
    public ApiResponse<AutomationAccessResponse> access(@PathVariable String userId) {
        var access = service.access(userId);
        return ApiResponse.ok(new AutomationAccessResponse(
                access.automationCodes()
        ));
    }

    @PutMapping("/api/v1/system/users/{userId}/automation-access")
    public ApiResponse<AutomationAccessResponse> updateAccess(
            @PathVariable String userId,
            @Valid @RequestBody UpdateAutomationAccessRequest request,
            @AuthenticationPrincipal PlatformPrincipal principal
    ) {
        var access = service.updateAccess(
                userId,
                request.automationCodes(),
                principal.userId()
        );
        return ApiResponse.ok(new AutomationAccessResponse(
                access.automationCodes()
        ));
    }

    public record UpdateAutomationAccessRequest(
            List<@NotBlank String> automationCodes
    ) {}

    public record JobControlRequest(@NotBlank String action) {}

    public record AutomationAccessResponse(
            List<String> automationCodes
    ) {}

    public record AssistantConnectionRequest(@NotBlank String agentId) {}

    public record AutomationPlanResponse(
            String summary,
            Map<String, Object> parameters,
            List<String> assumptions,
            List<String> warnings,
            String modelId,
            long latencyMs,
            boolean cached,
            boolean modelUsed
    ) {
        static AutomationPlanResponse from(
                AutomationModelService.PlanningResult plan
        ) {
            return new AutomationPlanResponse(
                    plan.summary(), plan.parameters(), plan.assumptions(),
                    plan.warnings(), plan.modelId(), plan.latencyMs(),
                    plan.cached(), plan.modelPlanned()
            );
        }
    }

    public record AssistantConnectionResponse(
            String agentId,
            String name,
            String osName,
            boolean online,
            boolean acceptingJobs,
            java.time.Instant lastSeenAt
    ) {
        static AssistantConnectionResponse from(
                AutomationAgentService.AgentConnection connection
        ) {
            return new AssistantConnectionResponse(
                    connection.agentId(), connection.name(), connection.osName(),
                    connection.online(), connection.acceptingJobs(),
                    connection.lastSeenAt()
            );
        }
    }

    public record SkillResponse(
            String code,
            String name,
            String version,
            String description,
            String supportedOs,
            String inputSchema,
            String runtimeConfig,
            String sourceType,
            String runnerType,
            String conversionReport,
            String status
    ) {
        static SkillResponse from(AutomationCapability capability) {
            return new SkillResponse(
                    capability.getCode(), capability.getName(), capability.getVersion(),
                    capability.getDescription(), capability.getSupportedOs(),
                    capability.getInputSchema(), capability.getRuntimeConfig(),
                    capability.getSourceType(),
                    capability.getRunnerType(), capability.getConversionReport(),
                    capability.getStatus()
            );
        }
    }

    public record AgentResponse(
            String id,
            String name,
            String osName,
            String status,
            boolean acceptingJobs,
            String boundUserId,
            java.time.Instant lastSeenAt
    ) {
        static AgentResponse from(AutomationAgent agent) {
            return new AgentResponse(
                    agent.getId(), agent.getName(), agent.getOsName(),
                    agent.getStatus(), agent.isAcceptingJobs(),
                    agent.getBoundUserId(), agent.getLastSeenAt()
            );
        }
    }

    public record AttachmentResponse(String id, String name, long size, String reference) {
    }

    public record JobResponse(
            String id,
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
        static JobResponse from(
                AutomationCommand command,
                AutomationAgentService agentService
        ) {
            return new JobResponse(
                    command.getId(), command.getAutomationCode(), command.getStatus(),
                    command.getProgressPercent(), command.getProgressMessage(),
                    agentService.parseJson(command.getInputPayload()),
                    agentService.parseJson(command.getOutputPayload()),
                    command.getErrorMessage(), command.getCreatedAt(),
                    command.getCompletedAt()
            );
        }
    }

    public record JobEventResponse(
            String id,
            String eventType,
            String source,
            Integer progressPercent,
            String message,
            Object payload,
            java.time.Instant createdAt
    ) {
        static JobEventResponse from(
                AutomationCommandEvent event,
                AutomationAgentService agentService
        ) {
            return new JobEventResponse(
                    event.getId(), event.getEventType(), event.getSource(),
                    event.getProgressPercent(), event.getMessage(),
                    agentService.parseJson(event.getPayload()), event.getCreatedAt()
            );
        }
    }
}
