package com.yingdao.aicommerce.video.web;

import com.yingdao.aicommerce.video.application.VideoReferenceDeliveryService;
import com.yingdao.aicommerce.video.application.VideoReferenceLinkService;
import com.yingdao.aicommerce.video.domain.AiVideoTaskAsset;
import com.yingdao.aicommerce.video.domain.AiVideoTaskItem;
import com.yingdao.aicommerce.video.domain.AiVideoTask;
import com.yingdao.aicommerce.video.domain.VideoScript;
import com.yingdao.aicommerce.video.mapper.AiVideoTaskAssetMapper;
import com.yingdao.aicommerce.video.mapper.AiVideoTaskItemMapper;
import com.yingdao.aicommerce.video.mapper.AiVideoTaskMapper;
import com.yingdao.aicommerce.video.mapper.VideoScriptMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;

@RestController
@RequestMapping("/api/public/v1/video-references")
public class PublicVideoReferenceController {
    private final AiVideoTaskAssetMapper assetMapper;
    private final AiVideoTaskItemMapper itemMapper;
    private final AiVideoTaskMapper taskMapper;
    private final VideoScriptMapper scriptMapper;
    private final VideoReferenceDeliveryService delivery;
    private final VideoReferenceLinkService referenceLinks;
    private final Path storageRoot;
    private final Path scriptStorageRoot;

    public PublicVideoReferenceController(AiVideoTaskAssetMapper assetMapper, AiVideoTaskItemMapper itemMapper,
                                          AiVideoTaskMapper taskMapper,
                                          VideoScriptMapper scriptMapper,
                                          VideoReferenceDeliveryService delivery,
                                          VideoReferenceLinkService referenceLinks,
                                          @Value("${app.ai-video-task-storage:/data/ai-video-tasks}") String storageRoot,
                                          @Value("${app.video-script-storage:/data/video-scripts}") String scriptStorageRoot) {
        this.assetMapper = assetMapper;
        this.itemMapper = itemMapper;
        this.taskMapper = taskMapper;
        this.scriptMapper = scriptMapper;
        this.delivery = delivery;
        this.referenceLinks = referenceLinks;
        this.storageRoot = Path.of(storageRoot).toAbsolutePath().normalize();
        this.scriptStorageRoot = Path.of(scriptStorageRoot).toAbsolutePath().normalize();
    }

    @GetMapping("/delivery-asset/{id}")
    public ResponseEntity<FileSystemResource> deliveryAsset(@PathVariable String id,
                                                            @RequestParam long expires,
                                                            @RequestParam String signature) {
        if (!referenceLinks.isValid("delivery-asset", id, expires, signature)) return ResponseEntity.notFound().build();
        AiVideoTaskAsset asset = assetMapper.selectById(id);
        if (asset == null) return ResponseEntity.notFound().build();
        VideoReferenceDeliveryService.Delivery prepared = delivery.prepare(asset);
        return file(prepared.path().toString(), prepared.contentType(), prepared.size());
    }

