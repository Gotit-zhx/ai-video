package com.yingdao.aicommerce.product.application;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;
import javax.imageio.ImageIO;

/**
 * Deterministic photo mockups. The photograph supplies geometry and lighting; a render supplies
 * only artwork coordinates. Silhouette registration is a guarded preview, not seam calibration.
 */
public final class CommercePhotoMockupComposer {
    public static final String VERSION = "photo-continuous-registration-v5";
    public record Report(String status, String method, int width, int height,
                         Set<String> visiblePrintIds, long mappedPixels, long changedPixels,
                         long protectedChangedPixels, double silhouetteProfileError,
                         double maximumRelativeStretch, double rejectedMappingRatio,
                         boolean productionReady, String limitation) {}
    public record Result(byte[] png, Report report) {}
    private record Span(int left, int right) { int width() { return right - left + 1; } }
    private record Mask(int width, int height, boolean[] pixels, int left, int top, int right, int bottom,
                        List<List<Span>> rows, double medianLuma) {
        int boxWidth() { return right - left + 1; }
        int boxHeight() { return bottom - top + 1; }
        boolean contains(int x, int y) { return x >= 0 && y >= 0 && x < width && y < height && pixels[y * width + x]; }
    }
    private record Ink(int index, double u, double v, double alpha) {}
    private record Field(double[] scale, double[] offset, int[] unsupportedRows) {}
    private CommercePhotoMockupComposer() {}

    /** Primary view convenience overload. BACK callers must supply the independently saved back photo and UV. */
    public static Result compose(byte[] photo, byte[] modelBlank, byte[] uv,
                                 List<String> printIds, Map<String, byte[]> replacements) {
        return compose("FRONT", photo, modelBlank, uv, printIds, replacements);
    }

    public static Result compose(String side, byte[] photoBytes, byte[] modelBytes, byte[] uvBytes,
                                 List<String> printIds, Map<String, byte[]> replacements) {
        return composeRecolored(side, photoBytes, photoBytes, modelBytes, uvBytes, printIds, replacements);
    }

