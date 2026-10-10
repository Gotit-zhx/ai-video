package com.yingdao.aicommerce.product.application.provider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ShopeeVideoPreprocessorTest {
    @TempDir Path dir;
    byte[] fixture(double seconds) throws Exception {
        try { assumeTrue(new ProcessBuilder("ffmpeg", "-version").redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor() == 0); }
        catch (java.io.IOException e) { assumeTrue(false, "FFmpeg required for integration test"); }
        Path video = dir.resolve("source.mp4");
        assertThat(new ProcessBuilder("ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", "testsrc2=size=160x120:rate=30", "-t", String.valueOf(seconds), "-c:v", "libx264", "-threads", "1", video.toString()).inheritIO().start().waitFor()).isZero();
        return Files.readAllBytes(video);
    }
    @Test void padsNearTenSecondClipAndReusesExactBytes() throws Exception {
        byte[] source = fixture(9.938), copy = source.clone();
        var processor = new ShopeeVideoPreprocessor(dir);
        byte[] normalized = processor.prepare(source);
        assertThat(normalized).isNotEqualTo(source);
        assertThat(processor.prepare(source)).isEqualTo(normalized);
        assertThat(source).isEqualTo(copy);
    }
    @Test void compliantVideoIsNotReencoded() throws Exception {
        byte[] source = fixture(12);
        assertThat(new ShopeeVideoPreprocessor(dir).prepare(source)).isSameAs(source);
    }
    @Test void refusesMateriallyShortAndLongVideosWithoutSilentlyTrimming() throws Exception {
        byte[] shortVideo = fixture(8);
        assertThatThrownBy(() -> new ShopeeVideoPreprocessor(dir).prepare(shortVideo)).hasMessageContaining("10–60 秒");
        byte[] longVideo = fixture(61);
        assertThatThrownBy(() -> new ShopeeVideoPreprocessor(dir).prepare(longVideo)).hasMessageContaining("10–60 秒");
    }
    @Test void oversizeMediaIsCompressedBelowDecimalThirtyMb() throws Exception {
        byte[] source = fixture(12);
        // Valid MP4 with a large trailing free atom, exercising the actual oversize upload path.
        var buffer = java.nio.ByteBuffer.allocate(31_000_000);
        buffer.put(source).putInt(31_000_000 - source.length).put(new byte[]{'f','r','e','e'});
        byte[] result = new ShopeeVideoPreprocessor(dir).prepare(buffer.array());
        assertThat(result.length).isLessThanOrEqualTo((int) ShopeeVideoPreprocessor.LIMIT);
        assertThat(result.length).isLessThan(buffer.array().length);
    }
}
