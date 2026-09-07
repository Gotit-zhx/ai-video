package com.yingdao.aicommerce.video.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VideoTimelineSemanticsTest {

    @Test
    void transitionPolicyNeverInfersCutsFromDifferentScenesOrHandPoses() {
        assertThat(VideoTimelineSemantics.TRANSITION_POLICY)
                .contains("场景、构图或手势差异本身不能新增切镜")
                .contains("若参考格的背景不兼容，保留当前场景")
                .contains("真实切点两侧不做人体或手势变形插值");
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

    @Test
    void explicitNoTransitionCannotBeOverriddenBySceneSwitchText() {
        assertThat(VideoTimelineSemantics.enteringTransition(2, "无")).isEqualTo("同镜头连续，无转场");
        assertThat(VideoTimelineSemantics.enteringTransition(3, "同一镜头连续，无转场，背景切换"))
                .isEqualTo("同镜头连续，无转场");
        assertThat(VideoTimelineSemantics.enteringTransition(4, "硬切到商品特写"))
                .isEqualTo("硬切到商品特写");
        assertThat(VideoTimelineSemantics.enteringTransition(4, "从同镜头连续改为硬切"))
                .isEqualTo("从同镜头连续改为硬切");
    }

    @Test
    void rewritesFramingConflictsWithoutErasingValidCloseRangeActions() {
        String result = VideoTimelineSemantics.normalizeCloseFraming("MODEL_WEAR",
                "镜头缓慢拉远，模特全身展示，左手提起衣摆，缓慢转身展示后背，展示鞋子");

        assertThat(result).contains("镜头保持固定机位", "人物中近景展示", "左手提起衣摆", "缓慢转身展示后背", "膝盖以下留在画外")
                .doesNotContain("镜头缓慢拉远", "全身展示", "展示鞋子");
    }

    @Test
    void preservesProhibitionsGarmentConstructionBackgroundDepthAndSmallSteps() {
        String source = "禁止缓慢拉远，不要拍摄全身镜头，避免展示鞋子，模特后退一步并转身，全身印花连衣裙，背景远景建筑保持虚化，将衣摆拉远一点";
        assertThat(VideoTimelineSemantics.normalizeCloseFraming("MODEL_WEAR", source)).isEqualTo(source);
        String english = "do not zoom out; never use a full-body shot; full body print; full-length dress; pull back the sleeve";
        assertThat(VideoTimelineSemantics.normalizeCloseFraming("MODEL_WEAR", english)).isEqualTo(english);
    }

    @Test
    void preservesNegationAcrossConstraintListsButNotAcrossActionsOrSentenceBoundaries() {
        String chinese = "禁止全身、远景、拉远；左手提衣摆，镜头拉远。全身展示";
        assertThat(VideoTimelineSemantics.normalizeCloseFraming("MODEL_WEAR", chinese))
                .isEqualTo("禁止全身、远景、拉远；左手提衣摆，镜头保持固定机位。人物中近景展示");
        String english = "no wide shot, full body shot, zoom out; camera retreats. full body shot";
        assertThat(VideoTimelineSemantics.normalizeCloseFraming("MODEL_WEAR", english))
                .isEqualTo("no wide shot, full body shot, zoom out; 镜头保持固定机位. 人物中近景");
        assertThat(VideoTimelineSemantics.normalizeCloseFraming("MODEL_WEAR",
                "禁止全身、远景，左手提衣摆，镜头拉远"))
                .isEqualTo("禁止全身、远景，左手提衣摆，镜头保持固定机位");
        assertThat(VideoTimelineSemantics.normalizeCameraMovement("MODEL_WEAR", "禁止拉远、后退、远离；缓慢横移"))
                .isEqualTo("禁止拉远、后退、远离；缓慢横移");
    }

    @Test
    void productOnlyKeepsShoeDetailsWhileNormalizingCameraDistance() {
        assertThat(VideoTimelineSemantics.normalizeCloseFraming("PRODUCT_ONLY", "鞋子特写，展示鞋底，单手拿起商品"))
                .isEqualTo("鞋子特写，展示鞋底，单手拿起商品");
        assertThat(VideoTimelineSemantics.normalizeCloseFraming("PRODUCT_ONLY", "zoom out to a wide shot，轻微环绕商品"))
                .contains("保持固定机位", "商品近景", "轻微环绕商品")
                .doesNotContain("zoom out", "wide shot");
        assertThat(VideoTimelineSemantics.normalizeCameraMovement("MODEL_WEAR", "后退并缓慢横移"))
                .isEqualTo("保持固定机位并缓慢横移");
        assertThat(VideoTimelineSemantics.normalizeCameraMovement("MODEL_WEAR", "禁止后退，缓慢横移"))
                .isEqualTo("禁止后退，缓慢横移");
    }
}