    /** Only for color derivatives of this exact fixed photo; color changes do not change geometry. */
    static Result composeRecolored(String side, byte[] photoBytes, byte[] geometryBytes, byte[] modelBytes,
                                   byte[] uvBytes, List<String> printIds, Map<String, byte[]> replacements) {
        if (!Set.of("FRONT","BACK").contains(side)) throw bad("照片套版只支持独立保存的正面或背面；侧面与折叠位置需要单独校准");
        var photo = decode(photoBytes); var model = decode(modelBytes); var uv = decode(uvBytes);
        if (model.getWidth() != uv.getWidth() || model.getHeight() != uv.getHeight())
            throw bad("模型底图与 UV 位置图尺寸不一致，请重新保存模板位置");
        var projection = CommerceSurfaceMockupComposer.inspect(uvBytes, printIds);
        if (projection.coveredPixels() == 0) throw bad("当前视角没有可见印花，请选择有印花的主视图");
        var textures = textures(replacements, projection.visiblePrintIds(), new HashSet<>(printIds));
        var geometry = decode(geometryBytes);
        if (geometry.getWidth() != photo.getWidth() || geometry.getHeight() != photo.getHeight())
            throw bad("颜色底图与固定轮廓尺寸不一致，不能复用位置映射");
        var silhouette = segment(geometry);
        int[] light = new int[(int)java.util.stream.IntStream.range(0, silhouette.pixels.length).filter(i -> silhouette.pixels[i]).count()];
        int at = 0;
        for (int y=0; y<photo.getHeight(); y++) for (int x=0; x<photo.getWidth(); x++)
            if (silhouette.contains(x,y)) light[at++] = (int)Math.round(luma(photo.getRGB(x,y)));
        Arrays.sort(light);
        var pm = new Mask(silhouette.width, silhouette.height, silhouette.pixels, silhouette.left, silhouette.top,
                silhouette.right, silhouette.bottom, silhouette.rows, light[light.length/2]);
        var mm = segment(model);
        double profileError = profileError(pm, mm);
        double aspectRatio = (pm.boxWidth() / (double) pm.boxHeight()) / (mm.boxWidth() / (double) mm.boxHeight());
        if (profileError > .13 || aspectRatio < .70 || aspectRatio > 1.40)
            throw bad("实物底图与模型轮廓不一致，不能沿用该位置映射；请确认底图与模型属于同款、同一视角并重新校准");
        long invalidUv = 0;
        var expectedInkIds = new LinkedHashSet<String>();
        var slotCoverage = new HashMap<String, Double>();
        var expectedMass = new HashMap<String, Double>();
        var stableSlots = new HashSet<String>();
        for (int y = 0; y < uv.getHeight(); y++) for (int x = 0; x < uv.getWidth(); x++) {
            int pixel = uv.getRGB(x, y);
            if ((pixel >>> 24) == 0) continue;
            if (!mm.contains(x, y)) invalidUv++;
            String id = printIds.get((pixel & 255) - 1);
            double coverage = (pixel >>> 24) / 255d;
            slotCoverage.merge(id, coverage, Double::sum);
            double alpha = coverage * (sample(textures.get(id), ((pixel >>> 16) & 255) / 255d,
                    1 - ((pixel >>> 8) & 255) / 255d) >>> 24) / 255d;
            if (alpha > .001) expectedMass.merge(id, alpha, Double::sum);
            if (stableUvSlot(uv, x, y, pixel & 255)) stableSlots.add(id);
        }
        double largestSlot = slotCoverage.values().stream().mapToDouble(Double::doubleValue).max().orElse(0);
        expectedMass.forEach((id, mass) -> {
            if (requiresVisibleInk(mass, slotCoverage.get(id), stableSlots.contains(id), largestSlot)) expectedInkIds.add(id);
        });
        if (invalidUv > projection.coveredPixels() * .01)
            throw bad("部分印花落在模型衣服轮廓之外，已停止照片套版，请重新保存印花位置");
        var field = continuousField(pm, mm);
        var output = copy(photo);
        var mappedIds = new LinkedHashSet<String>();
        var inkIds = new LinkedHashSet<String>();
        long mapped = 0, changed = 0, rejected = 0, eligible = 0;
        double maximumStretch = 1;
        for (int y = pm.top; y <= pm.bottom; y++) {
            double my = mm.top + (y - pm.top) * (mm.boxHeight() - 1d) / (pm.boxHeight() - 1d);
            var photoSpans = pm.rows.get(y);
            if (photoSpans.isEmpty()) continue;
            for (int x = pm.left; x <= pm.right; x++) {
                if (!pm.contains(x, y)) continue;
                int run = spanIndex(photoSpans, x);
                if (run < 0) continue;
                var ps = photoSpans.get(run);
                // One continuous field crosses the underarm. Per-row topology cannot reset U.
                double mx = mm.left + ((x - pm.left) / (double) (pm.boxWidth() - 1)
                        * field.scale[y] + field.offset[y]) * (mm.boxWidth() - 1);
                var ink = uvAt(uv, mx, my);
                if (ink.alpha <= .001) continue;
                eligible++;
                double stretch = field.scale[y];
                double relativeStretch = Math.max(stretch, 1 / stretch);
                maximumStretch = Math.max(maximumStretch, relativeStretch);
                if (relativeStretch > 1.5 || edgeDistance(ps, x) < 1
                        || (field.unsupportedRows[y] > Math.max(5, pm.boxHeight() * .04)
                        && !supportedInterior(pm, mm, field, x, y, mx, my))) { rejected++; continue; }
                String id = printIds.get(ink.index - 1);
                mappedIds.add(id); mapped++;
                int texture = sample(textures.get(id), ink.u, 1 - ink.v);
                double alpha = ink.alpha * (texture >>> 24) / 255d;
                if (alpha <= .001) continue;
                inkIds.add(id);
                int before = photo.getRGB(x, y);
                int after = blend(before, texture, alpha, pm.medianLuma);
                output.setRGB(x, y, after);
                if (before != after) changed++;
            }
        }
        double rejectedRatio = rejected / (double) Math.max(1, eligible);
        if (rejectedRatio > .015 || !mappedIds.containsAll(projection.visiblePrintIds()))
            throw bad("印花位置跨越未对齐的袖口、领口或折叠区域，照片映射不可靠；请先校准位置，已保留原底图");
        // Disconnected row parts must not silently discard an entire or substantial partial print.
        double mappingCoverage = mapped / (double) projection.coveredPixels()
                / ((pm.boxHeight() / (double) mm.boxHeight()) * (pm.boxWidth() / (double) mm.boxWidth()));
        if (mappingCoverage < .78 || mappingCoverage > 1.30)
            throw bad("印花可见范围在照片中无法完整对齐，已停止合成，避免印花缺失或变形");
        if (inkIds.isEmpty() || !inkIds.containsAll(expectedInkIds))
            throw bad("应当可见的印花母版透明或缩放后丢失，请检查素材与位置");
        return new Result(png(output), new Report("PHOTO_COMPOSITED_REVIEW_REQUIRED", VERSION,
                photo.getWidth(), photo.getHeight(), Collections.unmodifiableSet(mappedIds), mapped, changed, 0,
                profileError, maximumStretch, rejectedRatio, false,
                "底图背景与非印花像素保持不变，印花沿连续轮廓映射并保留布料明暗；轮廓诊断不是几何准确率，内部缝线、遮挡和实物位置仍需视觉校准。"));
    }

