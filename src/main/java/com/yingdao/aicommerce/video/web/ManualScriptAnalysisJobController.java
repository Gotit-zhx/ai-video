package com.yingdao.aicommerce.video.web;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.api.ApiResponse;
import com.yingdao.aicommerce.video.application.ManualScriptAnalysisJobService;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/v1/video-scripts/manual/analysis-jobs")
public class ManualScriptAnalysisJobController {
    private final ManualScriptAnalysisJobService service;

    public ManualScriptAnalysisJobController(ManualScriptAnalysisJobService service) {
        this.service = service;
    }

    @PostMapping("/prompt")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<ManualScriptAnalysisJobService.JobView> startPrompt(
            @RequestBody PromptRequest request,
            @AuthenticationPrincipal PlatformPrincipal principal
    ) {
        return ApiResponse.ok(service.startPrompt(request.prompt(), request.targetMarket(), request.name(),
                request.visualTone(), request.presentationMode(), principal));
    }

    @PostMapping(value = "/product", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<ManualScriptAnalysisJobService.JobView> startProduct(
            @RequestParam("images") List<MultipartFile> images,
            @RequestParam("imageContexts") List<@Size(max = 1200) String> imageContexts,
            @RequestParam(value = "requirements", required = false) @Size(max = 12000) String requirements,
            @RequestParam(value = "productContext", required = false) @Size(max = 4000) String productContext,
            @RequestParam(value = "targetMarket", required = false) @Size(max = 80) String targetMarket,
            @RequestParam(value = "name", required = false) @Size(max = 160) String name,
            @RequestParam(value = "visualTone", required = false) @Size(max = 4000) String visualTone,
            @RequestParam("presentationMode") @Size(max = 32) String presentationMode,
            @AuthenticationPrincipal PlatformPrincipal principal
    ) {
        return ApiResponse.ok(service.startProduct(images, imageContexts, requirements, productContext,
                targetMarket, name, visualTone, presentationMode, principal));
    }

    @GetMapping("/{jobId}")
    public ApiResponse<ManualScriptAnalysisJobService.JobView> get(
            @PathVariable String jobId,
            @AuthenticationPrincipal PlatformPrincipal principal
    ) {
        return ApiResponse.ok(service.get(jobId, principal));
    }

    public record PromptRequest(@Size(max = 12000) String prompt,
                                @Size(max = 80) String targetMarket,
                                @Size(max = 160) String name,
                                @Size(max = 4000) String visualTone,
                                @Size(max = 32) String presentationMode) {}
}
