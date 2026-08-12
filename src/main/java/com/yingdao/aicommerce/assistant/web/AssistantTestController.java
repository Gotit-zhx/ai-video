package com.yingdao.aicommerce.assistant.web;

import com.yingdao.aicommerce.assistant.application.AssistantTestService;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.automation.application.AutomationAttachmentService;
import com.yingdao.aicommerce.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/v1/assistant-test")
public class AssistantTestController {

    private final AssistantTestService service;
    private final AutomationAttachmentService attachmentService;

    public AssistantTestController(
            AssistantTestService service,
            AutomationAttachmentService attachmentService
    ) {
        this.service = service;
        this.attachmentService = attachmentService;
    }

    @GetMapping("/status")
    public ApiResponse<AssistantTestService.AssistantStatus> status(
            Authentication authentication,
            @RequestHeader(value = "X-Heyi-Agent-Id", required = false) String localAgentId
    ) {
        return ApiResponse.ok(service.status(
                (PlatformPrincipal) authentication.getPrincipal(),
                localAgentId
        ));
    }

    @PostMapping("/chat")
    public ApiResponse<AssistantTestService.ChatResult> chat(
            Authentication authentication,
            @RequestHeader(value = "X-Heyi-Agent-Id", required = false) String localAgentId,
            @Valid @RequestBody ChatRequest request
    ) {
        return ApiResponse.ok(service.chat(
                (PlatformPrincipal) authentication.getPrincipal(),
                request.sessionId(),
                request.prompt(),
                request.attachmentIds(),
                localAgentId
        ));
    }

    @GetMapping("/artifacts/{attachmentId}")
    public ResponseEntity<Resource> previewArtifact(
            Authentication authentication,
            @PathVariable String attachmentId,
            @RequestParam(defaultValue = "false") boolean download
    ) {
        PlatformPrincipal principal = (PlatformPrincipal) authentication.getPrincipal();
        var attachment = attachmentService.loadForUser(attachmentId, principal);
        MediaType mediaType;
        try {
            mediaType = MediaType.parseMediaType(attachment.contentType());
        } catch (IllegalArgumentException exception) {
            mediaType = MediaType.APPLICATION_OCTET_STREAM;
        }
        return ResponseEntity.ok()
                .contentType(mediaType)
                .contentLength(attachment.size())
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        (download ? ContentDisposition.attachment() : ContentDisposition.inline())
                                .filename(attachment.name(), StandardCharsets.UTF_8)
                                .build().toString()
                )
                .body(new FileSystemResource(attachment.path()));
    }

    @GetMapping("/commands")
    public ApiResponse<List<AssistantTestService.CommandStatus>> commands(
            Authentication authentication,
            @RequestParam
            @NotBlank
            @Pattern(regexp = "[A-Za-z0-9_-]{8,80}")
            String sessionId
    ) {
        return ApiResponse.ok(service.commands(
                (PlatformPrincipal) authentication.getPrincipal(),
                sessionId
        ));
    }

    public record ChatRequest(
            @NotBlank
            @Pattern(regexp = "[A-Za-z0-9_-]{8,80}")
            String sessionId,
            @NotBlank @Size(max = 4000) String prompt,
            @Size(max = 1) List<@Pattern(regexp = "[a-f0-9]{32}") String> attachmentIds
    ) {
    }
}
