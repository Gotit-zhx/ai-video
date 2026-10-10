package com.yingdao.aicommerce.video.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StoryboardFrameExtractorTest {
    @TempDir Path storage;

    @Test
    void splitsAllNineCellsInRowMajorOrderWithoutTrimmingEdgeSubjects() {
        BufferedImage grid = grid();
        grid.setRGB(0, 0, Color.MAGENTA.getRGB());
        grid.setRGB(299, 449, Color.CYAN.getRGB());
        List<BufferedImage> frames = StoryboardFrameExtractor.split(grid);
        assertThat(frames).hasSize(9);
        for (int index = 0; index < 9; index++) {
            assertThat(frames.get(index).getWidth()).isEqualTo(100);
            assertThat(frames.get(index).getHeight()).isEqualTo(150);
            assertThat(frames.get(index).getRGB(50, 75)).isEqualTo(color(index).getRGB());
        }
        assertThat(frames.getFirst().getRGB(0, 0)).isEqualTo(Color.MAGENTA.getRGB());
        assertThat(frames.getLast().getRGB(99, 149)).isEqualTo(Color.CYAN.getRGB());
    }

    @Test
    void removesOnlyNarrowNeutralSeparatorsAcrossTheWholeGrid() {
        BufferedImage grid = grid();
        Graphics2D graphics = grid.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(99, 0, 2, 450);
        graphics.fillRect(199, 0, 2, 450);
        graphics.fillRect(0, 149, 300, 2);
        graphics.fillRect(0, 299, 300, 2);
        graphics.dispose();
        List<BufferedImage> frames = StoryboardFrameExtractor.split(grid);
        for (int index = 0; index < 9; index++) {
            BufferedImage frame = frames.get(index);
            assertThat(frame.getRGB(0, 0)).isEqualTo(color(index).getRGB());
            assertThat(frame.getRGB(frame.getWidth() - 1, frame.getHeight() - 1)).isEqualTo(color(index).getRGB());
        }
        assertThat(grid.getRGB(99, 0)).isEqualTo(Color.WHITE.getRGB());
    }

    @Test
    void doesNotTreatAWhiteClothingLineInOneCellAsAGridSeparator() {
        BufferedImage grid = grid();
        for (int y = 0; y < 150; y++) grid.setRGB(99, y, Color.WHITE.getRGB());
        BufferedImage first = StoryboardFrameExtractor.split(grid).getFirst();
        assertThat(first.getWidth()).isEqualTo(100);
        assertThat(first.getRGB(99, 50)).isEqualTo(Color.WHITE.getRGB());
    }

    @Test
    void rejectsUnreadableOrTinyInputs() {
        assertThatThrownBy(() -> StoryboardFrameExtractor.split(null)).hasMessageContaining("尺寸不足");
        assertThatThrownBy(() -> StoryboardFrameExtractor.split(new BufferedImage(3, 3, BufferedImage.TYPE_INT_RGB)))
                .hasMessageContaining("尺寸不足");
    }

    @Test
    void cachesCompleteJpegsAtomicallyUnderContentDigestAndInvalidatesForChangedGrid() throws Exception {
        VideoReferenceDeliveryService delivery = new VideoReferenceDeliveryService(storage.toString());
        byte[] firstBytes = png(grid());
        List<VideoReferenceDeliveryService.Delivery> first;
        try (var executor = Executors.newFixedThreadPool(4)) {
            List<Callable<List<VideoReferenceDeliveryService.Delivery>>> work = new ArrayList<>();
            for (int i = 0; i < 8; i++) work.add(() -> delivery.prepareStoryboardFrames(firstBytes));
            var results = executor.invokeAll(work);
            first = results.getFirst().get();
            for (var result : results) assertThat(result.get()).isEqualTo(first);
        }
        assertThat(first).hasSize(9);
        for (int index = 0; index < 9; index++) {
            var image = first.get(index);
            assertThat(image.path()).hasFileName(String.format("%02d.jpg", index + 1));
            assertThat(image.path()).isRegularFile();
            assertThat(ImageIO.read(image.path().toFile())).isNotNull();
        }
        BufferedImage changed = grid();
        changed.setRGB(1, 1, Color.PINK.getRGB());
        var second = delivery.prepareStoryboardFrames(png(changed));
        assertThat(second.getFirst().path().getParent()).isNotEqualTo(first.getFirst().path().getParent());
        try (var paths = Files.walk(storage)) {
            assertThat(paths.filter(path -> path.getFileName().toString().startsWith("reference-")).toList()).isEmpty();
        }
    }

    static BufferedImage grid() {
        BufferedImage grid = new BufferedImage(300, 450, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = grid.createGraphics();
        for (int index = 0; index < 9; index++) {
            graphics.setColor(color(index));
            graphics.fillRect(index % 3 * 100, index / 3 * 150, 100, 150);
        }
        graphics.dispose();
        return grid;
    }

    static byte[] png(BufferedImage image) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private static Color color(int index) { return new Color(20 + index * 20, 70 + index * 10, 160 - index * 10); }
}