    public static final String PHOTO_VERSION = "photo-direct-uv-v1";

    /** Four-photo layouts use their original photograph as the saved blank; no mesh registration. */
    static Result composePhoto(String side, byte[] photoBytes, byte[] originalBytes, byte[] blankBytes,
                               byte[] uvBytes, List<String> printIds, Map<String, byte[]> replacements) {
        if (!Set.of("FRONT", "BACK", "LEFT", "RIGHT").contains(side)) throw bad("四面照片的视角无效");
        var photo = decode(photoBytes); var original = decode(originalBytes); var blank = decode(blankBytes); var uv = decode(uvBytes);
        int w = photo.getWidth(), h = photo.getHeight();
        if (original.getWidth()!=w || original.getHeight()!=h || blank.getWidth()!=w || blank.getHeight()!=h
                || uv.getWidth()!=w || uv.getHeight()!=h) throw bad("四面底图与保存的位置图尺寸不一致，请重新保存模板");
        var projection = CommerceSurfaceMockupComposer.inspect(uvBytes, printIds);
        var textures = textures(replacements, projection.visiblePrintIds(), new HashSet<>(printIds));
        var light = new ArrayList<Integer>();
        for(int y=0;y<h;y++)for(int x=0;x<w;x++) {
            if(original.getRGB(x,y)!=blank.getRGB(x,y)) throw bad("照片印区必须绑定同一张原始底图，不能套用模型位置图");
            if((uv.getRGB(x,y)>>>24)>0) light.add((int)Math.round(luma(photo.getRGB(x,y))));
        }
        light.sort(Integer::compareTo);
        double median=light.isEmpty()?128:light.get(light.size()/2);
        var output=copy(photo);var inkIds=new LinkedHashSet<String>();long changed=0;
        for(int y=0;y<h;y++)for(int x=0;x<w;x++) {
            int map=uv.getRGB(x,y);if((map>>>24)==0)continue;
            String id=printIds.get((map&255)-1);
            int ink=sample(textures.get(id),((map>>>16)&255)/255d,1-((map>>>8)&255)/255d);
            double alpha=(map>>>24)/255d*(ink>>>24)/255d;if(alpha<=.001)continue;
            inkIds.add(id);int before=photo.getRGB(x,y),after=blend(before,ink,alpha,median);
            output.setRGB(x,y,after);if(before!=after)changed++;
        }
        if(!inkIds.containsAll(projection.visiblePrintIds())) throw bad("已设置的照片印花透明或缩放后不可见，请检查素材与大小");
        return new Result(png(output),new Report("PHOTO_COMPOSITED_REVIEW_REQUIRED",PHOTO_VERSION,w,h,
                projection.visiblePrintIds(),projection.coveredPixels(),changed,0,0,1,0,false,
                "按原始四面照片的固定像素位置套用印花，非印区像素保持不变；未进行三维轮廓拟合或新增生图调用。"));
    }

    static Report inspectPhotoMapping(String side, byte[] photo, byte[] blank, byte[] uv, List<String> printIds) {
        var diagnostic=new BufferedImage(2,2,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<2;y++)for(int x=0;x<2;x++)diagnostic.setRGB(x,y,0xffbc3456);
        var replacements=new LinkedHashMap<String,byte[]>();byte[] ink=png(diagnostic);
        for(String id:printIds)replacements.put(id,ink);
        return composePhoto(side,photo,photo,blank,uv,printIds,replacements).report();
    }

    /** Run before any paid generation. Opaque diagnostics validate every saved visible slot. */
    public static Report inspectMapping(byte[] photo, byte[] modelBlank, byte[] uv, List<String> printIds) {
        var diagnostic = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        diagnostic.setRGB(0, 0, 0xffbc3456); diagnostic.setRGB(1, 0, 0xffbc3456);
        diagnostic.setRGB(0, 1, 0xffbc3456); diagnostic.setRGB(1, 1, 0xffbc3456);
        byte[] bytes = png(diagnostic);
        var replacements = new LinkedHashMap<String, byte[]>();
        if (printIds != null) for (String id : printIds) replacements.put(id, bytes);
        return compose(photo, modelBlank, uv, printIds, replacements).report();
    }

