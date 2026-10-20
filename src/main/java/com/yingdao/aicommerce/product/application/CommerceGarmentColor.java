package com.yingdao.aicommerce.product.application;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.Locale;
import javax.imageio.ImageIO;

/** Constrained photographic cloth recoloring; never invents a palette from a SKU name. */
public final class CommerceGarmentColor {
    public static final String VERSION = "photo-cloth-color-v4";
    public enum Mode { TEMPLATE_ORIGINAL, SOURCE_PHOTO_COLOR, TEMPLATE_HEX }
    public record Report(Mode mode, String sourceLabel, String observedSourceRgb,
                         String observedTemplateRgb, double dominantSampleShare,
                         long sourceEvidencePixels, long recoloredPixels, long protectedPixels,
                         boolean productionReady, String limitation) {}
    public record Result(byte[] png, Report report) {
        public Result { png = png.clone(); }
        @Override public byte[] png() { return png.clone(); }
    }
    public static final class ColorException extends IllegalArgumentException {
        private final String code;
        public ColorException(String code, String message) { super(message); this.code = code; }
        public String code() { return code; }
    }
    private record Lab(double l, double a, double b) {}
    private record Garment(BufferedImage image, boolean[] mask, int left, int top, int right, int bottom) {}
    private record Palette(int rgb, Lab lab, double share, int evidence) {}
    private CommerceGarmentColor() {}

    /** The two photos may have different sizes; source pixels supply color evidence only. */
    public static Result prepare(byte[] blankPhoto, byte[] sourceSkuImage, String label, boolean defaultOriginalColor) {
        var blank = decode(blankPhoto);
        String safeLabel = label == null ? "" : label.strip();
        if (safeLabel.length() > 200) throw bad("INVALID_COLOR_LABEL", "SKU 颜色名称过长，请检查颜色数据。");
        if (defaultOriginalColor)
            return new Result(png(blank), new Report(Mode.TEMPLATE_ORIGINAL, safeLabel, null, null,
                    0, 0, 0, (long) blank.getWidth() * blank.getHeight(), false,
                    "未设置独立 SKU 颜色，直接复用模板底色；未从颜色名称推断颜色，也未进行实物色号或成品质量验收。"));
        if (sourceSkuImage == null || sourceSkuImage.length == 0)
            throw bad("SKU_COLOR_IMAGE_REQUIRED", "该 SKU 颜色缺少可取色的商品图片；请补充此颜色的干净商品图，不能仅凭色名染色。");
        var source = garment(sourceSkuImage, "SKU 颜色「" + safeLabel + "」图", true);
        var targetColor = palette(source, false);
        verifyLabel(safeLabel, targetColor.rgb);
        return recolor(blankPhoto, safeLabel, targetColor, Mode.SOURCE_PHOTO_COLOR);
    }

    public static Result prepareHex(byte[] blankPhoto, String hex, String label) {
        if (hex == null || !hex.matches("#[0-9A-Fa-f]{6}"))
            throw bad("INVALID_TEMPLATE_COLOR", "模板配色色号无效，请重新保存模板");
        if (label == null || label.isBlank() || label.length() > 40)
            throw bad("INVALID_COLOR_LABEL", "模板颜色名称无效");
        int rgb = 0xff000000 | Integer.parseInt(hex.substring(1), 16);
        return recolor(blankPhoto, label.strip(), new Palette(rgb, lab(rgb), 1, 0), Mode.TEMPLATE_HEX);
    }

