package com.yingdao.aicommerce.product.application;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;

/** Exact pixel crops from one image; never mixes generated views with the source photograph. */
final class CommercePrintReferenceSheet {
    private CommercePrintReferenceSheet() {}
    static Map<String, byte[]> split(byte[] bytes) {
        BufferedImage sheet = decode(bytes);
        int w = sheet.getWidth(), h = sheet.getHeight();
        if (w != h || w < 512 || w % 2 != 0) throw new IllegalArgumentException("参考图必须是完整的正方形四宫格");
        var result = new LinkedHashMap<String, byte[]>();
        String[] sides = {"FRONT", "BACK", "LEFT", "RIGHT"};
        for (int i = 0; i < sides.length; i++) {
            var cell = sheet.getSubimage((i % 2) * (w / 2), (i / 2) * (h / 2), w / 2, h / 2);
            if (!hasContent(cell)) throw new IllegalArgumentException("四面参考图有空白区域，不能当作完整参考");
            try {
                var stream = new ByteArrayOutputStream();
                if (!ImageIO.write(cell, "png", stream)) throw new IllegalArgumentException("无法编码参考图");
                result.put(sides[i], stream.toByteArray());
            } catch (IOException error) { throw new IllegalStateException("参考图裁切失败", error); }
        }
        return Collections.unmodifiableMap(result);
    }
    static BufferedImage decode(byte[] bytes) {
        if (bytes == null || bytes.length < 12 || bytes.length > 8 * 1024 * 1024) throw new IllegalArgumentException("参考图片大小无效");
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IllegalArgumentException("图片无法解码");
            var reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 24 || height < 24 || width > 8192 || height > 8192 || (long) width * height > 16_777_216L)
                    throw new IllegalArgumentException("图片分辨率超出处理范围");
                var result = reader.read(0);
                if (result == null) throw new IllegalArgumentException("图片无法解码");
                return result;
            } finally { reader.dispose(); }
        } catch (IOException error) { throw new IllegalArgumentException("图片无法解码", error); }
    }
    private static boolean hasContent(BufferedImage image) {
        int lowR = 255, lowG = 255, lowB = 255, highR = 0, highG = 0, highB = 0, opaque = 0;
        for (int y = 0; y < 64; y++) for (int x = 0; x < 64; x++) {
            int pixel = image.getRGB(x * image.getWidth() / 64, y * image.getHeight() / 64);
            if ((pixel >>> 24) < 24) continue;
            opaque++; int r = (pixel >>> 16) & 255, g = (pixel >>> 8) & 255, b = pixel & 255;
            lowR = Math.min(lowR, r); lowG = Math.min(lowG, g); lowB = Math.min(lowB, b);
            highR = Math.max(highR, r); highG = Math.max(highG, g); highB = Math.max(highB, b);
        }
        return opaque > 40 && Math.max(highR - lowR, Math.max(highG - lowG, highB - lowB)) >= 12;
    }
}
