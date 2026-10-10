package com.yingdao.aicommerce.product.application;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;

/** Byte-level output facts, not visual quality approval. Never infers alpha from the prompt or MIME. */
public final class CommerceArtworkTransparency {
    private static final long MAX_PIXELS = 16_777_216L;
    private static final int MAX_BYTES = 64 * 1024 * 1024;
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};

    private CommerceArtworkTransparency() {}

    public record Report(String status, boolean alphaChannel, int width, int height,
                         long clearPixels, long visiblePixels, long totalPixels) {
        public boolean verified() { return "VERIFIED_ALPHA".equals(status); }
    }

    /** Inspect only after persisting the received response, including after downloading a saved URL. */
    public static Report inspect(byte[] bytes) {
        if (bytes == null || bytes.length < PNG.length || bytes.length > MAX_BYTES)
            return unavailable("INVALID_IMAGE");
        for (int i = 0; i < PNG.length; i++) if (bytes[i] != PNG[i]) return unavailable("NOT_PNG");
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) return unavailable("INVALID_IMAGE");
            var reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                long total = (long) width * height;
                if (width < 1 || height < 1 || total > MAX_PIXELS) return unavailable("INVALID_IMAGE");
                var image = reader.read(0);
                if (image == null) return unavailable("INVALID_IMAGE");
                boolean alpha = image.getColorModel().hasAlpha();
                long clear = 0, visible = 0;
                boolean partiallyTransparent = false;
                for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                    int opacity = image.getRGB(x, y) >>> 24;
                    if (opacity <= 8) clear++;
                    if (opacity >= 32) visible++;
                    if (opacity < 255) partiallyTransparent = true;
                }
                String status = visible == 0 ? "EMPTY_ARTWORK"
                        : clear > 0 && alpha ? "VERIFIED_ALPHA"
                        : partiallyTransparent ? "NO_CLEAR_BACKGROUND" : "OPAQUE";
                return new Report(status, alpha, width, height, clear, visible, total);
            } finally { reader.dispose(); }
        } catch (IOException | RuntimeException malformed) {
            // Do not turn a completed paid response into an uncertain submission or a new model call.
            return unavailable("INVALID_IMAGE");
        }
    }

    private static Report unavailable(String status) { return new Report(status, false, 0, 0, 0, 0, 0); }
}
