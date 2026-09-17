package com.yingdao.aicommerce.product.application;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Conservative, deterministic preparation of a reusable transparent artwork master.
 * This is background separation, not semantic segmentation: white regions enclosed
 * by the design remain white unless independently identified as canvas gaps.
 * The caller keeps the original asset independently.
 */
public final class CommerceArtworkMatte {
    public static final String VERSION = "transparent-master-v2";
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final int MAX_DIMENSION = 4096;
    private static final long MAX_PIXELS = 12_000_000;

    public enum Mode { EXISTING_ALPHA, EDGE_CONNECTED_WHITE }
    public record Bounds(int x, int y, int width, int height) {}
    /** A caller-verified canvas gap, in the unchanged source image's pixel coordinates. */
    public record BackgroundSeed(int x, int y) {}
    public record Report(Mode mode, int width, int height, Bounds foregroundBounds,
                         long backgroundRemovedPixels, long edgeAdjustedPixels,
                         List<String> warnings) {
        public Report { warnings = List.copyOf(warnings); }
    }
    public record Result(byte[] png, Report report) {
        public Result { png = png.clone(); }
        @Override public byte[] png() { return png.clone(); }
    }
    public static final class MatteException extends IllegalArgumentException {
        private final String code;
        public MatteException(String code, String message) { super(message); this.code = code; }
        public String code() { return code; }
    }

    private CommerceArtworkMatte() {}

    /** Returns a new PNG and inspection report without changing input bytes or artwork scale. */
    public static Result prepare(byte[] original) {
        return prepare(original, List.of());
    }