    /** Explicit Area coordinates use the existing 1024 square contain canvas; no model/photo warp is inferred. */
    public static Result composePlanar(byte[] photoBytes, List<CommercePrintTemplateService.Area> areas,
                                       Map<String, byte[]> replacements) {
        if (areas == null || areas.isEmpty() || areas.size() > 8) throw bad("请选择 1 至 8 个已校准的照片印区");
        var ids = new LinkedHashSet<String>();
        for (var a : areas) {
            if (a == null || a.id() == null || a.id().isBlank() || !ids.add(a.id()) || !"FRONT".equals(a.side())
                    || !finite(a.x(), a.y(), a.width(), a.height()) || a.x() < 0 || a.y() < 0
                    || a.width() <= 0 || a.height() <= 0 || a.x() + a.width() > 1 || a.y() + a.height() > 1)
                throw bad("照片印区坐标无效或未校准到正面，请重新保存位置");
        }
        var textures = textures(replacements, ids, ids);
        var base = CommerceMockupComposer.square(photoBytes); var mask = segment(base); var output = copy(base);
        long mapped = 0, changed = 0, outside = 0, total = 0;
        var visible = new LinkedHashSet<String>();
        for (var a : areas) {
            var texture = textures.get(a.id());
            double scale = Math.min(a.width() * 1024 / texture.getWidth(), a.height() * 1024 / texture.getHeight());
            double width = texture.getWidth() * scale, height = texture.getHeight() * scale;
            double left = a.x() * 1024 + (a.width() * 1024 - width) / 2;
            double top = a.y() * 1024 + (a.height() * 1024 - height) / 2;
            for (int y = Math.max(0, (int) Math.ceil(top)); y < Math.min(1024, top + height); y++)
                for (int x = Math.max(0, (int) Math.ceil(left)); x < Math.min(1024, left + width); x++) {
                    int ink = sample(texture, (x - left) / Math.max(1, width - 1), (y - top) / Math.max(1, height - 1));
                    double alpha = (ink >>> 24) / 255d;
                    if (alpha <= .001) continue;
                    total++;
                    if (!mask.contains(x, y)) { outside++; continue; }
                    visible.add(a.id()); mapped++;
                    int before = output.getRGB(x, y);
                    int after = blend(before, ink, alpha, mask.medianLuma,
                            luma(base.getRGB(x, y)));
                    output.setRGB(x, y, after);
                    if (before != after) changed++;
                }
        }
        double rejected = outside / (double) Math.max(1, total);
        if (rejected > .01 || !visible.containsAll(ids))
            throw bad("照片印区超出衣服可见范围，或印花为空；请调整印区，未覆盖背景");
        return new Result(png(output), new Report("PHOTO_COMPOSITED_REVIEW_REQUIRED", "photo-explicit-area-v1",
                1024, 1024, Collections.unmodifiableSet(visible), mapped, changed, 0, 0, 1, rejected, false,
                "按已保存的照片坐标合成，保留底图布料明暗及印区外像素；坐标须对应本张正面照片，缝线和遮挡需要视觉检查。"));
    }

    /** Opaque white marks the detected foreground; transparent pixels are protected background. */
    public static byte[] foregroundMask(byte[] photoBytes) {
        var photo = decode(photoBytes); var mask = segment(photo);
        return maskPng(mask);
    }

    /** Color sampling can retain faint white cloth highlights without changing production mapping. */
    static byte[] colorEvidenceMask(byte[] photoBytes) {
        var photo = decode(photoBytes);
        // An explicit alpha mask is evidence; never key white fabric out of it again.
        int transparent = 0, border = 0;
        for (int y = 0; y < photo.getHeight(); y++) for (int x = 0; x < photo.getWidth(); x++)
            if (x == 0 || y == 0 || x == photo.getWidth()-1 || y == photo.getHeight()-1) {
                border++; if ((photo.getRGB(x,y) >>> 24) < 16) transparent++;
            }
        if (transparent > border * .97) return maskPng(segment(photo, -1));
        IllegalArgumentException failure = null;
        for (int tolerance : new int[] {13, 8, 5, 3}) {
            try { return maskPng(segment(photo, tolerance)); }
            catch (IllegalArgumentException rejected) { failure = rejected; }
        }
        throw failure;
    }

