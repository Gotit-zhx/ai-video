package com.yingdao.aicommerce.video.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class VideoCameraMotionAnalyzerTest {

    @TempDir
    Path tempDirectory;

    private final VideoCameraMotionAnalyzer analyzer = new VideoCameraMotionAnalyzer();

    @Test
    void detectsFixedCameraFromStableBackground() throws Exception {
        List<Path> frames = writeSequence(tempDirectory.resolve("fixed"), 0);
        var analysis = analyzer.analyze(new VideoFrameExtractor.ExtractedFrames(2.0, 5.0, frames));

        assertThat(analysis.windows()).isNotEmpty();
        assertThat(analysis.windows()).allMatch(window -> window.label().contains("固定机位"));
        assertThat(analyzer.cameraMovementFor(
                analysis, BigDecimal.ZERO, BigDecimal.valueOf(1.9), "模型判断"
        )).contains("固定机位").contains("背景特征检测");
    }

    @Test
    void detectsHorizontalCameraMotionFromBackgroundDisplacement() throws Exception {
        List<Path> frames = writeSequence(tempDirectory.resolve("pan"), 2);
        var analysis = analyzer.analyze(new VideoFrameExtractor.ExtractedFrames(2.0, 5.0, frames));

        assertThat(analysis.windows()).isNotEmpty();
        assertThat(analysis.windows()).anyMatch(window -> window.label().contains("向右平移"));
        assertThat(analyzer.cameraMovementFor(
                analysis, BigDecimal.ZERO, BigDecimal.valueOf(1.9), "模型判断"
        )).contains("向右平移").contains("背景特征检测");
    }

    @Test
    void detectsPushInFromRadialBackgroundExpansion() throws Exception {
        List<Path> frames = writeZoomSequence(tempDirectory.resolve("zoom"));
        var analysis = analyzer.analyze(new VideoFrameExtractor.ExtractedFrames(2.0, 5.0, frames));

        assertThat(analysis.windows()).anyMatch(window -> window.label().contains("推近"));
    }

    @Test
    void detectsJitterFromAlternatingBackgroundDisplacement() throws Exception {
        List<Path> frames = writeJitterSequence(tempDirectory.resolve("jitter"));
        var analysis = analyzer.analyze(new VideoFrameExtractor.ExtractedFrames(2.0, 5.0, frames));

        assertThat(analysis.windows()).anyMatch(window -> window.label().contains("抖动"));
    }

    private List<Path> writeSequence(Path directory, int shiftPerFrame) throws Exception {
        java.nio.file.Files.createDirectories(directory);
        int width = 240;
        int height = 160;
        int[][] base = texture(width, height);
        List<Path> files = new ArrayList<>();
        for (int frame = 0; frame < 10; frame++) {
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            int shift = frame * shiftPerFrame;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int sourceX = Math.floorMod(x + shift, width);
                    int value = base[y][sourceX];
                    image.setRGB(x, y, (value << 16) | (value << 8) | value);
                }
            }
            Path file = directory.resolve(String.format("frame-%03d.png", frame));
            ImageIO.write(image, "png", file.toFile());
            files.add(file);
        }
        return files;
    }

    private List<Path> writeZoomSequence(Path directory) throws Exception {
        java.nio.file.Files.createDirectories(directory);
        int width = 240;
        int height = 160;
        int[][] base = texture(width, height);
        List<Path> files = new ArrayList<>();
        for (int frame = 0; frame < 10; frame++) {
            double scale = 1.0 + frame * 0.012;
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int sourceX = Math.max(0, Math.min(width - 1,
                            (int) Math.round(width / 2.0 + (x - width / 2.0) / scale)));
                    int sourceY = Math.max(0, Math.min(height - 1,
                            (int) Math.round(height / 2.0 + (y - height / 2.0) / scale)));
                    int value = base[sourceY][sourceX];
                    image.setRGB(x, y, (value << 16) | (value << 8) | value);
                }
            }
            Path file = directory.resolve(String.format("frame-%03d.png", frame));
            ImageIO.write(image, "png", file.toFile());
            files.add(file);
        }
        return files;
    }

    private List<Path> writeJitterSequence(Path directory) throws Exception {
        java.nio.file.Files.createDirectories(directory);
        int width = 240;
        int height = 160;
        int[][] base = texture(width, height);
        int[] offsets = {0, 2, -2, 2, -2, 2, -2, 2, -2, 2};
        List<Path> files = new ArrayList<>();
        for (int frame = 0; frame < offsets.length; frame++) {
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int sourceX = Math.floorMod(x + offsets[frame], width);
                    int value = base[y][sourceX];
                    image.setRGB(x, y, (value << 16) | (value << 8) | value);
                }
            }
            Path file = directory.resolve(String.format("frame-%03d.png", frame));
            ImageIO.write(image, "png", file.toFile());
            files.add(file);
        }
        return files;
    }

    private int[][] texture(int width, int height) {
        int[][] base = new int[height][width];
        Random random = new Random(20260807L);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) base[y][x] = random.nextInt(256);
        }
        return base;
    }
}