    /**
     * Explicit gap seeds identify enclosed canvas without globally removing white ink.
     * Callers must bind these coordinates to the original asset in their cache inputs.
     * Generated white holes and intentional white lettering are otherwise indistinguishable.
     */
    public static Result prepare(byte[] original, List<BackgroundSeed> canvasGaps) {
        if (canvasGaps == null || canvasGaps.size() > 128 || canvasGaps.stream().anyMatch(p -> p == null))
            throw failure("INVALID_CANVAS_GAPS", "背景空隙标记无效，单张母版最多支持 128 个已确认的背景点。");
        var source = decodeBounded(original);
        int width = source.getWidth(), height = source.getHeight();
        int[] pixels = source.getRGB(0, 0, width, height, null, 0, width);
        boolean hasAlpha = false;
        int transparent = 0, partial = 0;
        for (int pixel : pixels) {
            int alpha = pixel >>> 24;
            if (alpha != 255) hasAlpha = true;
            if (alpha == 0) transparent++;
            else if (alpha < 250) partial++;
        }
        if (transparent == pixels.length) throw failure("EMPTY_ARTWORK", "印花原稿完全透明，没有可用图案。");
        // One transparent pixel, or alpha=254 across a white canvas, does not prove
        // that a background has been removed. Require an edge-connected clear canvas
        // with meaningful depth; retain the actual artwork's alpha pixel-for-pixel.
        if (hasAlpha && trustworthyAlphaCanvas(pixels, width, height)) {
            if (!canvasGaps.isEmpty())
                throw failure("EXISTING_ALPHA_GAPS", "已有透明母版的白色区域可能属于设计；请在原始白底图上确认背景空隙后处理。");
            var bounds = bounds(pixels, width, height);
            var warnings = new ArrayList<String>();
            if (touchesEdge(bounds, width, height)) warnings.add("印花触及画布边缘；已保留现有透明通道，请检查原稿是否被裁切。");
            return result(pixels, width, height,
                    new Report(Mode.EXISTING_ALPHA, width, height, bounds, 0, 0, warnings));
        }
        if (hasAlpha && (partial > 0 || transparent > Math.max(1, pixels.length / 1000)))
            throw failure("ALPHA_CANVAS_UNCERTAIN", "原稿含半透明或局部透明像素，但没有充分的透明画布边缘，不能确认白色区域是背景还是图案；已保留原稿，请提供完整透明留边的母版。");

        int[] background = inspectWhiteBorder(pixels, width, height);
        if (transparent > 0) inspectSparseTransparency(pixels, background, width, height);
        boolean[] removed = new boolean[pixels.length];
        int[] queue = new int[pixels.length];
        int tail = 0;
        for (int x = 0; x < width; x++) {
            tail = enqueue(x, pixels, background, removed, queue, tail);
            tail = enqueue((height - 1) * width + x, pixels, background, removed, queue, tail);
        }
        for (int y = 1; y < height - 1; y++) {
            tail = enqueue(y * width, pixels, background, removed, queue, tail);
            tail = enqueue(y * width + width - 1, pixels, background, removed, queue, tail);
        }
        for (var gap : canvasGaps) {
            if (gap.x() < 0 || gap.y() < 0 || gap.x() >= width || gap.y() >= height
                    || distance(pixels[gap.y() * width + gap.x()], background) > 8)
                throw failure("INVALID_CANVAS_GAP", "背景空隙标记未落在原稿的白色画布上，已停止处理以保护图案。");
            tail = enqueue(gap.y() * width + gap.x(), pixels, background, removed, queue, tail);
        }
        for (int head = 0; head < tail; head++) {
            int index = queue[head], x = index % width, y = index / width;
            if (x > 0) tail = enqueue(index - 1, pixels, background, removed, queue, tail);
            if (x + 1 < width) tail = enqueue(index + 1, pixels, background, removed, queue, tail);
            if (y > 0) tail = enqueue(index - width, pixels, background, removed, queue, tail);
            if (y + 1 < height) tail = enqueue(index + width, pixels, background, removed, queue, tail);
            // Diagonally open hair curls are connected canvas too. This does not
            // cross an actual ink pixel or key out enclosed white lettering.
            if (x > 0 && y > 0) tail = enqueue(index - width - 1, pixels, background, removed, queue, tail);
            if (x + 1 < width && y > 0) tail = enqueue(index - width + 1, pixels, background, removed, queue, tail);
            if (x > 0 && y + 1 < height) tail = enqueue(index + width - 1, pixels, background, removed, queue, tail);
            if (x + 1 < width && y + 1 < height) tail = enqueue(index + width + 1, pixels, background, removed, queue, tail);
        }

        // Require actual contrasting artwork, rather than accepting an all-white or
        // almost-white canvas as a successfully prepared transparent print.
        boolean visibleArtwork = false;
        int minX = width, minY = height, maxX = -1, maxY = -1;
        for (int i = 0; i < pixels.length; i++) if (!removed[i] && (pixels[i] >>> 24) > 0) {
            int x = i % width, y = i / width;
            minX = Math.min(minX, x); minY = Math.min(minY, y);
            maxX = Math.max(maxX, x); maxY = Math.max(maxY, y);
            if (distance(pixels[i], background) >= 20) visibleArtwork = true;
        }
        if (!visibleArtwork) throw failure("NO_DISTINCT_ARTWORK", "未检测到可与白底可靠分离的印花；请提供透明原稿或带清晰轮廓的图案。");
        int padding = Math.min(4, Math.max(1, Math.min(width, height) / 100));
        if (minX < padding || minY < padding || maxX >= width - padding || maxY >= height - padding)
            throw failure("ARTWORK_TOUCHES_EDGE", "印花或背景杂色触及画布边缘，无法可靠去底；请保留完整图案并增加白色留边。");

        int[] output = pixels.clone();
        int fringeRadius = Math.max(2, Math.min(6, Math.min(width, height) / 256));
        long adjusted = 0;
        for (int i = 0; i < output.length; i++) {
            if (removed[i]) { output[i] = 0; continue; }
            if (nearBackground(i, removed, width, height, fringeRadius)) {
                int corrected = unmatteEdge(i, pixels, removed, background, width, height, fringeRadius);
                if (corrected != pixels[i]) { output[i] = corrected; adjusted++; }
            }
        }
        var warnings = new ArrayList<String>();
        warnings.add("仅分离与边缘或已确认背景点相连的白色画布；没有背景标记的封闭白色设计保持不变。");
        if (!canvasGaps.isEmpty()) warnings.add("已使用 " + canvasGaps.size() + " 个与原稿绑定的背景空隙标记；未全局删除白色文字或图案。");
        return result(output, width, height, new Report(Mode.EDGE_CONNECTED_WHITE, width, height,
                bounds(output, width, height), tail, adjusted, warnings));
    }

