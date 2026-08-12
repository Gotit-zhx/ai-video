package com.yingdao.aicommerce.video.web;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.api.ApiResponse;
import com.yingdao.aicommerce.video.application.AiVideoTaskService;
import com.yingdao.aicommerce.video.domain.AiVideoTask;
import com.yingdao.aicommerce.video.domain.AiVideoTaskAsset;
import com.yingdao.aicommerce.video.domain.AiVideoTaskItem;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Validated
@RestController
@RequestMapping("/api/v1/ai-video-tasks")
public class AiVideoTaskController {
    private final AiVideoTaskService service;

    public AiVideoTaskController(AiVideoTaskService service) { this.service = service; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<TaskResponse> create(@RequestBody CreateRequest request,
                                            @AuthenticationPrincipal PlatformPrincipal principal) {
        return ApiResponse.created(TaskResponse.from(service.create(
                new AiVideoTaskService.CreateCommand(request.scriptId(), request.name(), request.targetMarket(),
                        request.generationMode(), request.backgroundMode(), request.productCategory(), request.sceneType(), request.interactionType(),
                        Boolean.TRUE.equals(request.allowHand()), request.aspectRatio()), principal)));
    }

    @GetMapping
    public ApiResponse<List<TaskResponse>> list(@AuthenticationPrincipal PlatformPrincipal principal) {
        return ApiResponse.ok(service.list(principal).stream().map(TaskResponse::from).toList());
    }

    @GetMapping("/{id}")
    public ApiResponse<TaskDetailResponse> detail(@PathVariable String id,
                                                  @AuthenticationPrincipal PlatformPrincipal principal) {
        return ApiResponse.ok(TaskDetailResponse.from(service.detail(id, principal)));
    }

    @PostMapping(value = "/{id}/garments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<List<ItemResponse>> upload(@PathVariable String id,
                                                   @RequestParam("files") List<MultipartFile> files,
                                                   @AuthenticationPrincipal PlatformPrincipal principal) {
        return ApiResponse.ok(service.upload(id, files, principal).stream().map(ItemResponse::from).toList());
    }

    @PostMapping(value = "/{id}/music", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<TaskResponse> uploadMusic(@PathVariable String id,
                                                  @RequestParam("file") MultipartFile file,
                                                  @AuthenticationPrincipal PlatformPrincipal principal) {
        return ApiResponse.ok(TaskResponse.from(service.uploadMusic(id, file, principal)));
    }

    @PostMapping("/{id}/start-images")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<TaskResponse> startImages(@PathVariable String id,
                                                 @AuthenticationPrincipal PlatformPrincipal principal) {
        return ApiResponse.ok(TaskResponse.from(service.startImages(id, principal)));
    }

    @PostMapping("/{id}/start-videos")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<TaskResponse> startVideos(@PathVariable String id,
                                                 @AuthenticationPrincipal PlatformPrincipal principal) {
        return ApiResponse.ok(TaskResponse.from(service.startVideos(id, principal)));
    }

    @PostMapping("/{taskId}/items/{itemId}/regenerate-storyboard")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<TaskResponse> regenerateStoryboard(@PathVariable String taskId,
                                                           @PathVariable String itemId,
                                                           @AuthenticationPrincipal PlatformPrincipal principal) {
        return ApiResponse.ok(TaskResponse.from(service.regenerateStoryboard(taskId, itemId, principal)));
    }

    @PostMapping("/{taskId}/items/{itemId}/regenerate-first-frame")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<TaskResponse> regenerateFirstFrame(@PathVariable String taskId,
                                                           @PathVariable String itemId,
                                                           @AuthenticationPrincipal PlatformPrincipal principal) {
        return ApiResponse.ok(TaskResponse.from(service.regenerateFirstFrame(taskId, itemId, principal)));
    }

    @PatchMapping("/{taskId}/items/{itemId}/video-prompt")
    public ApiResponse<ItemResponse> updateVideoPrompt(@PathVariable String taskId, @PathVariable String itemId,
                                                        @RequestBody UpdateVideoPromptRequest request,
                                                        @AuthenticationPrincipal PlatformPrincipal principal) {
        return ApiResponse.ok(ItemResponse.from(service.updateVideoPrompt(taskId, itemId, request.prompt(), principal)));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id, @AuthenticationPrincipal PlatformPrincipal principal) {
        service.delete(id, principal);
    }

    @GetMapping("/{taskId}/items/{itemId}/source")
    public ResponseEntity<FileSystemResource> source(@PathVariable String taskId, @PathVariable String itemId,
                                                      @AuthenticationPrincipal PlatformPrincipal principal) {
        AiVideoTaskService.SourceFile file = service.source(taskId, itemId, principal);
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS).cachePrivate())
                .contentLength(file.size()).contentType(mediaType(file.contentType()))
                .body(new FileSystemResource(file.path()));
    }

