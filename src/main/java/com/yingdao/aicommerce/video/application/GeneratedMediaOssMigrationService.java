package com.yingdao.aicommerce.video.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yingdao.aicommerce.storage.OssStorageService;
import com.yingdao.aicommerce.video.domain.AiVideoTaskAsset;
import com.yingdao.aicommerce.video.mapper.AiVideoTaskAssetMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

@Service
@ConditionalOnBean(OssStorageService.class)
public class GeneratedMediaOssMigrationService {
    private static final Logger log = LoggerFactory.getLogger(GeneratedMediaOssMigrationService.class);
    private static final long MAX_MEDIA_BYTES = 500L * 1024 * 1024;
    private static final Set<String> GENERATED_TYPES = Set.of(
            "MODEL_IMAGE", "PRODUCT_SCENE_IMAGE", "STORYBOARD_GRID", "STORYBOARD_GUIDE",
            "STORYBOARD_FIRST_FRAME");

    private final AiVideoTaskAssetMapper assetMapper;
    private final OssStorageService ossStorage;
    private final Path storageRoot;

    public GeneratedMediaOssMigrationService(AiVideoTaskAssetMapper assetMapper, OssStorageService ossStorage,
                                             @Value("${app.ai-video-task-storage:/data/ai-video-tasks}") String storageRoot) {
        this.assetMapper = assetMapper;
        this.ossStorage = ossStorage;
        this.storageRoot = Path.of(storageRoot).toAbsolutePath().normalize();
    }

    @Scheduled(initialDelay = 20_000, fixedDelay = 60_000)
    public void migrateLegacyGeneratedMedia() {
        List<AiVideoTaskAsset> assets = assetMapper.selectList(new LambdaQueryWrapper<AiVideoTaskAsset>()
                .in(AiVideoTaskAsset::getAssetType, GENERATED_TYPES)
                .notLikeRight(AiVideoTaskAsset::getFilePath, "oss://")
                .orderByAsc(AiVideoTaskAsset::getCreatedAt)
                .last("LIMIT 50"));
        for (AiVideoTaskAsset asset : assets) migrate(asset);
    }

    private void migrate(AiVideoTaskAsset asset) {
        if (!GENERATED_TYPES.contains(asset.getAssetType())) return;
        Path source;
        try {
            source = Path.of(asset.getFilePath()).toAbsolutePath().normalize();
            if (!source.startsWith(storageRoot) || !Files.isRegularFile(source)) return;
            long size = Files.size(source);
            String category = "generated-legacy/" + asset.getOrganizationId() + "/" + asset.getTaskId()
                    + "/" + asset.getItemId();
            try (InputStream input = Files.newInputStream(source)) {
                OssStorageService.StoredObject stored = ossStorage.store(input, size, MAX_MEDIA_BYTES,
                        asset.getContentType(), category, source.getFileName().toString());
                asset.relocate(stored.location(), stored.size());
                if (assetMapper.updateById(asset) != 1) {
                    ossStorage.delete(stored.location());
                    throw new IllegalStateException("媒体数据库记录已发生变化");
                }
            }
            Files.deleteIfExists(source);
            log.info("Migrated generated media asset {} to OSS", asset.getId());
        } catch (Exception exception) {
            log.warn("Unable to migrate generated media asset {} to OSS: {}", asset.getId(), exception.getMessage());
        }
    }
}