    private static BufferedImage decodeBounded(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES)
            throw failure("INVALID_IMAGE_SIZE", "印花原稿须为不超过 8 MB 的可解码图片。");
        // Inspect dimensions before allocating decoded pixels; the shared composer
        // accepts larger product photographs than are needed for an artwork master.
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (input == null) throw failure("INVALID_IMAGE", "印花原稿无法解码。");
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw failure("INVALID_IMAGE", "印花原稿无法解码。");
            var reader = readers.next();
            try {
                reader.setInput(input);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 2 || height < 2 || width > MAX_DIMENSION || height > MAX_DIMENSION || (long) width * height > MAX_PIXELS)
                    throw failure("INVALID_IMAGE_DIMENSIONS", "印花原稿尺寸须至少为 2×2、单边不超过 4096 像素且总像素不超过 1200 万。");
                return reader.read(0);
            } finally { reader.dispose(); }
        } catch (IOException e) { throw failure("INVALID_IMAGE", "印花原稿无法解码。"); }
    }

    static boolean trustworthyAlphaCanvas(int[] pixels, int width, int height) {
        int inset = Math.min(2, Math.min(width, height) / 8);
        if (transparentRingShare(pixels, width, height, 0) < .70
                || transparentRingShare(pixels, width, height, inset) < .50) return false;
        boolean[] seen = new boolean[pixels.length];
        int[] queue = new int[pixels.length];
        int tail = 0;
        for (int x = 0; x < width; x++) {
            tail = enqueueTransparent(x, pixels, seen, queue, tail);
            tail = enqueueTransparent((height - 1) * width + x, pixels, seen, queue, tail);
        }
        for (int y = 1; y < height - 1; y++) {
            tail = enqueueTransparent(y * width, pixels, seen, queue, tail);
            tail = enqueueTransparent(y * width + width - 1, pixels, seen, queue, tail);
        }
        for (int head = 0; head < tail; head++) {
            int i = queue[head], x = i % width, y = i / width;
            if (x > 0) tail = enqueueTransparent(i - 1, pixels, seen, queue, tail);
            if (x + 1 < width) tail = enqueueTransparent(i + 1, pixels, seen, queue, tail);
            if (y > 0) tail = enqueueTransparent(i - width, pixels, seen, queue, tail);
            if (y + 1 < height) tail = enqueueTransparent(i + width, pixels, seen, queue, tail);
        }
        return tail >= Math.max(4, pixels.length * .03);
    }

    private static double transparentRingShare(int[] pixels, int width, int height, int inset) {
        int total = 0, clear = 0;
        for (int y = inset; y < height - inset; y++) for (int x = inset; x < width - inset; x++) {
            if (x != inset && x != width - inset - 1 && y != inset && y != height - inset - 1) continue;
            total++;
            if ((pixels[y * width + x] >>> 24) == 0) clear++;
        }
        return clear / (double) Math.max(1, total);
    }

    private static int enqueueTransparent(int i, int[] pixels, boolean[] seen, int[] queue, int tail) {
        if (!seen[i] && (pixels[i] >>> 24) == 0) { seen[i] = true; queue[tail++] = i; }
        return tail;
    }

    private static void inspectSparseTransparency(int[] pixels, int[] background, int width, int height) {
        for (int i = 0; i < pixels.length; i++) if ((pixels[i] >>> 24) == 0) {
            int x = i % width, y = i / width;
            for (int neighbor : new int[] {x > 0 ? i - 1 : i, x + 1 < width ? i + 1 : i,
                    y > 0 ? i - width : i, y + 1 < height ? i + width : i})
                if ((pixels[neighbor] >>> 24) > 0 && distance(pixels[neighbor], background) > 3)
                    throw failure("ALPHA_CANVAS_UNCERTAIN", "局部透明像素位于图案边界，可能属于原稿设计，不能据此自动去除白色区域；请提供完整透明留边的母版。");
        }
    }

    private static int[] inspectWhiteBorder(int[] pixels, int width, int height) {
        int[] min = {255, 255, 255}, max = {0, 0, 0};
        long[] sum = {0, 0, 0};
        int total = 0, white = 0;
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            if (x != 0 && x != width - 1 && y != 0 && y != height - 1) continue;
            int pixel = pixels[y * width + x];
            if ((pixel >>> 24) == 0) continue;
            int r = channel(pixel, 0), g = channel(pixel, 1), b = channel(pixel, 2);
            if (Math.min(r, Math.min(g, b)) >= 245 && Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) <= 6) white++;
            for (int c = 0; c < 3; c++) {
                int value = channel(pixel, c);
                min[c] = Math.min(min[c], value); max[c] = Math.max(max[c], value); sum[c] += value;
            }
            total++;
        }
        if (total == 0) throw failure("ALPHA_CANVAS_UNCERTAIN", "原稿只有过薄的透明边缘，无法确认内部白色是背景还是图案；请补充完整的透明母版。");
        if (white < total) {
            if (white >= total * 0.85) throw failure("ARTWORK_TOUCHES_EDGE", "图案或杂色触及白色画布边缘，无法可靠去底；请增加完整白色留边。");
            throw failure("BACKGROUND_NOT_UNIFORM", "印花背景不是均匀白底，无法可靠自动分离；请使用透明原稿或重新生成纯白背景图案。");
        }
        int[] background = new int[3];
        for (int c = 0; c < 3; c++) {
            background[c] = (int) Math.round((double) sum[c] / total);
            if (max[c] - min[c] > 6 || background[c] < 248)
                throw failure("BACKGROUND_NOT_UNIFORM", "白底存在渐变、阴影或颜色不均，自动去底可能损伤印花；请提供透明原稿或均匀纯白底图。");
        }
        return background;
    }

    private static int enqueue(int index, int[] pixels, int[] background, boolean[] removed, int[] queue, int tail) {
        // A few quantization levels of near-white are still the connected canvas.
        // These were previously left fully opaque as a dotted white halo.
        if (!removed[index] && ((pixels[index] >>> 24) == 0 || distance(pixels[index], background) <= 8)) {
            removed[index] = true; queue[tail++] = index;
        }
        return tail;
    }

    private static boolean adjacentBackground(int index, boolean[] removed, int width, int height) {
        int x = index % width, y = index / width;
        return (x > 0 && removed[index - 1]) || (x + 1 < width && removed[index + 1])
                || (y > 0 && removed[index - width]) || (y + 1 < height && removed[index + width]);
    }

    private static boolean nearBackground(int index, boolean[] removed, int width, int height, int radius) {
        int x = index % width, y = index / width;
        if (adjacentBackground(index, removed, width, height)) return true;
        for (int dy = -radius; dy <= radius; dy++) for (int dx = -radius; dx <= radius; dx++) {
            if (dx * dx + dy * dy > radius * radius) continue;
            int nx = x + dx, ny = y + dy;
            if (nx >= 0 && ny >= 0 && nx < width && ny < height && removed[ny * width + nx]) return true;
        }
        return false;
    }

    /** Remove white contamination only where a nearby interior color explains the edge sample. */
    private static int unmatteEdge(int index, int[] pixels, boolean[] removed, int[] background, int width, int height, int radius) {
        int pixel = pixels[index], x = index % width, y = index / width;
        // A real partial alpha is authored information, not a white matte to infer.
        if ((pixel >>> 24) < 250) return pixel;
        int originalDistance = distance(pixel, background);
        if (originalDistance < 4 || originalDistance > 244) return pixel;
        int best = pixel, bestDistance = originalDistance;
        // Generation/resizing at 1024px can leave a 3–4px matte, wider than the
        // old fixed 2px search. The color fit still has to explain every channel.
        for (int dy = -radius; dy <= radius; dy++) for (int dx = -radius; dx <= radius; dx++) {
            if (dx * dx + dy * dy > radius * radius + 1) continue;
            int nx = x + dx, ny = y + dy;
            if (nx < 0 || ny < 0 || nx >= width || ny >= height) continue;
            int candidateIndex = ny * width + nx;
            if (removed[candidateIndex] || adjacentBackground(candidateIndex, removed, width, height)) continue;
            int candidate = pixels[candidateIndex], candidateDistance = distance(candidate, background);
            if (candidateDistance <= bestDistance + 12) continue;
            double alpha = fitAlpha(pixel, candidate, background);
            if (alpha > 0 && alpha < 0.97 && explained(pixel, candidate, background, alpha)) {
                best = candidate; bestDistance = candidateDistance;
            }
        }
        if (best == pixel) return pixel;
        double alpha = fitAlpha(pixel, best, background);
        int a = Math.max(1, Math.min(254, (int) Math.round(alpha * (pixel >>> 24))));
        return (a << 24) | (best & 0x00ffffff);
    }

    private static double fitAlpha(int sample, int foreground, int[] background) {
        double dot = 0, length = 0;
        for (int c = 0; c < 3; c++) {
            int a = background[c] - channel(sample, c), b = background[c] - channel(foreground, c);
            dot += a * b; length += b * b;
        }
        return length == 0 ? 1 : dot / length;
    }

    private static boolean explained(int sample, int foreground, int[] background, double alpha) {
        for (int c = 0; c < 3; c++) {
            double expected = alpha * channel(foreground, c) + (1 - alpha) * background[c];
            if (Math.abs(expected - channel(sample, c)) > 4) return false;
        }
        return true;
    }

    private static int channel(int pixel, int channel) { return (pixel >>> (16 - 8 * channel)) & 255; }
    private static int distance(int pixel, int[] background) {
        int max = 0;
        for (int c = 0; c < 3; c++) max = Math.max(max, Math.abs(channel(pixel, c) - background[c]));
        return max;
    }
    private static boolean touchesEdge(Bounds bounds, int width, int height) {
        return bounds.x() == 0 || bounds.y() == 0 || bounds.x() + bounds.width() == width || bounds.y() + bounds.height() == height;
    }
    private static Bounds bounds(int[] pixels, int width, int height) {
        int minX = width, minY = height, maxX = -1, maxY = -1;
        for (int i = 0; i < pixels.length; i++) if ((pixels[i] >>> 24) > 0) {
            int x = i % width, y = i / width;
            minX = Math.min(minX, x); minY = Math.min(minY, y);
            maxX = Math.max(maxX, x); maxY = Math.max(maxY, y);
        }
        if (maxX < 0) throw failure("EMPTY_ARTWORK", "印花原稿完全透明，没有可用图案。");
        return new Bounds(minX, minY, maxX - minX + 1, maxY - minY + 1);
    }
    private static Result result(int[] pixels, int width, int height, Report report) {
        var output = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        output.setRGB(0, 0, width, height, pixels, 0, width);
        try (var bytes = new ByteArrayOutputStream()) {
            if (!ImageIO.write(output, "png", bytes)) throw new IOException("PNG writer unavailable");
            return new Result(bytes.toByteArray(), report);
        } catch (IOException e) { throw new IllegalStateException("透明印花母版编码失败", e); }
    }
    private static MatteException failure(String code, String message) { return new MatteException(code, message); }
}
