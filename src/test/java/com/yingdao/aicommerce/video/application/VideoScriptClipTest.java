package com.yingdao.aicommerce.video.application;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.video.domain.VideoScript;
import com.yingdao.aicommerce.video.mapper.VideoScriptMapper;
import com.yingdao.aicommerce.video.mapper.VideoScriptShotMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VideoScriptClipTest {

    @TempDir
    Path storageRoot;

    private final VideoScriptMapper scriptMapper = mock(VideoScriptMapper.class);
    private final VideoScriptShotMapper shotMapper = mock(VideoScriptShotMapper.class);
    private final VideoScriptAnalysisProcessor processor = mock(VideoScriptAnalysisProcessor.class);
    private final VideoFrameExtractor frameExtractor = mock(VideoFrameExtractor.class);
    private VideoScriptService service;

    @BeforeEach
    void setUp() {
        service = new VideoScriptService(
                storageRoot.toString(), scriptMapper, shotMapper, processor, frameExtractor, mock(ObjectMapper.class));
    }

    @Test
    void trimsLongUploadToFixedFifteenSecondWindowAndKeepsOriginal() throws Exception {
        when(frameExtractor.probeDuration(any(Path.class))).thenReturn(42.0);
        stubTrimOutput();

        VideoScript script = service.createAnalysis(
                video("demo.mov"), null, "中国", null, "PRODUCT_ONLY", false,
                new BigDecimal("7.25"), principal("org-a", "user-a"));

        assertThat(script.getSourceDurationSeconds()).isEqualByComparingTo("42");
        assertThat(script.getClipStartSecond()).isEqualByComparingTo("7.25");
        assertThat(script.getClipEndSecond()).isEqualByComparingTo("22.25");
        assertThat(script.getSourceFilePath()).endsWith("source.mp4");
        assertThat(script.getSourceContentType()).isEqualTo("video/mp4");
        assertThat(script.getSourceSizeBytes()).isEqualTo(3L);
        assertThat(Files.isRegularFile(Path.of(script.getSourceFilePath()).getParent().resolve("original.mov"))).isTrue();
        verify(frameExtractor).trim(any(Path.class), any(Path.class),
                org.mockito.ArgumentMatchers.eq(7.25), org.mockito.ArgumentMatchers.eq(15.0));
        verify(scriptMapper).insert(script);
        verify(processor).analyze(script.getId());
    }

    @Test
    void trimsExplicitStartAndEndToAnyShorterSelection() throws Exception {
        when(frameExtractor.probeDuration(any(Path.class))).thenReturn(42.0);
        stubTrimOutput();

        VideoScript script = service.createAnalysis(
                video("selection.mp4"), null, null, null, "PRODUCT_ONLY", false,
                new BigDecimal("7.2"), new BigDecimal("13.7"), principal("org-a", "user-a"));

        assertThat(script.getClipStartSecond()).isEqualByComparingTo("7.2");
        assertThat(script.getClipEndSecond()).isEqualByComparingTo("13.7");
        assertThat(script.getSourceFilePath()).endsWith("source.mp4");
        verify(frameExtractor).trim(any(Path.class), any(Path.class),
                org.mockito.ArgumentMatchers.eq(7.2), org.mockito.ArgumentMatchers.eq(6.5));
    }

    @Test
    void usesWholeVideoWhenUploadIsNotLongerThanFifteenSeconds() {
        when(frameExtractor.probeDuration(any(Path.class))).thenReturn(9.8);

        VideoScript script = service.createAnalysis(
                video("short.mp4"), null, null, null, "MODEL_WEAR", false,
                BigDecimal.ZERO, principal("org-a", "user-a"));

        assertThat(script.getSourceDurationSeconds()).isEqualByComparingTo("9.8");
        assertThat(script.getClipStartSecond()).isEqualByComparingTo("0");
        assertThat(script.getClipEndSecond()).isEqualByComparingTo("9.8");
        assertThat(script.getSourceFilePath()).endsWith("original.mp4");
        verify(frameExtractor, never()).trim(any(), any(), anyDouble(), anyDouble());
    }

    @Test
    void shortVideoCanUseCustomSubclip() throws Exception {
        when(frameExtractor.probeDuration(any(Path.class))).thenReturn(9.8);
        stubTrimOutput();

        VideoScript script = service.createAnalysis(
                video("short.mp4"), null, null, null, "MODEL_WEAR", false,
                new BigDecimal("1.2"), new BigDecimal("5.4"), principal("org-a", "user-a"));

        assertThat(script.getClipStartSecond()).isEqualByComparingTo("1.2");
        assertThat(script.getClipEndSecond()).isEqualByComparingTo("5.4");
        verify(frameExtractor).trim(any(Path.class), any(Path.class),
                org.mockito.ArgumentMatchers.eq(1.2), org.mockito.ArgumentMatchers.eq(4.2));
    }

    @Test
    void rejectsStartThatCannotFitFixedFifteenSecondWindow() {
        when(frameExtractor.probeDuration(any(Path.class))).thenReturn(20.0);

        assertThatThrownBy(() -> service.createAnalysis(
                video("demo.mp4"), null, null, null, "PRODUCT_ONLY", false,
                new BigDecimal("5.051"), principal("org-a", "user-a")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("最晚为 5 秒")
                .satisfies(error -> assertThat(((BusinessException) error).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST));

        verify(scriptMapper, never()).insert(any(VideoScript.class));
        verify(processor, never()).analyze(any());
    }

    @Test
    void shortVideoOnlyAcceptsWholeVideoStart() {
        assertThatThrownBy(() -> VideoScriptService.clipWindow(
                new BigDecimal("12.4"), new BigDecimal("0.1")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("开始位置必须为 0");
    }

    @Test
    void anySourceLongerThanFifteenSecondsUsesFixedClip() {
        VideoScriptService.ClipWindow window = VideoScriptService.clipWindow(
                new BigDecimal("15.0004"), BigDecimal.ZERO);

        assertThat(window.trimmed()).isTrue();
        assertThat(window.endSecond()).isEqualByComparingTo("15");
    }

    @Test
    void rejectsExplicitSelectionLongerThanFifteenSeconds() {
        assertThatThrownBy(() -> VideoScriptService.clipWindow(
                new BigDecimal("60"), new BigDecimal("2"), new BigDecimal("17.1")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能超过 15 秒");
    }

    @Test
    void rejectsReversedAndOutOfBoundsExplicitSelections() {
        assertThatThrownBy(() -> VideoScriptService.clipWindow(
                new BigDecimal("20"), new BigDecimal("8"), new BigDecimal("7")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("结束位置必须大于开始位置");
        assertThatThrownBy(() -> VideoScriptService.clipWindow(
                new BigDecimal("20"), BigDecimal.ZERO, new BigDecimal("20.2")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能超过源视频时长");
        assertThatThrownBy(() -> VideoScriptService.clipWindow(
                new BigDecimal("20"), new BigDecimal("20"), new BigDecimal("20.01")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("开始位置必须小于源视频时长");
    }

    @Test
    void clampsTinyBrowserDurationDifferenceAtSourceTail() {
        VideoScriptService.ClipWindow window = VideoScriptService.clipWindow(
                new BigDecimal("9.984"), BigDecimal.ZERO, new BigDecimal("10"));

        assertThat(window.trimmed()).isFalse();
        assertThat(window.endSecond()).isEqualByComparingTo("9.984");
    }

    @Test
    void rejectsSelectionShorterThanOneTenthSecond() {
        assertThatThrownBy(() -> VideoScriptService.clipWindow(
                new BigDecimal("20"), new BigDecimal("4"), new BigDecimal("4.099")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能短于 0.1 秒");
    }

    private void stubTrimOutput() throws Exception {
        doAnswer(invocation -> {
            Path output = invocation.getArgument(1);
            Files.write(output, new byte[]{9, 8, 7});
            return null;
        }).when(frameExtractor).trim(any(Path.class), any(Path.class), anyDouble(), anyDouble());
    }

    private MockMultipartFile video(String name) {
        return new MockMultipartFile("video", name, "video/mp4", new byte[]{1, 2, 3, 4});
    }

    private PlatformPrincipal principal(String organizationId, String userId) {
        return new PlatformPrincipal(
                userId, organizationId, "demo", "13800000000", "测试用户",
                "password", true, List.of("ADMIN"), List.of("video:script:manage")
        );
    }
}
