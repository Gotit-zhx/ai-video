package com.yingdao.aicommerce.video.web;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.api.ApiResponse;
import com.yingdao.aicommerce.video.application.VideoScriptService;
import com.yingdao.aicommerce.video.application.ManualScriptAiService;
import com.yingdao.aicommerce.video.domain.VideoScript;
import com.yingdao.aicommerce.video.domain.VideoScriptShot;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.FileSystemResource;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Validated
@RestController
@RequestMapping("/api/v1/video-scripts")
public class VideoScriptController {
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.yingdao.aicommerce.storage.LocalMaterialOssArchiveService materialArchive;

    private final VideoScriptService service;
    private final ManualScriptAiService manualScriptAiService;

    public VideoScriptController(VideoScriptService service, ManualScriptAiService manualScriptAiService) {
        this.service = service;
        this.manualScriptAiService = manualScriptAiService;
    }

    @PostMapping("/manual/analyze")
    public ApiResponse<ManualScriptAiService.Draft> analyzeManualPrompt(
            @RequestBody ManualScriptAiRequest request,
            @AuthenticationPrincipal PlatformPrincipal principal
    ) {
        return ApiResponse.ok(manualScriptAiService.analyze(
                request.prompt(), request.targetMarket(), request.name(), request.visualTone(),
                request.presentationMode(), principal));
    }

