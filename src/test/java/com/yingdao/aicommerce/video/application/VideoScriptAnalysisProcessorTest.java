package com.yingdao.aicommerce.video.application;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VideoScriptAnalysisProcessorTest {

    @Test
    void recognizesTransientPiFailuresForAutomaticRetry() {
        assertTrue(VideoScriptAnalysisProcessor.retryablePiFailure(
                502, "{\"message\":\"Stream ended without finish_reason\"}"));
        assertTrue(VideoScriptAnalysisProcessor.retryablePiFailure(504, "gateway timeout"));
        assertTrue(VideoScriptAnalysisProcessor.retryablePiFailure(429, "rate limited"));
        assertFalse(VideoScriptAnalysisProcessor.retryablePiFailure(401, "invalid api key"));
        assertFalse(VideoScriptAnalysisProcessor.retryablePiFailure(400, "invalid request"));
    }

    @Test
    void longVideoIsReplannedAsAContinuousFifteenSecondTimeline() {
        VideoScriptAnalysisResult original = resultWithNineShots(new BigDecimal("42.0"));

        VideoScriptAnalysisResult normalized =
                VideoScriptAnalysisProcessor.normalizeLongVideoTimeline(original, 42.0);

        assertEquals(new BigDecimal("15.0"), normalized.durationSeconds());
        assertEquals(new BigDecimal("0.0"), normalized.shots().getFirst().startSecond());
        assertEquals(new BigDecimal("1.7"), normalized.shots().getFirst().endSecond());
        assertEquals(new BigDecimal("13.3"), normalized.shots().getLast().startSecond());
        assertEquals(new BigDecimal("15.0"), normalized.shots().getLast().endSecond());
        for (int index = 1; index < normalized.shots().size(); index++) {
            assertEquals(normalized.shots().get(index - 1).endSecond(),
                    normalized.shots().get(index).startSecond());
        }
        BigDecimal durationSum = normalized.shots().stream()
                .map(VideoScriptAnalysisResult.Shot::durationSeconds)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, durationSum.compareTo(new BigDecimal("15.0")));
        assertEquals("画面 1", normalized.shots().getFirst().sceneDescription());
    }

    @Test
    void videoAtOrBelowFifteenSecondsKeepsItsOriginalTimeline() {
        VideoScriptAnalysisResult original = resultWithNineShots(new BigDecimal("12.0"));

        assertSame(original, VideoScriptAnalysisProcessor.normalizeLongVideoTimeline(original, 12.0));
        assertSame(original, VideoScriptAnalysisProcessor.normalizeLongVideoTimeline(original, 15.0));
    }

    @Test
    void keepsModelCondensedNarrationInsteadOfRestoringTheLongTranscript() {
        VideoScriptAnalysisResult original = resultWithNineShots(new BigDecimal("15.0"));
        List<VideoScriptAnalysisResult.Shot> shots = new ArrayList<>(original.shots());
        VideoScriptAnalysisResult.Shot first = shots.getFirst();
        shots.set(0, copyWithNarration(first, "旁白：轻松照顾宝宝，从这一刻开始"));
        VideoScriptAnalysisResult condensed = new VideoScriptAnalysisResult(
                original.title(), original.summary(), original.globalVisualTone(), original.durationSeconds(), shots);
        DayanAudioTranscriptionService.Transcript transcript = new DayanAudioTranscriptionService.Transcript(
                "这是一段很长并且包含大量重复表达的原始口播内容",
                List.of(new DayanAudioTranscriptionService.Segment(
                        BigDecimal.ZERO, new BigDecimal("15.0"), null,
                        "这是一段很长并且包含大量重复表达的原始口播内容")));

        VideoScriptAnalysisResult applied = VideoScriptAnalysisProcessor.applyAudioTranscript(
                condensed, transcript, 15.0);

        assertEquals("旁白：轻松照顾宝宝，从这一刻开始", applied.shots().getFirst().narration());
    }

    @Test
    void transcriptFallbackIsAlwaysLabeledAsOffCameraNarration() {
        VideoScriptAnalysisResult original = resultWithNineShots(new BigDecimal("15.0"));
        DayanAudioTranscriptionService.Transcript transcript = new DayanAudioTranscriptionService.Transcript(
                "今天看看新款", List.of(new DayanAudioTranscriptionService.Segment(
                BigDecimal.ZERO, new BigDecimal("1.6"), null, "今天看看新款")));

        VideoScriptAnalysisResult applied = VideoScriptAnalysisProcessor.applyAudioTranscript(
                original, transcript, 15.0);

        assertEquals("旁白：今天看看新款", applied.shots().getFirst().narration());
    }

    @Test
    void neverCutsModelNarrationInTheMiddleOfItsMeaning() {
        VideoScriptAnalysisResult original = resultWithNineShots(new BigDecimal("15.0"));
        List<VideoScriptAnalysisResult.Shot> shots = new ArrayList<>();
        for (VideoScriptAnalysisResult.Shot shot : original.shots()) {
            shots.add(copyWithNarration(shot, "口播：这是一段明显超过正常十五秒口播速度的冗长内容需要被限制"));
        }
        VideoScriptAnalysisResult verbose = new VideoScriptAnalysisResult(
                original.title(), original.summary(), original.globalVisualTone(), original.durationSeconds(), shots);

        DayanAudioTranscriptionService.Transcript transcript = new DayanAudioTranscriptionService.Transcript(
                "原始口播", List.of(new DayanAudioTranscriptionService.Segment(
                BigDecimal.ZERO, new BigDecimal("15.0"), null, "原始口播")));

        VideoScriptAnalysisResult preserved = VideoScriptAnalysisProcessor.applyAudioTranscript(
                verbose, transcript, 15.0);

        assertEquals("旁白：这是一段明显超过正常十五秒口播速度的冗长内容需要被限制",
                preserved.shots().getFirst().narration());
        assertEquals("旁白：这是一段明显超过正常十五秒口播速度的冗长内容需要被限制",
                preserved.shots().getLast().narration());
    }

    private VideoScriptAnalysisResult.Shot copyWithNarration(
            VideoScriptAnalysisResult.Shot shot, String narration) {
        return new VideoScriptAnalysisResult.Shot(
                shot.sequence(), shot.stage(), shot.title(), shot.startSecond(), shot.endSecond(),
                shot.durationSeconds(), shot.sceneDescription(), shot.motionDescription(),
                shot.motionIntensity(), narration, shot.shotSize(), shot.cameraMovement(),
                shot.transitionDescription(), shot.emotion(), shot.imagePrompt());
    }

    private VideoScriptAnalysisResult resultWithNineShots(BigDecimal duration) {
        List<VideoScriptAnalysisResult.Shot> shots = new ArrayList<>();
        BigDecimal originalShotDuration = duration.divide(BigDecimal.valueOf(9), 4, java.math.RoundingMode.HALF_UP);
        for (int index = 0; index < 9; index++) {
            BigDecimal start = originalShotDuration.multiply(BigDecimal.valueOf(index));
            BigDecimal end = index == 8 ? duration : originalShotDuration.multiply(BigDecimal.valueOf(index + 1));
            shots.add(new VideoScriptAnalysisResult.Shot(
                    index + 1, "CONTENT", "分镜 " + (index + 1), start, end, end.subtract(start),
                    "画面 " + (index + 1), "动作 " + (index + 1), "小幅", "", "中景", "固定机位",
                    index == 0 ? "无" : "同镜头连续，无转场", "自然", Map.of("prompt", "提示词")
            ));
        }
        return new VideoScriptAnalysisResult("测试", "摘要", "基调", duration, shots);
    }
}
