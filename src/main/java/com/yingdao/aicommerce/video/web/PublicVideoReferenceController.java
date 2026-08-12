package com.yingdao.aicommerce.video.web;

import com.yingdao.aicommerce.video.application.VideoReferenceDeliveryService;
import com.yingdao.aicommerce.video.domain.AiVideoTaskAsset;
import com.yingdao.aicommerce.video.domain.AiVideoTaskItem;
import com.yingdao.aicommerce.video.domain.AiVideoTask;
import com.yingdao.aicommerce.video.mapper.AiVideoTaskAssetMapper;
import com.yingdao.aicommerce.video.mapper.AiVideoTaskItemMapper;
import com.yingdao.aicommerce.video.mapper.AiVideoTaskMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/public/v1/video-references")
public class PublicVideoReferenceController {
    private final AiVideoTaskAssetMapper assetMapper;
    private final AiVideoTaskItemMapper itemMapper;
    private final AiVideoTaskMapper taskMapper;
    private final VideoReferenceDeliveryService delivery;
    private final Path storageRoot;

    public PublicVideoReferenceController(AiVideoTaskAssetMapper assetMapper, AiVideoTaskItemMapper itemMapper,
                                          AiVideoTaskMapper taskMapper,
                                          VideoReferenceDeliveryService delivery,
                                          @Value("${app.ai-video-task-storage:/data/ai-video-tasks}") String storageRoot) {
        this.assetMapper = assetMapper;
        this.itemMapper = itemMapper;
        this.taskMapper = taskMapper;
        this.delivery = delivery;
        this.storageRoot = Path.of(storageRoot).toAbsolutePath().normalize();
    }

    @GetMapping("/delivery-asset/{id}")
    public ResponseEntity<FileSystemResource> deliveryAsset(@PathVariable String id) {
        AiVideoTaskAsset asset = assetMapper.selectById(id);
        if (asset == null) return ResponseEntity.notFound().build();
        VideoReferenceDeliveryService.Delivery prepared = delivery.prepare(asset);
        return file(prepared.path().toString(), prepared.contentType(), prepared.size());
    }

    @GetMapping("/asset/{id}")
    public ResponseEntity<FileSystemResource> asset(@PathVariable String id) {
        AiVideoTaskAsset asset = assetMapper.selectById(id);
        if (asset == null) return ResponseEntity.notFound().build();
        return file(asset.getFilePath(), asset.getContentType(), asset.getSizeBytes());
    }

    @GetMapping("/source/{id}")
    public ResponseEntity<FileSystemResource> source(@PathVariable String id) {
        AiVideoTaskItem item = itemMapper.selectById(id);
        if (item == null) return ResponseEntity.notFound().build();
        return file(item.getSourceFilePath(), item.getSourceContentType(), item.getSourceSizeBytes());
    }

    @GetMapping("/task-audio/{id}")
    public ResponseEntity<FileSystemResource> taskAudio(@PathVariable String id) {
        AiVideoTask task = taskMapper.selectById(id);
        if (task == null || task.getMusicFilePath() == null) return ResponseEntity.notFound().build();
        return file(task.getMusicFilePath(), task.getMusicContentType(), task.getMusicSizeBytes());
    }

    private ResponseEntity<FileSystemResource> file(String value, String contentType, long size) {
        Path path = Path.of(value).toAbsolutePath().normalize();
        if (!path.startsWith(storageRoot) || !Files.isRegularFile(path)) return ResponseEntity.notFound().build();
        MediaType media;
        try { media = MediaType.parseMediaType(contentType); } catch (Exception ignored) { media = MediaType.APPLICATION_OCTET_STREAM; }
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(6, TimeUnit.HOURS).cachePublic())
                .contentType(media).contentLength(size).body(new FileSystemResource(path));
    }
}
