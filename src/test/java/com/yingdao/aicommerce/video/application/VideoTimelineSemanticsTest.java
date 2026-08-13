package com.yingdao.aicommerce.video.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VideoTimelineSemanticsTest {

    @Test
    void physicalShotTransitionDirectivesOnlyApplyToProductVideos() {
        assertThat(VideoTimelineSemantics.usesPhysicalShotTransitions("PRODUCT_ONLY")).isTrue();
        assertThat(VideoTimelineSemantics.usesPhysicalShotTransitions("MODEL_WEAR")).isFalse();
    }

    @Test
    void firstKeyframeNeverIntroducesATransition() {
        assertThat(VideoTimelineSemantics.enteringTransition(1, "直接硬切"))
                .isEqualTo("无");
    }

    @Test
    void missingTransitionMeansContinuousKeyframeInsteadOfHardCut() {
        assertThat(VideoTimelineSemantics.enteringTransition(2, "  "))
                .isEqualTo("同镜头连续，无转场");
    }

    @Test
    void preservesAnExplicitPhysicalShotTransition() {
        assertThat(VideoTimelineSemantics.enteringTransition(5, " 青绿色漏光闪白，约0.3秒 "))
                .isEqualTo("青绿色漏光闪白，约0.3秒");
    }
}
