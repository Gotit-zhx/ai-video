package com.yingdao.aicommerce.video.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record VideoScriptAnalysisResult(
        String title,
        String summary,
        String globalVisualTone,
        BigDecimal durationSeconds,
        List<Shot> shots
) {
    public record Shot(
            Integer sequence,
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
            String emotion,
            Map<String, Object> imagePrompt
    ) {
    }
}