    @GetMapping("/{taskId}/assets/{assetId}")
    public ResponseEntity<FileSystemResource> asset(@PathVariable String taskId, @PathVariable String assetId,
                                                     @RequestParam(defaultValue = "false") boolean download,
                                                     @RequestParam(required = false) String filename,
                                                     @AuthenticationPrincipal PlatformPrincipal principal) {
        AiVideoTaskService.AssetFile file = service.asset(taskId, assetId, principal);
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS).cachePrivate())
                .contentLength(file.size()).contentType(mediaType(file.contentType()));
        if (download) {
            response.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                    .filename(downloadFilename(filename, file.path()), StandardCharsets.UTF_8)
                    .build().toString());
        }
        return response.body(new FileSystemResource(file.path()));
    }

    private String downloadFilename(String requested, java.nio.file.Path path) {
        String original = path.getFileName().toString();
        int dot = original.lastIndexOf('.');
        String extension = dot >= 0 ? original.substring(dot).toLowerCase() : ".png";
        String value = requested == null ? "赫逸生成图片" : requested.trim();
        value = value.replaceAll("[\\\\/:*?\"<>|\\r\\n]+", "-");
        if (value.isBlank()) value = "赫逸生成图片";
        if (value.length() > 100) value = value.substring(0, 100);
        if (!value.toLowerCase().endsWith(extension)) value += extension;
        return value;
    }

    private MediaType mediaType(String value) {
        try { return MediaType.parseMediaType(value); } catch (Exception ignored) { return MediaType.APPLICATION_OCTET_STREAM; }
    }

    public record CreateRequest(@NotBlank String scriptId, @Size(max = 160) String name,
                                @Size(max = 80) String targetMarket, @Size(max = 32) String generationMode,
                                @Size(max = 32) String backgroundMode, @Size(max = 80) String productCategory, @Size(max = 80) String sceneType,
                                @Size(max = 80) String interactionType, Boolean allowHand,
                                @Size(max = 16) String aspectRatio) {}

    public record UpdateVideoPromptRequest(@NotBlank @Size(max = 20000) String prompt) {}

    public record TaskResponse(String id, String name, String scriptId, int scriptVersion, String scriptName,
                               String targetMarket, String generationMode, String productCategory, String sceneType,
                               String backgroundMode, String interactionType, boolean allowHand, String aspectRatio,
                               String musicMode, String musicFileName, String musicContentType, long musicSizeBytes,
                               String status, int progress, int totalItems,
                               int completedItems, String modelId, String errorMessage,
                               Instant createdAt, Instant updatedAt, Instant completedAt) {
        static TaskResponse from(AiVideoTask task) {
            return new TaskResponse(task.getId(), task.getName(), task.getScriptId(), task.getScriptVersion(),
                    task.getScriptName(), task.getTargetMarket(), task.getGenerationMode(), task.getProductCategory(),
                    task.getSceneType(), task.getBackgroundMode(), task.getInteractionType(), task.isAllowHand(), task.getAspectRatio(),
                    task.getMusicMode(), task.getMusicFileName(), task.getMusicContentType(), task.getMusicSizeBytes(),
                    task.getStatus(), task.getProgress(),
                    task.getTotalItems(), task.getCompletedItems(), task.getModelId(), task.getErrorMessage(),
                    task.getCreatedAt(), task.getUpdatedAt(), task.getCompletedAt());
        }
    }

    public record ItemResponse(String id, String name, String sku, String sourceFileName, String status,
                               int progress, String errorMessage, String videoPromptText, String videoProviderTaskId,
                               Instant createdAt, Instant updatedAt) {
        static ItemResponse from(AiVideoTaskItem item) {
            return from(item, null);
        }

        static ItemResponse from(AiVideoTaskItem item, String videoProviderTaskId) {
            return new ItemResponse(item.getId(), item.getName(), item.getSku(), item.getSourceFileName(),
                    item.getStatus(), item.getProgress(), item.getErrorMessage(), item.getVideoPromptText(), videoProviderTaskId,
                    item.getCreatedAt(), item.getUpdatedAt());
        }
    }

    public record AssetResponse(String id, String itemId, String assetType, Integer shotSequence,
                                String contentType, long sizeBytes, String modelId, Instant createdAt) {
        static AssetResponse from(AiVideoTaskAsset asset) {
            return new AssetResponse(asset.getId(), asset.getItemId(), asset.getAssetType(), asset.getShotSequence(),
                    asset.getContentType(), asset.getSizeBytes(), asset.getModelId(), asset.getCreatedAt());
        }
    }

    public record TaskDetailResponse(TaskResponse task, List<ItemResponse> items, List<AssetResponse> assets,
                                     Instant videoSubmittedAt) {
        static TaskDetailResponse from(AiVideoTaskService.TaskDetail detail) {
            return new TaskDetailResponse(TaskResponse.from(detail.task()),
                    detail.items().stream().map(item -> ItemResponse.from(item, detail.videoProviderTaskIds().get(item.getId()))).toList(),
                    detail.assets().stream().map(AssetResponse::from).toList(), detail.videoSubmittedAt());
        }
    }
}
