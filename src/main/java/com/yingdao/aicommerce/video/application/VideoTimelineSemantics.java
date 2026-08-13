package com.yingdao.aicommerce.video.application;

final class VideoTimelineSemantics {

    static final String FIRST_SEGMENT = "无";
    static final String CONTINUOUS_SEGMENT = "同镜头连续，无转场";

    private VideoTimelineSemantics() {
    }

    static boolean usesPhysicalShotTransitions(String generationMode) {
        return "PRODUCT_ONLY".equals(generationMode);
    }

    static String enteringTransition(int sequence, String transitionDescription) {
        if (sequence <= 1) return FIRST_SEGMENT;
        if (transitionDescription == null || transitionDescription.isBlank()) {
            return CONTINUOUS_SEGMENT;
        }
        return transitionDescription.trim();
    }
}
