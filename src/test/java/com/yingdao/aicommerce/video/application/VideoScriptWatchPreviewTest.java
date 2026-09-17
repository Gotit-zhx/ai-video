package com.yingdao.aicommerce.video.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.video.domain.VideoScript;
import com.yingdao.aicommerce.video.domain.VideoScriptShot;
import com.yingdao.aicommerce.video.mapper.VideoScriptMapper;
import com.yingdao.aicommerce.video.mapper.VideoScriptShotMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class VideoScriptWatchPreviewTest {
    @TempDir Path storage;

    @BeforeAll
    static void initializeTableMetadata() {
        var assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, VideoScript.class);
        TableInfoHelper.initTableInfo(assistant, VideoScriptShot.class);
    }

    @Test
    void usesTheExactWatchEvidencePreviewBeforeLegacyAnalysisFrames() throws Exception {
        var fixture = new Fixture();
        Path exact = Files.createDirectories(storage.resolve("task/shot-previews")).resolve("shot-002.jpg");
        Files.write(exact, new byte[]{1});
        Path legacy = Files.createDirectories(storage.resolve("task/frames")).resolve("frame-001.jpg");
        Files.write(legacy, new byte[]{2});

        var preview = fixture.service.shotPreview("script", fixture.shot.getId(), fixture.principal);

        assertThat(preview.path()).isEqualTo(exact);
        assertThat(preview.lastModified()).isNotNull();
        verify(fixture.shots, never()).selectList(any());
        verifyNoInteractions(fixture.frames);
    }

    @Test
    void olderScriptsStillUseTheirExistingFramesWithoutRunningNewExtraction() throws Exception {
        var fixture = new Fixture();
        Path legacy = Files.createDirectories(storage.resolve("task/frames")).resolve("frame-001.jpg");
        Files.write(legacy, new byte[]{2});
        when(fixture.shots.selectList(any())).thenReturn(List.of(fixture.shot));

        var preview = fixture.service.shotPreview("script", fixture.shot.getId(), fixture.principal);

        assertThat(preview.path()).isEqualTo(legacy);
        verifyNoInteractions(fixture.frames);
    }

    @Test
    void failedNewPreviewGenerationCannotFallBackToStaleLegacyEvidence() throws Exception {
        var fixture = new Fixture();
        Files.createDirectories(storage.resolve("task/shot-previews"));
        Path legacy = Files.createDirectories(storage.resolve("task/frames")).resolve("frame-001.jpg");
        Files.write(legacy, new byte[]{2});
        assertThatThrownBy(() -> fixture.service.shotPreview("script", fixture.shot.getId(), fixture.principal))
                .hasMessageContaining("本次分镜预览尚未生成");
        verifyNoInteractions(fixture.frames);
    }

    private class Fixture {
        final VideoScriptMapper scripts = mock(VideoScriptMapper.class);
        final VideoScriptShotMapper shots = mock(VideoScriptShotMapper.class);
        final VideoFrameExtractor frames = mock(VideoFrameExtractor.class);
        final PlatformPrincipal principal = new PlatformPrincipal("user", "org", "org", "13800000000", "测试用户", "password", true,
                List.of("ADMIN"), List.of("video:script:view"));
        final VideoScriptShot shot = new VideoScriptShot("org", "script", 2, "CONTENT", "镜头",
                BigDecimal.ONE, new BigDecimal("2"), BigDecimal.ONE, "画面", "动作", "微幅", "",
                "中景", "跟拍", "切换", "自然", "{}");
        final VideoScriptService service;

        Fixture() {
            var script = new VideoScript("script", "org", "名称", "VIDEO_ANALYSIS", "MODEL_WEAR", "source.mp4",
                    storage.resolve("task/source.mp4").toString(), "video/mp4", 3L, null, null, "user");
            when(scripts.selectById("script")).thenReturn(script);
            when(shots.selectOne(any())).thenReturn(shot);
            service = new VideoScriptService(storage.toString(), scripts, shots, mock(VideoScriptAnalysisProcessor.class), frames, new ObjectMapper());
        }
    }
}