    /** White fabric highlights need a tighter background tolerance during recoloring. */
    static byte[] recolorMask(byte[] photoBytes) {
        return maskPng(segment(decode(photoBytes), 3));
    }

    private static byte[] maskPng(Mask mask) {
        var output = new BufferedImage(mask.width, mask.height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < mask.height; y++) for (int x = 0; x < mask.width; x++)
            if (mask.contains(x, y)) output.setRGB(x, y, 0xffffffff);
        return png(output);
    }

    private static Mask segment(BufferedImage image) {
        return segment(image, 13);
    }

    private static Mask segment(BufferedImage image, int tolerance) {
        int w = image.getWidth(), h = image.getHeight(), n = w * h;
        if (w < 16 || h < 16) throw bad("实物底图分辨率过低，无法校准服装轮廓");
        var border = new ArrayList<Integer>();
        for (int x = 0; x < w; x++) { border.add(image.getRGB(x, 0)); border.add(image.getRGB(x, h - 1)); }
        for (int y = 1; y < h - 1; y++) { border.add(image.getRGB(0, y)); border.add(image.getRGB(w - 1, y)); }
        int[] rgb = new int[3];
        for (int channel = 0; channel < 3; channel++) {
            int shift = channel * 8;
            var values = border.stream().filter(p -> (p >>> 24) > 16).mapToInt(p -> (p >>> shift) & 255).sorted().toArray();
            rgb[channel] = values.length == 0 ? 255 : values[values.length / 2];
        }
        long inconsistent = border.stream().filter(p -> (p >>> 24) > 16 && distance(p, rgb) > 20).count();
        if (inconsistent > border.size() * .03) throw bad("照片背景或边缘不够干净，无法可靠分离衣服，请使用完整的纯色背景底图");
        // Edge-connected background is removed. Enclosed white motifs/labels are never globally keyed out.
        var background = new boolean[n]; var queue = new int[n]; int head = 0, tail = 0;
        for (int x = 0; x < w; x++) { tail = seed(image, x, 0, rgb, background, queue, tail, tolerance); tail = seed(image, x, h - 1, rgb, background, queue, tail, tolerance); }
        for (int y = 1; y < h - 1; y++) { tail = seed(image, 0, y, rgb, background, queue, tail, tolerance); tail = seed(image, w - 1, y, rgb, background, queue, tail, tolerance); }
        while (head < tail) {
            int p = queue[head++], x = p % w, y = p / w;
            if (x > 0) tail = seed(image, x - 1, y, rgb, background, queue, tail, tolerance);
            if (x + 1 < w) tail = seed(image, x + 1, y, rgb, background, queue, tail, tolerance);
            if (y > 0) tail = seed(image, x, y - 1, rgb, background, queue, tail, tolerance);
            if (y + 1 < h) tail = seed(image, x, y + 1, rgb, background, queue, tail, tolerance);
        }
        var seen = background.clone(); int largest = 0; int[] component = new int[0]; long foregroundTotal = 0;
        for (int p = 0; p < n; p++) if (!seen[p]) {
            head = 0; tail = 1; queue[0] = p; seen[p] = true;
            while (head < tail) {
                int q = queue[head++], x = q % w, y = q / w;
                if (x > 0 && !seen[q - 1]) { seen[q - 1] = true; queue[tail++] = q - 1; }
                if (x + 1 < w && !seen[q + 1]) { seen[q + 1] = true; queue[tail++] = q + 1; }
                if (y > 0 && !seen[q - w]) { seen[q - w] = true; queue[tail++] = q - w; }
                if (y + 1 < h && !seen[q + w]) { seen[q + w] = true; queue[tail++] = q + w; }
            }
            foregroundTotal += tail;
            if (tail > largest) { largest = tail; component = Arrays.copyOf(queue, tail); }
        }
        if (largest < n * .04 || largest > n * .88 || largest < foregroundTotal * .97)
            throw bad("未找到完整、单一的服装前景，无法可靠套版；请更换干净底图或校准遮罩");
        var mask = new boolean[n]; int left = w, top = h, right = -1, bottom = -1;
        int[] lumas = new int[largest]; int at = 0;
        for (int p : component) {
            mask[p] = true; int x = p % w, y = p / w;
            left = Math.min(left, x); right = Math.max(right, x); top = Math.min(top, y); bottom = Math.max(bottom, y);
            lumas[at++] = (int) Math.round(luma(image.getRGB(x, y)));
        }
        // An explicit color-evidence mask may reach the frame: it supplies no geometry.
        // Production photo mapping still requires a complete outline.
        if (tolerance != -1 && (left < 1 || top < 1 || right >= w - 1 || bottom >= h - 1))
            throw bad("衣服已被画面边缘裁切，无法确定完整位置映射");
        var rows = new ArrayList<List<Span>>(h);
        for (int y = 0; y < h; y++) {
            var spans = new ArrayList<Span>(); int x = 0;
            while (x < w) { while (x < w && !mask[y * w + x]) x++; int start = x;
                while (x < w && mask[y * w + x]) x++; if (start < x) spans.add(new Span(start, x - 1)); }
            rows.add(spans);
        }
        Arrays.sort(lumas);
        return new Mask(w, h, mask, left, top, right, bottom, rows, lumas[lumas.length / 2]);
    }

