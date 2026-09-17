package com.yingdao.aicommerce.product.application;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.*;
import javax.imageio.ImageIO;

/** Replaces artwork through the renderer's visible UV map, without moving the saved garment or print. */
public final class CommerceSurfaceMockupComposer {
    public record Projection(Set<String> visiblePrintIds, long coveredPixels, int width, int height) {}
    public record Report(String status, long outsideChangedPixels, long outsideComparedPixels,
                         double outsideChangedRatio, boolean exactGuideAvailable, boolean productionReady,
                         String limitation) {}
    private CommerceSurfaceMockupComposer() {}

    public static Projection inspect(byte[] uvBytes, List<String> printIds) {
        return inspect(CommerceMockupComposer.decode(uvBytes), printIds);
    }

    private static Projection inspect(BufferedImage uv, List<String> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > 32 || new HashSet<>(ids).size() != ids.size()
                || ids.stream().anyMatch(id -> id == null || id.isBlank()))
            throw new IllegalArgumentException("三维印花索引不完整，请重新保存模型位置图");
        var visible = new LinkedHashSet<String>();
        long covered = 0;
        for (int y = 0; y < uv.getHeight(); y++) for (int x = 0; x < uv.getWidth(); x++) {
            int pixel = uv.getRGB(x, y), index = pixel & 255;
            if ((pixel >>> 24) == 0) continue;
            if (index < 1 || index > ids.size())
                throw new IllegalArgumentException("三维印花UV索引超出已保存槽位，请重新生成位置图");
            visible.add(ids.get(index - 1)); covered++;
        }
        return new Projection(Collections.unmodifiableSet(visible), covered, uv.getWidth(), uv.getHeight());
    }

    public static byte[] compose(byte[] blankBytes, byte[] uvBytes, List<String> printIds,
                                 Map<String, byte[]> replacements) {
        var blank = CommerceMockupComposer.decode(blankBytes);
        var uv = CommerceMockupComposer.decode(uvBytes);
        var projection = inspect(uv, printIds);
        if (blank.getWidth() != uv.getWidth() || blank.getHeight() != uv.getHeight())
            throw new IllegalArgumentException("无印花模型与UV位置图尺寸不一致，请重新保存");
        if (replacements == null || !replacements.keySet().containsAll(projection.visiblePrintIds())
                || !new HashSet<>(printIds).containsAll(replacements.keySet()))
            throw new IllegalArgumentException("当前可见的三维印花位置缺少替换素材");
        var textures = new HashMap<String, BufferedImage>();
        replacements.forEach((id, bytes) -> textures.put(id, CommerceMockupComposer.decode(bytes)));
        var output = new BufferedImage(blank.getWidth(), blank.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < uv.getHeight(); y++) for (int x = 0; x < uv.getWidth(); x++) {
            int map = uv.getRGB(x, y), base = blank.getRGB(x, y), coverage = map >>> 24;
            if (coverage == 0) { output.setRGB(x, y, base); continue; }
            var texture = textures.get(printIds.get((map & 255) - 1));
            // WebGL texture v points up; decoded image rows point down. UV PNG is byte data, not color.
            int tx = Math.round(((map >>> 16) & 255) / 255f * (texture.getWidth() - 1));
            int ty = Math.round((1 - ((map >>> 8) & 255) / 255f) * (texture.getHeight() - 1));
            int ink = texture.getRGB(tx, ty);
            double alpha = coverage / 255d * (ink >>> 24) / 255d;
            int rgb = base & 0xff000000;
            for (int shift : new int[]{0, 8, 16})
                rgb |= (int)Math.round(((base >>> shift) & 255) * (1 - alpha)
                        + ((ink >>> shift) & 255) * alpha) << shift;
            output.setRGB(x, y, rgb);
        }
        try (var bytes = new ByteArrayOutputStream()) {
            ImageIO.write(output, "png", bytes); return bytes.toByteArray();
        } catch (java.io.IOException e) { throw new IllegalStateException("三维印花引导图归档失败", e); }
    }

    /** A diagnostic only: the generated photo is never clipped against a differently aligned source photograph. */
    public static Report check(byte[] blankBytes, byte[] uvBytes, byte[] candidateBytes, List<String> printIds) {
        var uv = CommerceMockupComposer.decode(uvBytes); inspect(uv, printIds);
        var base = CommerceMockupComposer.square(blankBytes);
        var candidate = CommerceMockupComposer.square(candidateBytes);
        long changed = 0, compared = 0;
        for (int y = 0; y < 1024; y++) for (int x = 0; x < 1024; x++) {
            int mx = Math.min(uv.getWidth() - 1, x * uv.getWidth() / 1024);
            int my = Math.min(uv.getHeight() - 1, y * uv.getHeight() / 1024);
            if ((uv.getRGB(mx, my) >>> 24) > 0) continue;
            compared++;
            int a = base.getRGB(x, y), b = candidate.getRGB(x, y), delta = 0;
            for (int shift : new int[]{0, 8, 16})
                delta = Math.max(delta, Math.abs(((a >>> shift) & 255) - ((b >>> shift) & 255)));
            if (delta >= 40) changed++;
        }
        return new Report("SURFACE_REVIEW_REQUIRED", changed, compared, compared == 0 ? 0 : changed / (double)compared,
                true, false, "精确印花引导图沿已冻结UV替换素材；AI成品仍可能改变形状、位置或光影。此项只记录模型印区外像素变化，不代表位置合格或批准上架；未用未对齐实物图裁切成品。");
    }
}
