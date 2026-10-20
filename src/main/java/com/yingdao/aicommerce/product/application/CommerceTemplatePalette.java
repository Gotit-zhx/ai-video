package com.yingdao.aicommerce.product.application;

import java.util.*;

/** Explicit template colors; captured photos are never used to infer these values. */
public final class CommerceTemplatePalette {
    public record Color(String name, String hex) {
        public Color {
            name = name == null ? "" : name.strip();
            hex = hex == null ? "" : hex.strip().toUpperCase(Locale.ROOT);
            if (hex.matches("#[0-9A-F]{3}"))
                hex = "#" + hex.charAt(1) + hex.charAt(1) + hex.charAt(2) + hex.charAt(2) + hex.charAt(3) + hex.charAt(3);
        }
    }
    private CommerceTemplatePalette() {}
    public static void validate(List<Color> colors) {
        if (colors == null) return; // Existing immutable templates predate explicit palettes.
        if (colors.isEmpty() || colors.size() > 20)
            throw new IllegalArgumentException("请配置1至20种裂变配色");
        var names = new HashSet<String>();
        var hexes = new HashSet<String>();
        for (var color : colors) {
            if (color == null || color.name().isBlank() || color.name().length() > 40
                    || !color.hex().matches("#[0-9A-F]{6}"))
                throw new IllegalArgumentException("请填写颜色名称和有效的 HEX 色号");
            if (!names.add(color.name().toLowerCase(Locale.ROOT)) || !hexes.add(color.hex()))
                throw new IllegalArgumentException("模板配色的名称或色号不能重复");
        }
    }
    public static List<CommerceSourceColorResolver.Color> resolve(List<Color> colors,
            ProductCaptureService.CaptureDetail detail) {
        validate(colors);
        if (colors == null) throw new IllegalArgumentException("模板尚未配置裂变配色");
        var keys = detail.skus().stream()
                .filter(s -> Objects.equals(s.getOrganizationId(), detail.capture().getOrganizationId())
                        && Objects.equals(s.getCaptureId(), detail.capture().getId()))
                .map(s -> s.getSkuKey()).toList();
        if (keys.isEmpty() || keys.stream().anyMatch(k -> k == null || k.isBlank())
                || new HashSet<>(keys).size() != keys.size())
            throw new IllegalArgumentException("来源商品缺少完整的尺码及售价规格，请先补齐 SKU");
        return colors.stream().map(c -> new CommerceSourceColorResolver.Color(
                "template-" + c.hex().substring(1).toLowerCase(Locale.ROOT),
                c.name(), keys, null, false, c.hex())).toList();
    }
    /** A literal color reference for image-model paths, not a fabricated SKU photo. */
    public static byte[] swatch(String hex) {
        if (hex == null || !hex.matches("#[0-9A-Fa-f]{6}")) throw new IllegalArgumentException("模板色号无效");
        var image = new java.awt.image.BufferedImage(256, 256, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try { graphics.setColor(java.awt.Color.decode(hex)); graphics.fillRect(0, 0, 256, 256); }
        finally { graphics.dispose(); }
        try (var output = new java.io.ByteArrayOutputStream()) {
            javax.imageio.ImageIO.write(image, "png", output);
            return output.toByteArray();
        } catch (java.io.IOException failure) { throw new IllegalStateException("配色色样生成失败", failure); }
    }
}
