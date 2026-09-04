package com.yingdao.aicommerce.video.application;

import com.yingdao.aicommerce.video.mapper.AiVideoTaskItemMapper;
import com.yingdao.aicommerce.video.domain.AiVideoTaskItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Operational telemetry only: a failed progress write must never replay a paid request. */
public final class ImageExecutionProgress {
    private static final Logger log = LoggerFactory.getLogger(ImageExecutionProgress.class);
    private ImageExecutionProgress() {}
    public static void record(AiVideoTaskItemMapper mapper, AiVideoTaskItem item, String assetType, String stage) {
        item.setActiveImageAssetType(assetType);
        record(mapper, item.getId(), assetType, stage);
    }
    public static void record(AiVideoTaskItemMapper mapper, String itemId, String assetType, String stage) {
        if (itemId == null || assetType == null) return;
        try {
            mapper.recordImageStep(itemId, assetType, stage);
        } catch (RuntimeException exception) {
            log.warn("Could not record image stage {} for item {}", stage, itemId);
        }
    }
}