    private static int seed(BufferedImage image, int x, int y, int[] background, boolean[] seen, int[] queue, int tail, int tolerance) {
        int p = y * image.getWidth() + x;
        if (!seen[p] && ((image.getRGB(x, y) >>> 24) < 16 || distance(image.getRGB(x, y), background) <= tolerance)) {
            seen[p] = true; queue[tail++] = p;
        }
        return tail;
    }
    private static int distance(int pixel, int[] rgb) {
        int max = 0; for (int i = 0; i < 3; i++) max = Math.max(max, Math.abs(((pixel >>> (8 * i)) & 255) - rgb[i])); return max;
    }
    private static double profileError(Mask a, Mask b) {
        double total = 0;
        for (int row = 2; row < 98; row++) {
            var aa = a.rows.get(a.top + (int) Math.round(row / 99d * (a.boxHeight() - 1)));
            var bb = b.rows.get(b.top + (int) Math.round(row / 99d * (b.boxHeight() - 1)));
            double aw = aa.isEmpty() ? 0 : (aa.getLast().right - aa.getFirst().left + 1) / (double) a.boxWidth();
            double bw = bb.isEmpty() ? 0 : (bb.getLast().right - bb.getFirst().left + 1) / (double) b.boxWidth();
            total += Math.abs(aw - bw);
        }
        return total / 96;
    }
    private static int spanIndex(List<Span> spans, int x) {
        for (int i = 0; i < spans.size(); i++) if (x >= spans.get(i).left && x <= spans.get(i).right) return i;
        return -1;
    }
    private static Field continuousField(Mask photo, Mask model) {
        int h = photo.height;
        var scales = new double[h]; var offsets = new double[h]; var unsupported = new int[h]; Arrays.fill(scales, Double.NaN);
        int valid = 0;
        for (int y = photo.top; y <= photo.bottom; y++) {
            int my = model.top + (int) Math.round((y - photo.top) * (model.boxHeight() - 1d) / (photo.boxHeight() - 1));
            var pp = photo.rows.get(y); var mm = model.rows.get(my);
            if (pp.isEmpty() || mm.isEmpty() || pp.size() != mm.size()) continue;
            double pl = (pp.getFirst().left - photo.left) / (double) (photo.boxWidth() - 1);
            double pr = (pp.getLast().right - photo.left) / (double) (photo.boxWidth() - 1);
            double ml = (mm.getFirst().left - model.left) / (double) (model.boxWidth() - 1);
            double mr = (mm.getLast().right - model.left) / (double) (model.boxWidth() - 1);
            if (pr - pl < .08 || mr - ml < .08) continue;
            double scale = (mr - ml) / (pr - pl);
            // A one-pixel sleeve termination can halve a scanline width. That is not cloth stretch.
            // Such rows do not constrain the warp; nearby reliable rows determine the transition.
            if (scale < .88 || scale > 1.12 || Math.abs((ml + mr - pl - pr) / 2) > .04) continue;
            scales[y] = scale; offsets[y] = (ml + mr) / 2 - (pl + pr) / 2 * scale; valid++;
        }
        if (valid < photo.boxHeight() * .75)
            throw bad("照片与模型缺少连续可靠的轮廓对应，不能自动推测套版位置，请先校准模板");
        int previous = -1;
        for (int y = photo.top; y <= photo.bottom; y++) if (!Double.isNaN(scales[y])) {
            for (int row = previous < 0 ? photo.top : previous + 1; row < y; row++) {
                double t = previous < 0 ? 1 : (row - previous) / (double) (y - previous);
                scales[row] = previous < 0 ? scales[y] : scales[previous] * (1 - t) + scales[y] * t;
                offsets[row] = previous < 0 ? offsets[y] : offsets[previous] * (1 - t) + offsets[y] * t;
                unsupported[row] = previous < 0 ? y - photo.top : y - previous - 1;
            }
            previous = y;
        }
        for (int y = previous + 1; y <= photo.bottom; y++) {
            scales[y] = scales[previous]; offsets[y] = offsets[previous]; unsupported[y] = photo.bottom - previous;
        }
        var smoothScale = scales.clone(); var smoothOffset = offsets.clone();
        int radius = Math.max(3, (int) Math.round(photo.boxHeight() * .012));
        for (int y = photo.top; y <= photo.bottom; y++) {
            double sum = 0, s = 0, o = 0;
            for (int k = -radius; k <= radius; k++) {
                int yy = Math.max(photo.top, Math.min(photo.bottom, y + k));
                double weight = Math.exp(-2d * k * k / (radius * radius));
                s += scales[yy] * weight; o += offsets[yy] * weight; sum += weight;
            }
            smoothScale[y] = s / sum; smoothOffset[y] = o / sum;
        }
        return new Field(smoothScale, smoothOffset, unsupported);
    }
    /** Sleeve silhouette disagreement cannot invalidate an otherwise supported chest interior. */
    private static boolean supportedInterior(Mask photo, Mask model, Field field, int x, int y, double mx, double my) {
        int gap = field.unsupportedRows[y];
        if (gap <= 0 || gap > photo.boxHeight() * .12) return false;
        double px = (x - photo.left) / (double) (photo.boxWidth() - 1);
        double qx = (mx - model.left) / (double) (model.boxWidth() - 1);
        if (px < .20 || px > .80 || qx < .20 || qx > .80) return false;
        int above = y, below = y;
        while (above >= photo.top && field.unsupportedRows[above] > 0) above--;
        while (below <= photo.bottom && field.unsupportedRows[below] > 0) below++;
        // Extrapolation past the collar/hem and unstable warps remain blocked.
        if (above < photo.top || below > photo.bottom
                || Math.abs(field.scale[above] - field.scale[below]) > .04
                || Math.abs(field.offset[above] - field.offset[below]) > .02) return false;
        return solidInterior(photo, x, y) && solidInterior(model, (int)Math.round(mx), (int)Math.round(my));
    }
    private static boolean solidInterior(Mask mask, int x, int y) {
        int dx = Math.max(3, (int)Math.ceil(mask.boxWidth() * .03));
        int dy = Math.max(2, (int)Math.ceil(mask.boxHeight() * .01));
        if (y-dy < mask.top || y+dy > mask.bottom) return false;
        for (int row=y-dy; row<=y+dy; row++) {
            var spans = mask.rows.get(row);int at=spanIndex(spans,x);
            if(at<0 || x-dx<spans.get(at).left || x+dx>spans.get(at).right) return false;
        }
        return true;
    }

