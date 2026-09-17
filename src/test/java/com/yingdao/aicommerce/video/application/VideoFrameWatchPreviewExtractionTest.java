package com.yingdao.aicommerce.video.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class VideoFrameWatchPreviewExtractionTest {
    @TempDir Path storage;

    @Test
    void rendersOnlyNineDisplayJpegsAndInvalidatesPreviousGenerationOnFailure() throws Exception {
        boolean ffmpegAvailable;
        try {
            var check = new ProcessBuilder("ffmpeg", "-version").redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            ffmpegAvailable = check.waitFor(10, TimeUnit.SECONDS) && check.exitValue() == 0;
        } catch (java.io.IOException missing) { ffmpegAvailable = false; }
        assumeTrue(ffmpegAvailable, "FFmpeg is required for the real media extraction check");
        Path source = storage.resolve("source.mp4");
        var create = new ProcessBuilder("ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
                "-f", "lavfi", "-i", "testsrc2=size=160x90:rate=10", "-t", "2", "-c:v", "mpeg4",
                source.toString()).redirectErrorStream(true).start();
        assertThat(create.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assertThat(create.exitValue()).as(new String(create.getInputStream().readAllBytes())).isZero();
        Path previews = Files.createDirectories(storage.resolve("shot-previews"));
        Files.write(previews.resolve("shot-001.jpg"), new byte[]{1, 2, 3});
        Map<Integer, Double> times = new LinkedHashMap<>();
        for (int index = 0; index < 9; index++) times.put(index + 1, index == 3 ? 0.4 : index * 0.2);

        var extractor = new VideoFrameExtractor();
        extractor.extractShotPreviews(source, previews, times);

        try (var files = Files.list(previews)) {
            var images = files.toList();
            assertThat(images).hasSize(9);
            for (Path image : images) assertThat(ImageIO.read(image.toFile())).isNotNull();
        }
        assertThat(storage.resolve("frames")).doesNotExist();
        assertThat(storage.resolve("motion-frames")).doesNotExist();
        assertThatThrownBy(() -> extractor.extractShotPreviews(source, previews, Map.of(1, Double.NaN)))
                .hasMessageContaining("分镜预览时间无效");
        try (var files = Files.list(previews)) { assertThat(files.toList()).isEmpty(); }
        try (var files = Files.list(storage)) {
            assertThat(files.map(path -> path.getFileName().toString()).toList())
                    .noneMatch(name -> name.startsWith("shot-preview-"));
        }
    }
}