    @PostMapping(value = "/manual/analyze-product", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ManualScriptAiService.Draft> analyzeManualProduct(
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
        return ApiResponse.ok(manualScriptAiService.analyzeProduct(
                images, imageContexts, requirements, productContext, targetMarket, name, visualTone,
                presentationMode, principal));
    }

    @PostMapping(value = "/analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<VideoScriptSummaryResponse> analyze(
            @RequestParam("video") MultipartFile video,
            @RequestParam(value = "name", required = false) @Size(max = 160) String name,
            @RequestParam(value = "targetMarket", required = false) @Size(max = 80) String targetMarket,
            @RequestParam(value = "requirements", required = false) @Size(max = 4000) String requirements,
            @RequestParam("presentationMode") @Size(max = 32) String presentationMode,
            @RequestParam(value = "extractAudio", defaultValue = "false") boolean extractAudio,
            @RequestParam(value = "clipStartSecond", defaultValue = "0") BigDecimal clipStartSecond,
            @RequestParam(value = "clipEndSecond", required = false) BigDecimal clipEndSecond,
            @AuthenticationPrincipal PlatformPrincipal principal
    ) {
        return ApiResponse.created(VideoScriptSummaryResponse.from(
                service.createAnalysis(video, name, targetMarket, requirements, presentationMode,
                        extractAudio, clipStartSecond, clipEndSecond, principal)
        ));
    }

    @PostMapping("/manual")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<VideoScriptSummaryResponse> createManual(
            @RequestBody ManualScriptRequest request,
            @AuthenticationPrincipal PlatformPrincipal principal
    ) {
        List<VideoScriptService.ManualShot> shots = request.shots() == null ? List.of() : request.shots().stream()
                .map(shot -> new VideoScriptService.ManualShot(
                        shot.stage(), shot.title(), shot.startSecond(), shot.endSecond(),
                        shot.sceneDescription(), shot.motionDescription(), shot.narration(),
                        shot.shotSize(), shot.cameraMovement(), shot.emotion()
                )).toList();
        return ApiResponse.created(VideoScriptSummaryResponse.from(
                service.createManual(request.name(), request.targetMarket(), request.visualTone(),
                        request.presentationMode(), shots, principal)
        ));
    }

    @GetMapping
    public ApiResponse<List<VideoScriptSummaryResponse>> list(
            @AuthenticationPrincipal PlatformPrincipal principal
    ) {
        return ApiResponse.ok(service.list(principal).stream()
                .map(VideoScriptSummaryResponse::from)
                .toList());
    }

    @GetMapping("/current")
    public ApiResponse<VideoScriptSummaryResponse> current(
            @AuthenticationPrincipal PlatformPrincipal principal
    ) {
        VideoScript current = service.currentBlocking(principal);
        return ApiResponse.ok(current == null ? null : VideoScriptSummaryResponse.from(current));
    }

    @PostMapping("/{id}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<VideoScriptSummaryResponse> retry(
            @PathVariable String id,
            @AuthenticationPrincipal PlatformPrincipal principal
    ) {
        return ApiResponse.ok(VideoScriptSummaryResponse.from(service.retry(id, principal)));
    }

    @PostMapping("/{id}/reanalyze")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<VideoScriptSummaryResponse> reanalyze(
            @PathVariable String id,
            @AuthenticationPrincipal PlatformPrincipal principal
    ) {
        return ApiResponse.ok(VideoScriptSummaryResponse.from(service.reanalyze(id, principal)));
    }

    @PostMapping("/{id}/copy")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<VideoScriptSummaryResponse> copy(
            @PathVariable String id,
            @AuthenticationPrincipal PlatformPrincipal principal
    ) {
        return ApiResponse.created(VideoScriptSummaryResponse.from(service.copy(id, principal)));
    }

    @PatchMapping("/{id}/visual-tone")
    public ApiResponse<VideoScriptSummaryResponse> updateVisualTone(
            @PathVariable String id,
            @RequestBody UpdateVisualToneRequest request,
            @AuthenticationPrincipal PlatformPrincipal principal
    ) {
        return ApiResponse.ok(VideoScriptSummaryResponse.from(
                service.updateVisualTone(id, request.visualTone(), principal)
        ));
    }

    @GetMapping("/{id}")
    public ApiResponse<VideoScriptDetailResponse> detail(
            @PathVariable String id,
            @AuthenticationPrincipal PlatformPrincipal principal
    ) {
        VideoScriptService.VideoScriptDetail detail = service.detail(id, principal);
        return ApiResponse.ok(VideoScriptDetailResponse.from(detail));
    }

    @GetMapping("/{id}/status")
    public ApiResponse<VideoScriptStatusResponse> status(
            @PathVariable String id,
            @AuthenticationPrincipal PlatformPrincipal principal
    ) {
        return ApiResponse.ok(VideoScriptStatusResponse.from(service.status(id, principal)));
    }

    @GetMapping(value = "/{scriptId}/shots/{shotId}/preview", produces = MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<?> shotPreview(
            @PathVariable String scriptId,
            @PathVariable String shotId,
            @AuthenticationPrincipal PlatformPrincipal principal
    ) {
        VideoScriptService.ShotPreview preview = service.shotPreview(scriptId, shotId, principal);
        String archived = materialArchive == null ? null : materialArchive.archivedUrl(preview.path());
        if (archived != null) return ResponseEntity.status(HttpStatus.TEMPORARY_REDIRECT)
                .location(java.net.URI.create(archived)).cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS).cachePrivate()).build();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS).cachePrivate())
                .lastModified(preview.lastModified())
                .contentType(MediaType.IMAGE_JPEG)
                .body(new FileSystemResource(preview.path()));
    }

    @DeleteMapping("/{scriptId}/shots/{shotId}")
    public ApiResponse<VideoScriptDetailResponse> deleteShot(@PathVariable String scriptId,
            @PathVariable String shotId, @AuthenticationPrincipal PlatformPrincipal principal) {
        return ApiResponse.ok(VideoScriptDetailResponse.from(service.deleteShot(scriptId, shotId, principal)));
    }

    @PatchMapping("/{scriptId}/shots/{shotId}")
    public ApiResponse<VideoScriptShotResponse> updateShot(
            @PathVariable String scriptId,
            @PathVariable String shotId,
            @RequestBody UpdateShotRequest request,
            @AuthenticationPrincipal PlatformPrincipal principal
    ) {
        VideoScriptShot shot = service.updateShot(
                scriptId,
                shotId,
                new VideoScriptService.ShotUpdate(
                        request.stage(), request.title(), request.sceneDescription(),
                        request.motionDescription(), request.motionIntensity(), request.narration(),
                        request.shotSize(), request.cameraMovement(), request.transitionDescription(), request.emotion()
                ),
                principal
        );
        return ApiResponse.ok(VideoScriptShotResponse.from(shot));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable String id,
            @AuthenticationPrincipal PlatformPrincipal principal
    ) {
        service.delete(id, principal);
    }

    public record VideoScriptSummaryResponse(
            String id,
            String organizationId,
            String name,
            String sourceType,
            String presentationMode,
            String sourceFileName,
            BigDecimal sourceDurationSeconds,
            BigDecimal clipStartSecond,
            BigDecimal clipEndSecond,
            String targetMarket,
            boolean extractAudio,
            String status,
            int progress,
            String analysisRunId,
            String analysisStage,
            String analysisOutput,
            long analysisOutputCharacters,
            Instant analysisOutputUpdatedAt,
            String summary,
            String visualTone,
            String modelId,
            String errorMessage,
            int version,
            Instant createdAt,
            Instant updatedAt,
            Instant completedAt
    ) {
        static VideoScriptSummaryResponse from(VideoScript script) {
            return from(script, false);
        }

        static VideoScriptSummaryResponse from(VideoScript script, boolean includeOutput) {
            return new VideoScriptSummaryResponse(
                    script.getId(),
                    script.getOrganizationId(),
                    script.getName(),
                    script.getSourceType(),
                    script.getPresentationMode(),
                    script.getSourceFileName(),
                    script.getSourceDurationSeconds(),
                    script.getClipStartSecond(),
                    script.getClipEndSecond(),
                    script.getTargetMarket(),
                    script.isExtractAudio(),
                    script.getStatus(),
                    script.getProgress(),
                    script.getAnalysisRunId(),
                    script.getAnalysisStage(),
                    includeOutput ? script.getAnalysisOutput() : null,
                    script.getAnalysisOutputCharacters(),
                    script.getAnalysisOutputUpdatedAt(),
                    script.getSummary(),
                    script.getVisualTone(),
                    script.getModelId(),
                    script.getErrorMessage(),
                    script.getVersion(),
                    script.getCreatedAt(),
                    script.getUpdatedAt(),
                    script.getCompletedAt()
            );
        }
    }

    public record VideoScriptStatusResponse(
            String id,
            String status,
            int progress,
            String analysisRunId,
            String analysisStage,
            String analysisOutput,
            long analysisOutputCharacters,
            Instant analysisOutputUpdatedAt,
            String modelId,
            String errorMessage,
            Instant updatedAt,
            Instant completedAt
    ) {
        static VideoScriptStatusResponse from(VideoScript script) {
            return new VideoScriptStatusResponse(
                    script.getId(), script.getStatus(), script.getProgress(),
                    script.getAnalysisRunId(), script.getAnalysisStage(), script.getAnalysisOutput(),
                    script.getAnalysisOutputCharacters(), script.getAnalysisOutputUpdatedAt(),
                    script.getModelId(), script.getErrorMessage(),
                    script.getUpdatedAt(), script.getCompletedAt()
            );
        }
    }

    public record VideoScriptDetailResponse(
            VideoScriptSummaryResponse script,
            String scriptJson,
            List<VideoScriptShotResponse> shots
    ) {
        static VideoScriptDetailResponse from(VideoScriptService.VideoScriptDetail detail) {
            return new VideoScriptDetailResponse(
                    VideoScriptSummaryResponse.from(detail.script(), true),
                    detail.script().getScriptJson(),
                    detail.shots().stream().map(VideoScriptShotResponse::from).toList()
            );
        }
    }

    public record VideoScriptShotResponse(
            String id,
            int sequence,
            String stage,
            String title,
            BigDecimal startSecond,
            BigDecimal endSecond,
            BigDecimal durationSeconds,
            String sceneDescription,
            String motionDescription,
            String motionIntensity,
            String narration,
            String shotSize,
            String cameraMovement,
            String transitionDescription,
            String emotion,
            String imagePromptJson
    ) {
        static VideoScriptShotResponse from(VideoScriptShot shot) {
            return new VideoScriptShotResponse(
                    shot.getId(), shot.getSequenceNo(), shot.getStage(), shot.getTitle(),
                    shot.getStartSecond(), shot.getEndSecond(), shot.getDurationSeconds(),
                    shot.getSceneDescription(), shot.getMotionDescription(), shot.getMotionIntensity(),
                    shot.getNarration(), shot.getShotSize(),
                    shot.getCameraMovement(), shot.getTransitionDescription(), shot.getEmotion(), shot.getImagePromptJson()
            );
        }
    }

    public record UpdateShotRequest(
            String stage,
            String title,
            String sceneDescription,
            String motionDescription,
            String motionIntensity,
            String narration,
            String shotSize,
            String cameraMovement,
            String transitionDescription,
            String emotion
    ) {
    }

    public record UpdateVisualToneRequest(String visualTone) {
    }

    public record ManualScriptRequest(
            String name,
            String targetMarket,
            String visualTone,
            String presentationMode,
            List<ManualShotRequest> shots
    ) {
    }

    public record ManualScriptAiRequest(String prompt, String targetMarket, String name, String visualTone,
                                        String presentationMode) {
    }

    public record ManualShotRequest(
            String stage,
            String title,
            BigDecimal startSecond,
            BigDecimal endSecond,
            String sceneDescription,
            String motionDescription,
            String narration,
            String shotSize,
            String cameraMovement,
            String emotion
    ) {
    }
}