    private static int edgeDistance(Span span, int x) { return Math.min(x - span.left, span.right - x); }
    private static Map<String, BufferedImage> textures(Map<String, byte[]> replacements, Set<String> required, Set<String> allowed) {
        if (replacements == null || !replacements.keySet().containsAll(required) || !allowed.containsAll(replacements.keySet()))
            throw bad("当前可见印花缺少对应母版，不能保留旧花或使用其他图案替代");
        var result = new LinkedHashMap<String, BufferedImage>();
        for (String id : required) result.put(id, decode(replacements.get(id)));
        return result;
    }
    // Tangent back-face fragments may be a sparse, disconnected one-pixel fringe.
    // A narrow opaque decal or a stable hollow artwork is still required to survive.
    static boolean requiresVisibleInk(double mass, double coverage, boolean stable, double largestSlot) {
        return mass > 0 && (stable || mass / Math.max(.00001, coverage) >= .10
                || coverage >= largestSlot * .005);
    }
    private static boolean stableUvSlot(BufferedImage uv, int x, int y, int index) {
        if (x < 1 || y < 1 || x >= uv.getWidth() - 1 || y >= uv.getHeight() - 1) return false;
        for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
            int p = uv.getRGB(x + dx, y + dy);
            if ((p >>> 24) == 0 || (p & 255) != index) return false;
        }
        return true;
    }
    private static Ink uvAt(BufferedImage uv, double x, double y) {
        int x0 = Math.max(0, Math.min(uv.getWidth() - 1, (int) Math.floor(x)));
        int y0 = Math.max(0, Math.min(uv.getHeight() - 1, (int) Math.floor(y)));
        int nearest = uv.getRGB(Math.max(0, Math.min(uv.getWidth() - 1, (int) Math.round(x))),
                Math.max(0, Math.min(uv.getHeight() - 1, (int) Math.round(y))));
        int index = nearest & 255;
        if ((nearest >>> 24) == 0) return new Ink(0, 0, 0, 0);
        double u = 0, v = 0, alpha = 0, weightSum = 0;
        double fx = Math.max(0, Math.min(1, x - x0)), fy = Math.max(0, Math.min(1, y - y0));
        for (int dy = 0; dy < 2; dy++) for (int dx = 0; dx < 2; dx++) {
            int p = uv.getRGB(Math.min(uv.getWidth() - 1, x0 + dx), Math.min(uv.getHeight() - 1, y0 + dy));
            double weight = (dx == 0 ? 1 - fx : fx) * (dy == 0 ? 1 - fy : fy);
            if ((p & 255) != index || (p >>> 24) == 0) continue;
            u += ((p >>> 16) & 255) / 255d * weight;
            v += ((p >>> 8) & 255) / 255d * weight;
            alpha += (p >>> 24) / 255d * weight; weightSum += weight;
        }
        return new Ink(index, u / Math.max(.00001, weightSum), v / Math.max(.00001, weightSum), alpha);
    }
    /** Premultiplied interpolation prevents opaque color leaking from transparent texture borders. */
    private static int sample(BufferedImage texture, double u, double v) {
        double x = Math.max(0, Math.min(1, u)) * (texture.getWidth() - 1), y = Math.max(0, Math.min(1, v)) * (texture.getHeight() - 1);
        int x0 = (int) x, y0 = (int) y; double alpha = 0; double[] rgb = new double[3];
        for (int dy = 0; dy < 2; dy++) for (int dx = 0; dx < 2; dx++) {
            int pixel = texture.getRGB(Math.min(texture.getWidth() - 1, x0 + dx), Math.min(texture.getHeight() - 1, y0 + dy));
            double weight = (dx == 0 ? 1 - (x - x0) : x - x0) * (dy == 0 ? 1 - (y - y0) : y - y0);
            double a = (pixel >>> 24) / 255d * weight; alpha += a;
            for (int i = 0; i < 3; i++) rgb[i] += ((pixel >>> (i * 8)) & 255) * a;
        }
        int result = (int) Math.round(alpha * 255) << 24;
        if (alpha > .00001) for (int i = 0; i < 3; i++) result |= (int) Math.round(rgb[i] / alpha) << (i * 8);
        return result;
    }
    private static int blend(int base, int ink, double alpha, double median) { return blend(base, ink, alpha, median, luma(base)); }
    private static int blend(int base, int ink, double alpha, double median, double luminance) {
        // Lightness is normalized by the cloth, not by white. Thus white ink stays visible on dark cloth.
        double shade = Math.max(.28, Math.min(1.10, .90 * Math.pow(Math.max(1, luminance) / Math.max(1, median), .70)));
        int result = base & 0xff000000;
        for (int i = 0; i < 3; i++) {
            int shift = i * 8, bc = (base >>> shift) & 255, ic = (ink >>> shift) & 255;
            double lit = Math.pow(Math.min(1, Math.pow(ic / 255d, 2.2) * shade), 1 / 2.2) * 255;
            result |= (int) Math.round(bc * (1 - alpha) + lit * alpha) << shift;
        }
        return result;
    }
    private static double luma(int p) { return .2126 * ((p >>> 16) & 255) + .7152 * ((p >>> 8) & 255) + .0722 * (p & 255); }
    private static boolean finite(double... values) { for (double v : values) if (!Double.isFinite(v)) return false; return true; }
    private static BufferedImage decode(byte[] bytes) {
        var image = CommerceMockupComposer.decode(bytes);
        if ((long) image.getWidth() * image.getHeight() > 9_000_000) throw bad("照片套版图片过大，请先缩小到 3000 像素以内");
        return image;
    }
    private static BufferedImage copy(BufferedImage image) {
        var output = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        output.setRGB(0, 0, image.getWidth(), image.getHeight(), image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth()), 0, image.getWidth());
        return output;
    }
    private static byte[] png(BufferedImage image) {
        try (var out = new ByteArrayOutputStream()) { ImageIO.write(image, "png", out); return out.toByteArray(); }
        catch (IOException e) { throw new IllegalStateException("照片套版结果编码失败", e); }
    }
    private static IllegalArgumentException bad(String message) { return new IllegalArgumentException(message); }
}