    private static Result recolor(byte[] blankPhoto, String safeLabel, Palette targetColor, Mode mode) {
        var blank = decode(blankPhoto);
        var template = garment(blankPhoto, "模板底图", false);
        var templateColor = palette(template, true);
        int width = blank.getWidth(), height = blank.getHeight(), n = width * height;
        int[] original = blank.getRGB(0, 0, width, height, null, 0, width);
        boolean[] candidates = new boolean[n];
        int foreground = 0;
        for (int i = 0; i < n; i++) if (template.mask[i]) {
            foreground++;
            Lab pixel=lab(original[i]);
            candidates[i] = pixel.l >= templateColor.lab.l * .38
                    && Math.hypot(pixel.a-templateColor.lab.a,pixel.b-templateColor.lab.b) <= .095;
        }
        // Keep the continuous fabric, not small same-colored islands inside a label
        // or decoration bounded by non-fabric ink. This preserves white label letters.
        boolean[] fabric = largestComponent(candidates, width, height);
        int clothPixels = 0;
        for (boolean value : fabric) if (value) clothPixels++;
        if (clothPixels < foreground * .70)
            throw bad("TEMPLATE_FABRIC_UNCERTAIN", "模板的纯色布料区域不连续或有过多杂色，无法可靠只改布色；请补充去印花后的纯色底图。");
        int[] output = original.clone();
        long changed = 0;
        for (int i = 0; i < n; i++) if (fabric[i]) {
            Lab before = lab(original[i]);
            double shade = before.l - templateColor.lab.l;
            double newL = clamp(targetColor.lab.l + shade, .025, .995);
            double chromaScale = Math.min(1.15, newL / Math.max(.08, targetColor.lab.l));
            Lab after = new Lab(newL, targetColor.lab.a * chromaScale, targetColor.lab.b * chromaScale);
            output[i] = (original[i] & 0xff000000) | (rgb(after) & 0xffffff);
            if (output[i] != original[i]) changed++;
        }
        // Reconstruct antialiased coverage only along the external garment outline.
        // Interior labels and seams are never blurred into the fabric.
        for (int y=1;y<height-1;y++) for(int x=1;x<width-1;x++) {
            int i=y*width+x;
            boolean edge=false;
            for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++)
                if(template.mask[(y+dy)*width+x+dx]!=template.mask[i])edge=true;
            if(!edge || (!fabric[i] && template.mask[i]))continue;
            int nearest=-1, distance=100;
            for(int dy=-3;dy<=3;dy++)for(int dx=-3;dx<=3;dx++) {
                int xx=x+dx,yy=y+dy;if(xx<1||yy<1||xx>=width-1||yy>=height-1)continue;
                int q=yy*width+xx;
                if(!fabric[q]||!template.mask[q-1]||!template.mask[q+1]||!template.mask[q-width]||!template.mask[q+width])continue;
                int d=dx*dx+dy*dy;if(d<distance){nearest=q;distance=d;}
            }
            if(nearest<0)continue;
            double numerator=0,denominator=0;
            for(int shift:new int[]{0,8,16}) {
                double c=255-((original[nearest]>>>shift)&255);
                numerator+=c*(255-((original[i]>>>shift)&255));denominator+=c*c;
            }
            double coverage=clamp(numerator/Math.max(1,denominator),0,1);
            if(coverage>=.985 && fabric[i])continue;
            int pixel=original[i]&0xff000000;
            for(int shift:new int[]{0,8,16})pixel|=(int)Math.round(255*(1-coverage)+((output[nearest]>>>shift)&255)*coverage)<<shift;
            output[i]=pixel;
        }
        verifyCoverage(original, output, fabric, width, height, templateColor.lab.l, targetColor.lab.l);
        var result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        result.setRGB(0, 0, width, height, output, 0, width);
        return new Result(png(result), new Report(mode, safeLabel, hex(targetColor.rgb),
                hex(templateColor.rgb), targetColor.share, targetColor.evidence, changed, n - clothPixels, false,
                mode == Mode.TEMPLATE_HEX
                        ? "按模板指定 HEX 配色，仅改变布料底色，保留光影、背景和印花；屏幕色号不代表实物染色验收。"
                        : "仅使用 SKU 照片中可分离、占主导且分布一致的布色，保留模板光影、背景和识别到的非布色细节。取样 RGB 受拍摄光照影响，不等同真实标准色号；布料边缘、材质与颜色仍需检查。"));
    }

    /** Fail before archiving when a darker SKU still contains a patch of the original light cloth. */
    static void verifyCoverage(int[] original, int[] output, boolean[] fabric, int width, int height,
                               double originalLightness, double targetLightness) {
        if (originalLightness-targetLightness < .12) return;
        int examined=0, leaked=0;
        for(int y=1;y<height-1;y++)for(int x=1;x<width-1;x++) {
            int i=y*width+x;
            if(!fabric[i]||!fabric[i-1]||!fabric[i+1]||!fabric[i-width]||!fabric[i+width])continue;
            examined++;
            double before=lab(original[i]).l, after=lab(output[i]).l;
            double expected=clamp(targetLightness+before-originalLightness,.025,.995);
            if(before>.75 && after>expected+.14 && Math.abs(after-before)<.035)leaked++;
        }
        if(leaked>Math.max(12,examined*.001))
            throw bad("COLOR_COVERAGE_INCOMPLETE","换色检查发现布料漏染白块，已停止套版与上传；请修复底图遮罩，已生成印花保留且不会重生成。");
    }

    private static Garment garment(byte[] bytes, String description, boolean colorEvidence) {
        var image = decode(bytes);
        cleanBorder(image, description);
        BufferedImage maskImage;
        try { maskImage = CommerceMockupComposer.decode(colorEvidence
                ? CommercePhotoMockupComposer.colorEvidenceMask(bytes)
                : CommercePhotoMockupComposer.recolorMask(bytes)); }
        catch (IllegalArgumentException e) {
            throw bad("COLOR_FOREGROUND_UNCERTAIN", description + "在保留浅色高光后仍缺少可靠布料轮廓；请补充背景干净、衣服完整的该色商品图。");
        }
        int w = image.getWidth(), h = image.getHeight();
        var mask = new boolean[w * h];
        int left = w, right = -1, top = h, bottom = -1;
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) if ((maskImage.getRGB(x, y) >>> 24) > 240) {
            mask[y * w + x] = true;
            left = Math.min(left, x); right = Math.max(right, x); top = Math.min(top, y); bottom = Math.max(bottom, y);
        }
        if (right < 0) throw bad("COLOR_FOREGROUND_UNCERTAIN", description + "没有可识别的服装主体，请补充可靠图片。");
        return new Garment(image, mask, left, top, right, bottom);
    }

    private static void cleanBorder(BufferedImage image, String description) {
        int w = image.getWidth(), h = image.getHeight(), total = 0, opaque = 0, white = 0;
        int[][] channels = new int[3][256];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            if (x != 0 && x != w - 1 && y != 0 && y != h - 1) continue;
            int p = image.getRGB(x, y); total++;
            if ((p >>> 24) < 16) continue;
            opaque++;
            int r = p >> 16 & 255, g = p >> 8 & 255, b = p & 255;
            if (Math.min(r, Math.min(g, b)) >= 235 && Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) <= 10) white++;
            int[] values = {r, g, b};
            for (int c = 0; c < 3; c++) channels[c][values[c]]++;
        }
        if (opaque < total * .03) return;
        boolean gradient = false;
        // A few JPEG/collage edge specks are not a background gradient. Keep the
        // independent 98% white gate and all downstream full-silhouette checks.
        int lowRank = (int) Math.floor(opaque * .01), highRank = (int) Math.ceil(opaque * .99) - 1;
        for (int c = 0; c < 3; c++) {
            int cumulative = 0, low = -1, high = 255;
            for (int value = 0; value < 256; value++) {
                cumulative += channels[c][value];
                if (low < 0 && cumulative > lowRank) low = value;
                if (cumulative > highRank) { high = value; break; }
            }
            if (high - low > 18) gradient = true;
        }
        if (white < opaque * .98 || gradient)
            throw bad("COLOR_BACKGROUND_COMPLEX", description + "须为背景均匀且轮廓完整的白底或透明商品图；当前背景或边缘不可靠，已停止自动取色。");
    }

    private static Palette palette(Garment garment, boolean template) {
        int w = garment.image.getWidth(), h = garment.image.getHeight();
        int step = Math.max(1, (int) Math.ceil(Math.sqrt(w * (double) h / 60_000)));
        int capacity = ((w + step - 1) / step) * ((h + step - 1) / step);
        int[] samples = new int[capacity], xs = new int[capacity], ys = new int[capacity];
        int count = 0;
        for (int y = 1; y < h - 1; y += step) for (int x = 1; x < w - 1; x += step) {
            int i = y * w + x;
            if (!garment.mask[i] || !garment.mask[i - 1] || !garment.mask[i + 1] || !garment.mask[i - w] || !garment.mask[i + w]) continue;
            int p = garment.image.getRGB(x, y);
            if ((p >>> 24) < 240) continue;
            samples[count] = p; xs[count] = x; ys[count++] = y;
        }
        if (count < 50) throw bad("COLOR_EVIDENCE_TOO_SMALL", "可识别的衣服布料太少，无法可靠取色；请补充清晰的该色商品图。");
        int median = 0xff000000;
        for (int shift : new int[] {0, 8, 16}) {
            int[] values = new int[count];
            for (int i = 0; i < count; i++) values[i] = (samples[i] >>> shift) & 255;
            Arrays.sort(values); median |= values[count / 2] << shift;
        }
        Lab center = lab(median);
        int accepted = 0, rimCount = 0, rimAccepted = 0;
        int rimWidth = Math.max(3, Math.min(12, (int) Math.round(Math.min(garment.right - garment.left, garment.bottom - garment.top) * .07)));
        int[] zoneCount = new int[4], zoneAccepted = new int[4];
        for (int i = 0; i < count; i++) {
            boolean same = cloth(lab(samples[i]), center, template);
            if (same) accepted++;
            // A large central graphic can win a global median and all broad zones.
            // Require evidence in the cloth just inside the outline as well. Skip
            // the first two pixels, which often contain antialiasing or a seam.
            if (insideCross(garment, xs[i], ys[i], 2) && !insideCross(garment, xs[i], ys[i], rimWidth)) {
                rimCount++;
                if (same) rimAccepted++;
            }
            double x = (xs[i] - garment.left) / (double) Math.max(1, garment.right - garment.left);
            double y = (ys[i] - garment.top) / (double) Math.max(1, garment.bottom - garment.top);
            boolean[] zones = {x < .30, x > .70, y < .30, y > .70};
            for (int z = 0; z < 4; z++) if (zones[z]) { zoneCount[z]++; if (same) zoneAccepted[z]++; }
        }
        int consistentZones = 0, measurableZones = 0;
        for (int z = 0; z < 4; z++) if (zoneCount[z] >= 12) {
            measurableZones++;
            if (zoneAccepted[z] >= zoneCount[z] * .58) consistentZones++;
        }
        double share = accepted / (double) count;
        if (share < (template ? .75 : .68) || measurableZones < 3 || consistentZones != measurableZones
                || rimCount >= 30 && rimAccepted < rimCount * .58)
            throw bad(template ? "TEMPLATE_FABRIC_UNCERTAIN" : "SKU_FABRIC_COLOR_UNCERTAIN",
                    template ? "模板存在过多印花、杂色或阴影，无法可靠确定纯色布料层；请使用干净纯色底图。"
                            : "SKU 图中布色不占主导或各部位颜色不一致，可能是大面积印花、多色衣服或复杂背景；请补充能明确显示原色的商品图。");
        return new Palette(median, center, share, accepted);
    }

    private static boolean insideCross(Garment garment, int x, int y, int radius) {
        int w = garment.image.getWidth(), h = garment.image.getHeight();
        return x >= radius && y >= radius && x + radius < w && y + radius < h
                && garment.mask[y * w + x - radius] && garment.mask[y * w + x + radius]
                && garment.mask[(y - radius) * w + x] && garment.mask[(y + radius) * w + x];
    }

    private static boolean cloth(Lab pixel, Lab reference, boolean template) {
        double chroma = Math.hypot(pixel.a - reference.a, pixel.b - reference.b);
        return Math.abs(pixel.l - reference.l) <= .20 && chroma <= (template ? .036 : .047);
    }

    private static boolean[] largestComponent(boolean[] candidates, int width, int height) {
        var seen = new boolean[candidates.length]; var result = new boolean[candidates.length];
        int[] queue = new int[candidates.length], largest = new int[0];
        for (int i = 0; i < candidates.length; i++) if (candidates[i] && !seen[i]) {
            int head = 0, tail = 1; queue[0] = i; seen[i] = true;
            while (head < tail) {
                int q = queue[head++], x = q % width, y = q / width;
                if (x > 0) tail = next(q - 1, candidates, seen, queue, tail);
                if (x + 1 < width) tail = next(q + 1, candidates, seen, queue, tail);
                if (y > 0) tail = next(q - width, candidates, seen, queue, tail);
                if (y + 1 < height) tail = next(q + width, candidates, seen, queue, tail);
            }
            if (tail > largest.length) largest = Arrays.copyOf(queue, tail);
        }
        for (int i : largest) result[i] = true;
        return result;
    }
    private static int next(int i, boolean[] candidates, boolean[] seen, int[] queue, int tail) {
        if (candidates[i] && !seen[i]) { seen[i] = true; queue[tail++] = i; }
        return tail;
    }

    private static void verifyLabel(String label, int observed) {
        String value = Normalizer.normalize(label, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).strip();
        if (value.isBlank()) return;
        float[] hsv = Color.RGBtoHSB(observed >>> 16 & 255, observed >>> 8 & 255, observed & 255, null);
        double h = hsv[0] * 360, s = hsv[1], v = hsv[2] * 255;
        String family = null;
        // Compound descriptions are checked before individual words (e.g. off-white, light blue).
        if (matches(value, "米白", "米色", "奶油", "奶白", "杏色", "beige", "cream", "off-white", "ivory", "ecru")) family = "cream";
        else if (matches(value, "粉", "pink", "merah muda")) family = "pink";
        else if (matches(value, "黑", "black", "hitam")) family = "black";
        else if (matches(value, "白", "white", "putih")) family = "white";
        else if (matches(value, "灰", "gray", "grey", "abu")) family = "gray";
        else if (matches(value, "棕", "褐", "咖啡", "brown", "coklat")) family = "brown";
        else if (matches(value, "蓝", "藍", "blue", "navy", "biru")) family = "blue";
        else if (matches(value, "绿", "綠", "green", "hijau")) family = "green";
        else if (matches(value, "紫", "purple", "violet", "ungu")) family = "purple";
        else if (matches(value, "橙", "橘", "orange", "oranye")) family = "orange";
        else if (matches(value, "黄", "黃", "yellow", "kuning")) family = "yellow";
        else if (matches(value, "红", "紅", "red", "merah")) family = "red";
        if (family == null) return;
        boolean agrees = switch (family) {
            case "black" -> v <= 105;
            // Photo whites may be shaded; still retain measured RGB, never paint label-white.
            case "white" -> v >= 200 && s <= .18;
            case "gray" -> v >= 60 && v <= 235 && s <= .18;
            case "cream" -> v >= 155 && s <= .40 && (s < .06 || h >= 25 && h <= 75);
            case "pink" -> v >= 140 && s >= .07 && s <= .75 && (h >= 320 || h <= 20);
            case "brown" -> v <= 205 && s >= .12 && h >= 8 && h <= 65;
            case "blue" -> s >= .12 && h >= 175 && h <= 270;
            case "green" -> s >= .10 && h >= 65 && h <= 185;
            case "purple" -> s >= .10 && h >= 255 && h <= 330;
            case "orange" -> s >= .20 && h >= 15 && h <= 55;
            case "yellow" -> s >= .12 && h >= 35 && h <= 80;
            case "red" -> s >= .25 && (h <= 25 || h >= 330);
            default -> true;
        };
        if (!agrees) throw bad("SKU_COLOR_LABEL_MISMATCH", "SKU 颜色“" + label + "”与商品图中识别到的主要布色不一致，已停止调色；请确认此图片确实属于该 SKU 颜色。");
    }
    private static boolean matches(String value, String... words) {
        for (String word : words) {
            if (word.codePoints().anyMatch(c -> c > 127)) { if (value.contains(word)) return true; }
            else if (value.matches("(?s).*\\b" + java.util.regex.Pattern.quote(word) + "\\b.*")) return true;
        }
        return false;
    }

    private static Lab lab(int rgb) {
        double r = linear((rgb >>> 16 & 255) / 255d), g = linear((rgb >>> 8 & 255) / 255d), b = linear((rgb & 255) / 255d);
        double l = Math.cbrt(.4122214708 * r + .5363325363 * g + .0514459929 * b);
        double m = Math.cbrt(.2119034982 * r + .6806995451 * g + .1073969566 * b);
        double s = Math.cbrt(.0883024619 * r + .2817188376 * g + .6299787005 * b);
        return new Lab(.2104542553 * l + .7936177850 * m - .0040720468 * s,
                1.9779984951 * l - 2.4285922050 * m + .4505937099 * s,
                .0259040371 * l + .7827717662 * m - .8086757660 * s);
    }
    private static int rgb(Lab lab) {
        double l = lab.l + .3963377774 * lab.a + .2158037573 * lab.b;
        double m = lab.l - .1055613458 * lab.a - .0638541728 * lab.b;
        double s = lab.l - .0894841775 * lab.a - 1.2914855480 * lab.b;
        l *= l * l; m *= m * m; s *= s * s;
        int r = channel(4.0767416621 * l - 3.3077115913 * m + .2309699292 * s);
        int g = channel(-1.2684380046 * l + 2.6097574011 * m - .3413193965 * s);
        int b = channel(-.0041960863 * l - .7034186147 * m + 1.7076147010 * s);
        return 0xff000000 | r << 16 | g << 8 | b;
    }
    private static double linear(double v) { return v <= .04045 ? v / 12.92 : Math.pow((v + .055) / 1.055, 2.4); }
    private static int channel(double v) { return (int) Math.round(clamp(v <= .0031308 ? 12.92 * v : 1.055 * Math.pow(v, 1 / 2.4) - .055, 0, 1) * 255); }
    private static double clamp(double v, double low, double high) { return Math.max(low, Math.min(high, v)); }
    private static String hex(int rgb) { return String.format(Locale.ROOT, "#%06X", rgb & 0xffffff); }
    private static BufferedImage decode(byte[] bytes) {
        try {
            var image = CommerceMockupComposer.decode(bytes);
            if (image.getWidth() > 4096 || image.getHeight() > 4096 || (long) image.getWidth() * image.getHeight() > 12_000_000)
                throw bad("COLOR_IMAGE_SIZE", "调色参考图单边不得超过 4096 像素、总像素不得超过 1200 万。");
            return image;
        } catch (ColorException e) { throw e; }
        catch (IllegalArgumentException e) { throw bad("COLOR_IMAGE_INVALID", "调色参考图无效，请提供不超过 8 MB 的可解码图片。"); }
    }
    private static byte[] png(BufferedImage image) {
        try (var bytes = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "png", bytes)) throw new IOException("PNG writer missing");
            return bytes.toByteArray();
        } catch (IOException e) { throw new IllegalStateException("布料颜色预览编码失败", e); }
    }
    private static ColorException bad(String code, String message) { return new ColorException(code, message); }
}
