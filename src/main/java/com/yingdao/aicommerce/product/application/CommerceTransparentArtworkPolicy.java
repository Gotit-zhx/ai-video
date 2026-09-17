package com.yingdao.aicommerce.product.application;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import javax.imageio.ImageIO;

/** Validates the received native alpha; never creates transparency or interprets white artwork. */
public final class CommerceTransparentArtworkPolicy {
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final int MAX_DIMENSION = 4096;
    private static final long MAX_PIXELS = 12_000_000;
    private static final String FAILURE = "模型未返回可用的原生透明印花，已保留原图，未继续套版";

    private CommerceTransparentArtworkPolicy() {}

    /** The caller must archive the model response before invoking this read-only gate. */
    public static void requireNative(byte[] original) {
        if (original == null || original.length == 0 || original.length > MAX_BYTES)
            throw rejected("图片为空或超过 8 MB。");
        var report = CommerceArtworkTransparency.inspect(original);
        if (!report.verified() || !report.alphaChannel())
            throw rejected("需要包含可见图案及真实透明背景的 PNG；白底、JPEG 或全透明图片不能用于套版。");
        if (report.width() < 2 || report.height() < 2 || report.width() > MAX_DIMENSION
                || report.height() > MAX_DIMENSION || report.totalPixels() > MAX_PIXELS)
            throw rejected("透明印花尺寸不符合要求。");
        if (report.visiblePixels() < Math.max(4, report.totalPixels() / 10_000)
                || report.clearPixels() < Math.max(4, report.totalPixels() * .03))
            throw rejected("透明画布或可见图案不足，不能将孤立透明像素当作完成去底。");

        final int[] pixels;
        try (var input = new ByteArrayInputStream(original)) {
            var image = ImageIO.read(input);
            if (image == null) throw new IOException("PNG could not be decoded");
            pixels = image.getRGB(0, 0, report.width(), report.height(), null, 0, report.width());
        } catch (IOException | RuntimeException malformed) {
            throw rejected("原生透明图片无法完整解码。");
        }
        if (!CommerceArtworkMatte.trustworthyAlphaCanvas(pixels, report.width(), report.height()))
            throw rejected("没有足够的边缘连通透明留边；局部透明或过薄透明边框不能代替原生透明背景。");
    }

    private static IllegalArgumentException rejected(String reason) {
        return new IllegalArgumentException(FAILURE + "：" + reason);
    }
}
