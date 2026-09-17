package com.yingdao.aicommerce.product.application;

import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;

/** Detection-only full-canvas view. Never use these altered pixels as a generation reference. */
final class WatermarkDetectionView {
    static final int MAX_EDGE = 960;
    static final int MAX_BYTES = 256 * 1024;
    static final String BACKGROUND_METHOD = "SIMPLE_NEAR_WHITE_BACKGROUND_V1";
    record View(byte[] bytes, String mimeType) {}

    private WatermarkDetectionView() {}

    /** Eligibility to skip editing, not a claim that any pixel contains a watermark. */
    static Map<String, Object> assessBackground(byte[] original) {
        var image = compactDecode(original);
        int width = image.getWidth(), height = image.getHeight();
        int edgeX = Math.max(1, (int) Math.ceil(width * .08)), edgeY = Math.max(1, (int) Math.ceil(height * .08));
        int nearWhite = 0, edgeWhite = 0, edgePixels = 0;
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int rgb = image.getRGB(x, y), red = (rgb >>> 16) & 255, green = (rgb >>> 8) & 255, blue = rgb & 255;
            int min = Math.min(red, Math.min(green, blue)), max = Math.max(red, Math.max(green, blue));
            boolean white = min >= 235 && max - min <= 15;
            if (white) nearWhite++;
            if (x < edgeX || x >= width - edgeX || y < edgeY || y >= height - edgeY) {
                edgePixels++;
                if (white) edgeWhite++;
            }
        }
        double fraction = (double) nearWhite / (width * height), edgeFraction = (double) edgeWhite / edgePixels;
        boolean eligible = fraction >= .5 && edgeFraction >= .85;
        return Map.of("method", BACKGROUND_METHOD, "eligibleToSkip", eligible,
                "nearWhiteFraction", fraction, "borderNearWhiteFraction", edgeFraction,
                "borderFraction", .08, "sampleWidth", width, "sampleHeight", height,
                "reason", eligible ? "简单近白背景满足跳过生图的像素条件，仍须两次前置确认无水印"
                        : "原图不满足简单近白背景的跳过条件，按保守规则直接生图；此结果不代表检测到水印");
    }

    static boolean permitsSkipping(Map<?, ?> assessment) {
        return BACKGROUND_METHOD.equals(assessment.get("method")) && Boolean.TRUE.equals(assessment.get("eligibleToSkip"))
                && fractionAtLeast(assessment.get("nearWhiteFraction"), .5)
                && fractionAtLeast(assessment.get("borderNearWhiteFraction"), .85);
    }

    private static boolean fractionAtLeast(Object value, double threshold) {
        return value instanceof Number number && Double.isFinite(number.doubleValue())
                && number.doubleValue() >= threshold && number.doubleValue() <= 1;
    }

    static View create(byte[] original) {
        BufferedImage image = compactDecode(original);
        enhance(image);
        try {
            for (float quality : new float[]{.9f, .75f, .55f, .35f}) {
                byte[] bytes = jpeg(image, quality);
                if (bytes.length <= MAX_BYTES) return new View(bytes, "image/jpeg");
            }
            throw new IllegalArgumentException("增强检测图超过大小限制");
        } catch (IOException e) {
            throw new IllegalArgumentException("增强检测图编码失败", e);
        }
    }

    private static BufferedImage compactDecode(byte[] bytes) {
        if (bytes == null || bytes.length < 12 || bytes.length > 8 * 1024 * 1024)
            throw new IllegalArgumentException("增强检测原图大小无效");
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IllegalArgumentException("增强检测原图无法解码");
            var reader = readers.next();
            try {
                reader.setInput(input);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > 8192 || height > 8192 || (long) width * height > 25_000_000)
                    throw new IllegalArgumentException("增强检测原图像素过大");
                var parameters = reader.getDefaultReadParam();
                int sample = Math.max(1, Math.max(width, height) / MAX_EDGE);
                parameters.setSourceSubsampling(sample, sample, 0, 0);
                var decoded = reader.read(0, parameters);
                double scale = Math.min(1, (double) MAX_EDGE / Math.max(width, height));
                var compact = new BufferedImage(Math.max(1, (int) Math.round(width * scale)),
                        Math.max(1, (int) Math.round(height * scale)), BufferedImage.TYPE_INT_RGB);
                var graphics = compact.createGraphics();
                try {
                    graphics.setColor(Color.WHITE);
                    graphics.fillRect(0, 0, compact.getWidth(), compact.getHeight());
                    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                    graphics.drawImage(decoded, 0, 0, compact.getWidth(), compact.getHeight(), null);
                } finally { graphics.dispose(); }
                return compact;
            } finally { reader.dispose(); }
        } catch (IOException e) {
            throw new IllegalArgumentException("增强检测原图无法解码", e);
        }
    }

    // Contrast-limited local histograms expose faint dark overlays. Bilinear interpolation
    // avoids tile seams; gamma lifts shadows. Grayscale avoids adding false color evidence.
    private static void enhance(BufferedImage image) {
        int width = image.getWidth(), height = image.getHeight();
        int tilesX = Math.min(8, Math.max(1, width / 32)), tilesY = Math.min(8, Math.max(1, height / 32));
        int[] luminance = new int[width * height];
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int rgb = image.getRGB(x, y);
            luminance[y * width + x] = (77 * ((rgb >>> 16) & 255) + 150 * ((rgb >>> 8) & 255) + 29 * (rgb & 255) + 128) >>> 8;
        }
        int[][] curves = new int[tilesX * tilesY][256];
        for (int ty = 0; ty < tilesY; ty++) for (int tx = 0; tx < tilesX; tx++) {
            int left = tx * width / tilesX, right = (tx + 1) * width / tilesX;
            int top = ty * height / tilesY, bottom = (ty + 1) * height / tilesY;
            int count = (right - left) * (bottom - top), clip = Math.max(1, count * 3 / 256);
            int[] histogram = new int[256];
            for (int y = top; y < bottom; y++) for (int x = left; x < right; x++) histogram[luminance[y * width + x]]++;
            int excess = 0;
            for (int i = 0; i < 256; i++) { excess += Math.max(0, histogram[i] - clip); histogram[i] = Math.min(clip, histogram[i]); }
            int cumulative = 0;
            for (int i = 0; i < 256; i++) {
                int remainder = excess % 256;
                cumulative += histogram[i] + excess / 256 + (i + 1) * remainder / 256 - i * remainder / 256;
                curves[ty * tilesX + tx][i] = Math.min(255, (int) Math.round(cumulative * 255.0 / count));
            }
        }
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            double gx = Math.max(0, Math.min(tilesX - 1, (x + .5) * tilesX / width - .5));
            double gy = Math.max(0, Math.min(tilesY - 1, (y + .5) * tilesY / height - .5));
            int ix = (int) gx, iy = (int) gy, nx = Math.min(tilesX - 1, ix + 1), ny = Math.min(tilesY - 1, iy + 1);
            double fx = gx - ix, fy = gy - iy;
            int value = luminance[y * width + x];
            double local = curves[iy * tilesX + ix][value] * (1 - fx) * (1 - fy)
                    + curves[iy * tilesX + nx][value] * fx * (1 - fy)
                    + curves[ny * tilesX + ix][value] * (1 - fx) * fy + curves[ny * tilesX + nx][value] * fx * fy;
            int lifted = (int) Math.round(255 * Math.pow((value * .55 + local * .45) / 255, .6));
            image.setRGB(x, y, lifted * 0x010101);
        }
    }

    private static byte[] jpeg(BufferedImage image, float quality) throws IOException {
        var writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) throw new IOException("JPEG编码器不可用");
        var writer = writers.next();
        try (var bytes = new ByteArrayOutputStream(); var output = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(output);
            var parameters = writer.getDefaultWriteParam();
            parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            parameters.setCompressionQuality(quality);
            writer.write(null, new IIOImage(image, null, null), parameters);
            output.flush();
            return bytes.toByteArray();
        } finally { writer.dispose(); }
    }
}