    @GetMapping("/storyboard-frame/{sequence}/{id}")
    public ResponseEntity<FileSystemResource> storyboardFrame(@PathVariable int sequence, @PathVariable String id,
                                                               @RequestParam long expires, @RequestParam String signature) {
        if (sequence < 1 || sequence > 9 || !referenceLinks.isValid("storyboard-frame/" + sequence, id, expires, signature)) {
            return ResponseEntity.notFound().build();
        }
        AiVideoTaskAsset asset = assetMapper.selectById(id);
        if (asset == null || !"STORYBOARD_GRID".equals(asset.getAssetType())) return ResponseEntity.notFound().build();
        try {
            Path source = Path.of(asset.getFilePath()).toAbsolutePath().normalize();
            if (!source.startsWith(storageRoot) || !Files.isRegularFile(source) || Files.size(source) > 64L * 1024 * 1024) {
                return ResponseEntity.notFound().build();
            }
            var frame = delivery.prepareStoryboardFrames(Files.readAllBytes(source)).get(sequence - 1);
            return file(frame.path().toString(), frame.contentType(), frame.size());
        } catch (java.io.IOException exception) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/asset/{id}")
    public ResponseEntity<FileSystemResource> asset(@PathVariable String id,
                                                    @RequestParam long expires,
                                                    @RequestParam String signature) {
        if (!referenceLinks.isValid("asset", id, expires, signature)) return ResponseEntity.notFound().build();
        AiVideoTaskAsset asset = assetMapper.selectById(id);
        if (asset == null) return ResponseEntity.notFound().build();
        return file(asset.getFilePath(), asset.getContentType(), asset.getSizeBytes());
    }

    @GetMapping("/source/{id}")
    public ResponseEntity<FileSystemResource> source(@PathVariable String id,
                                                     @RequestParam long expires,
                                                     @RequestParam String signature) {
        if (!referenceLinks.isValid("source", id, expires, signature)) return ResponseEntity.notFound().build();
        AiVideoTaskItem item = itemMapper.selectById(id);
        if (item == null) return ResponseEntity.notFound().build();
        return file(item.getSourceFilePath(), item.getSourceContentType(), item.getSourceSizeBytes());
    }

    @GetMapping("/script-source/{id}")
    public ResponseEntity<FileSystemResource> scriptSource(@PathVariable String id,
                                                           @RequestParam long expires,
                                                           @RequestParam String signature) {
        if (!referenceLinks.isValid("script-source", id, expires, signature)) return ResponseEntity.notFound().build();
        VideoScript script = scriptMapper.selectById(id);
        if (script == null || !"VIDEO_ANALYSIS".equals(script.getSourceType())
                || script.getSourceFilePath() == null || script.getSourceContentType() == null
                || !script.getSourceContentType().startsWith("video/")) {
            return ResponseEntity.notFound().build();
        }
        return file(script.getSourceFilePath(), script.getSourceContentType(),
                script.getSourceSizeBytes() == null ? 0L : script.getSourceSizeBytes(), scriptStorageRoot);
    }

    @GetMapping({"/script-audio/{id}", "/script-audio/{id}/transcription-audio.mp3"})
    public ResponseEntity<FileSystemResource> scriptAudio(@PathVariable String id,
                                                          @RequestParam long expires,
                                                          @RequestParam String signature) {
        if (!referenceLinks.isValid("script-audio", id, expires, signature)) return ResponseEntity.notFound().build();
        VideoScript script = scriptMapper.selectById(id);
        if (script == null || script.getSourceFilePath() == null) return ResponseEntity.notFound().build();
        Path source = Path.of(script.getSourceFilePath()).toAbsolutePath().normalize();
        Path audio = source.getParent().resolve("transcription-audio.mp3").normalize();
        return file(audio.toString(), "audio/mpeg", 0L, scriptStorageRoot);
    }

    @GetMapping("/task-audio/{id}")
    public ResponseEntity<FileSystemResource> taskAudio(@PathVariable String id,
                                                        @RequestParam long expires,
                                                        @RequestParam String signature) {
        if (!referenceLinks.isValid("task-audio", id, expires, signature)) return ResponseEntity.notFound().build();
        AiVideoTask task = taskMapper.selectById(id);
        if (task == null || task.getMusicFilePath() == null) return ResponseEntity.notFound().build();
        return file(task.getMusicFilePath(), task.getMusicContentType(), task.getMusicSizeBytes());
    }

    private ResponseEntity<FileSystemResource> file(String value, String contentType, long size) {
        return file(value, contentType, size, storageRoot);
    }

    private ResponseEntity<FileSystemResource> file(String value, String contentType, long size, Path allowedRoot) {
        Path path = Path.of(value).toAbsolutePath().normalize();
        if (!path.startsWith(allowedRoot) || !Files.isRegularFile(path)) return ResponseEntity.notFound().build();
        try { if (size <= 0) size = Files.size(path); } catch (Exception ignored) { return ResponseEntity.notFound().build(); }
        MediaType media;
        try { media = MediaType.parseMediaType(contentType); } catch (Exception ignored) { media = MediaType.APPLICATION_OCTET_STREAM; }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(media).contentLength(size).body(new FileSystemResource(path));
    }
}
