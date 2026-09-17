package com.yingdao.aicommerce.video.web;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.api.ApiResponse;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.video.application.AiVideoTaskService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Validated
@RestController
@RequestMapping("/api/v1/general-video-tasks")
public class GeneralVideoTaskController {
    private final AiVideoTaskService service;

    public GeneralVideoTaskController(AiVideoTaskService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AiVideoTaskController.TaskResponse> create(
            @RequestParam(required = false) @Size(max = 160) String name,
            @RequestParam @NotBlank @Size(max = 20000) String prompt,
            @RequestParam(defaultValue = "6") Integer durationSeconds,
            @RequestParam(defaultValue = "9:16") @Pattern(regexp = "9:16|1:1|16:9") String aspectRatio,
            @RequestParam(defaultValue = "720p") @Pattern(regexp = "720p|1080p|2K|4K") String resolution,
            @RequestParam(defaultValue = "QUALITY") @Pattern(regexp = "QUALITY|SPEED") String videoRoute,
            @RequestParam(defaultValue = "false") boolean autoStart,
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam(required = false) Double videoClipStart,
            @RequestParam(required = false) Double videoClipEnd,
            @AuthenticationPrincipal PlatformPrincipal principal) {
        validateRouteResolution(videoRoute, resolution);
        var task = service.createGeneral(new AiVideoTaskService.GeneralCreateCommand(name, prompt,
                durationSeconds, aspectRatio, resolution, videoRoute, autoStart, videoClipStart, videoClipEnd), files, principal);
        return ApiResponse.created(AiVideoTaskController.TaskResponse.from(task));
    }

    @GetMapping
    public ApiResponse<List<AiVideoTaskController.TaskResponse>> list(
            @AuthenticationPrincipal PlatformPrincipal principal) {
        return ApiResponse.ok(service.listGeneral(principal).stream()
                .map(AiVideoTaskController.TaskResponse::from).toList());
    }

    @GetMapping("/{id}")
    public ApiResponse<AiVideoTaskController.TaskDetailResponse> detail(
            @PathVariable String id, @AuthenticationPrincipal PlatformPrincipal principal) {
        return ApiResponse.ok(AiVideoTaskController.TaskDetailResponse.from(service.detailGeneral(id, principal)));
    }

    @PostMapping("/{id}/start")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<AiVideoTaskController.TaskResponse> start(
            @PathVariable String id, @AuthenticationPrincipal PlatformPrincipal principal) {
        return ApiResponse.ok(AiVideoTaskController.TaskResponse.from(service.startGeneral(id, principal)));
    }

    @GetMapping("/{taskId}/assets/{assetId}")
    public ResponseEntity<?> asset(@PathVariable String taskId, @PathVariable String assetId,
                                   @RequestParam(defaultValue = "false") boolean download,
                                   @RequestParam(defaultValue = "false") boolean preview,
                                   @RequestParam(required = false) String filename,
                                   @AuthenticationPrincipal PlatformPrincipal principal) {
        AiVideoTaskService.AssetFile file = service.generalAsset(taskId, assetId, principal);
        if (file.location() != null) {
            boolean imagePreview = preview && !download && file.contentType() != null
                    && file.contentType().startsWith("image/");
            return ResponseEntity.status(HttpStatus.TEMPORARY_REDIRECT)
                    .location(URI.create(service.signedAssetUrl(file.location(), imagePreview)))
                    .cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS).cachePrivate()).build();
        }
        MediaType contentType;
        try { contentType = MediaType.parseMediaType(file.contentType()); }
        catch (Exception ignored) { contentType = MediaType.APPLICATION_OCTET_STREAM; }
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS).cachePrivate())
                .contentLength(file.size()).contentType(contentType);
        if (download) {
            response.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                    .filename(downloadFilename(filename, file.path()), StandardCharsets.UTF_8)
                    .build().toString());
        }
        return response.body(new FileSystemResource(file.path()));
    }

    private void validateRouteResolution(String videoRoute, String resolution) {
        if ("SPEED".equals(videoRoute) && !"720p".equals(resolution)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "高速线路当前仅支持 720p（厂商实际输出 768P）；如需更高分辨率，请切换品质线路");
        }
    }

    private String downloadFilename(String requested, java.nio.file.Path path) {
        String original = path.getFileName().toString();
        int dot = original.lastIndexOf('.');
        String extension = dot >= 0 ? original.substring(dot).toLowerCase() : ".bin";
        String value = requested == null ? "通用生成素材" : requested.trim();
        value = value.replaceAll("[\\\\/:*?\"<>|\\r\\n]+", "-");
        if (value.isBlank()) value = "通用生成素材";
        if (value.length() > 100) value = value.substring(0, 100);
        if (!value.toLowerCase().endsWith(extension)) value += extension;
        return value;
    }
}
