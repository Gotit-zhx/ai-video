package com.yingdao.aicommerce.video.application;

import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class VideoCameraMotionAnalyzer {

    private static final int PATCH_RADIUS = 2;
    private static final int SEARCH_RADIUS = 6;
    private static final int MIN_FEATURES = 6;

    public MotionAnalysis analyze(VideoFrameExtractor.ExtractedFrames frames) {
        if (frames == null || frames.files() == null || frames.files().size() < 2) {
            return new MotionAnalysis("BACKGROUND_FEATURE_FLOW_V1", 0, 0, List.of());
        }
        Map<Integer, List<PairMotion>> buckets = new LinkedHashMap<>();
        GrayFrame previous = read(frames.files().getFirst());
        for (int index = 1; index < frames.files().size(); index++) {
            GrayFrame current = read(frames.files().get(index));
            double start = frames.timestampSeconds(index - 1);
            double end = frames.timestampSeconds(index);
            PairMotion motion = estimate(previous, current, start, end);
            if (motion != null) {
                int bucket = Math.max(0, (int) Math.floor((start + end) / 2.0));
                buckets.computeIfAbsent(bucket, ignored -> new ArrayList<>()).add(motion);
            }
            previous = current;
        }
        List<MotionWindow> windows = new ArrayList<>();
        for (Map.Entry<Integer, List<PairMotion>> entry : buckets.entrySet()) {
            windows.add(summarize(
                    entry.getKey(),
                    Math.min(frames.durationSeconds(), entry.getKey() + 1.0),
                    entry.getValue(),
                    frames.samplingFps(),
                    previous.width()
            ));
        }
        return new MotionAnalysis(
                "BACKGROUND_FEATURE_FLOW_V1",
                round(frames.samplingFps(), 3),
                frames.files().size(),
                List.copyOf(windows)
        );
    }

    public String cameraMovementFor(MotionAnalysis analysis, BigDecimal startSecond,
                                    BigDecimal endSecond, String fallback) {
        if (analysis == null || analysis.windows().isEmpty()) return fallback;
        double start = startSecond == null ? 0.0 : startSecond.doubleValue();
        double end = endSecond == null ? start + 1.0 : endSecond.doubleValue();
        if (end <= start) end = start + 1.0;
        Map<String, Double> weights = new LinkedHashMap<>();
        Map<String, Double> confidences = new LinkedHashMap<>();
        for (MotionWindow window : analysis.windows()) {
            double overlap = Math.max(0.0, Math.min(end, window.endSecond()) - Math.max(start, window.startSecond()));
            if (overlap <= 0) continue;
            double weight = overlap * Math.max(0.05, window.confidence());
            weights.merge(window.label(), weight, Double::sum);
            confidences.merge(window.label(), weight * window.confidence(), Double::sum);
        }
        if (weights.isEmpty()) return fallback;
        String label = weights.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
        double confidence = confidences.getOrDefault(label, 0.0) / Math.max(0.0001, weights.get(label));
        if (confidence < 0.45) return fallback;
        return label + "（背景特征检测，置信度 " + Math.round(confidence * 100) + "%）";
    }

    private PairMotion estimate(GrayFrame previous, GrayFrame current, double start, double end) {
        if (previous.width() != current.width() || previous.height() != current.height()) return null;
        List<FeatureMotion> motions = trackFeatures(previous, current, true);
        if (motions.size() < MIN_FEATURES) motions = trackFeatures(previous, current, false);
        if (motions.size() < MIN_FEATURES) return null;

        double tx = median(motions.stream().map(FeatureMotion::dx).toList());
        double ty = median(motions.stream().map(FeatureMotion::dy).toList());
        double centerX = previous.width() / 2.0;
        double centerY = previous.height() / 2.0;
        List<Double> zoomValues = new ArrayList<>();
        List<Double> residuals = new ArrayList<>();
        for (FeatureMotion motion : motions) {
            double rx = motion.x() - centerX;
            double ry = motion.y() - centerY;
            double radiusSquared = rx * rx + ry * ry;
            if (radiusSquared > 100) {
                zoomValues.add((rx * (motion.dx() - tx) + ry * (motion.dy() - ty)) / radiusSquared);
            }
        }
        double zoom = zoomValues.isEmpty() ? 0.0 : median(zoomValues);
        for (FeatureMotion motion : motions) {
            double expectedX = tx + zoom * (motion.x() - centerX);
            double expectedY = ty + zoom * (motion.y() - centerY);
            residuals.add(Math.hypot(motion.dx() - expectedX, motion.dy() - expectedY));
        }
        double medianError = median(motions.stream().map(FeatureMotion::error).toList());
        double residual = median(residuals);
        double featureScore = Math.min(1.0, motions.size() / 20.0);
        double matchScore = clamp(1.0 - medianError / 45.0);
        double consistencyScore = clamp(1.0 - residual / 3.5);
        double confidence = clamp(featureScore * 0.35 + matchScore * 0.30 + consistencyScore * 0.35);
        return new PairMotion(start, end, tx, ty, zoom, residual, confidence);
    }

    private List<FeatureMotion> trackFeatures(GrayFrame previous, GrayFrame current, boolean backgroundOnly) {
        int width = previous.width();
        int height = previous.height();
        int stepX = Math.max(18, width / 10);
        int stepY = Math.max(18, height / 14);
        List<FeatureMotion> result = new ArrayList<>();
        for (int y = 12; y < height - 12; y += stepY) {
            for (int x = 12; x < width - 12; x += stepX) {
                boolean background = x < width * 0.28 || x > width * 0.72 || y < height * 0.18;
                if (backgroundOnly && !background) continue;
                if (texture(previous, x, y) < 16.0) continue;
                FeatureMotion motion = match(previous, current, x, y);
                if (motion != null && motion.error() < 48.0) result.add(motion);
            }
        }
        return result;
    }

    private FeatureMotion match(GrayFrame previous, GrayFrame current, int x, int y) {
        double best = Double.MAX_VALUE;
        int bestDx = 0;
        int bestDy = 0;
        for (int dy = -SEARCH_RADIUS; dy <= SEARCH_RADIUS; dy++) {
            for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
                if (!inside(current, x + dx, y + dy, PATCH_RADIUS)) continue;
                double score = patchError(previous, current, x, y, dx, dy);
                if (score < best) {
                    best = score;
                    bestDx = dx;
                    bestDy = dy;
                }
            }
        }
        return Double.isFinite(best) ? new FeatureMotion(x, y, bestDx, bestDy, best) : null;
    }

    private double patchError(GrayFrame previous, GrayFrame current, int x, int y, int dx, int dy) {
        long sum = 0;
        int count = 0;
        for (int py = -PATCH_RADIUS; py <= PATCH_RADIUS; py++) {
            for (int px = -PATCH_RADIUS; px <= PATCH_RADIUS; px++) {
                int left = previous.value(x + px, y + py);
                int right = current.value(x + px + dx, y + py + dy);
                sum += Math.abs(left - right);
                count++;
            }
        }
        return sum / (double) count;
    }

    private double texture(GrayFrame frame, int x, int y) {
        int horizontal = Math.abs(frame.value(x + 2, y) - frame.value(x - 2, y));
        int vertical = Math.abs(frame.value(x, y + 2) - frame.value(x, y - 2));
        int diagonal = Math.abs(frame.value(x + 2, y + 2) - frame.value(x - 2, y - 2));
        return (horizontal + vertical + diagonal) / 3.0;
    }

    private MotionWindow summarize(double start, double end, List<PairMotion> pairs,
                                   double fps, int width) {
        double tx = pairs.stream().mapToDouble(PairMotion::tx).average().orElse(0.0);
        double ty = pairs.stream().mapToDouble(PairMotion::ty).average().orElse(0.0);
        double zoom = pairs.stream().mapToDouble(PairMotion::zoom).average().orElse(0.0);
        double confidence = pairs.stream().mapToDouble(PairMotion::confidence).average().orElse(0.0);
        double path = pairs.stream().mapToDouble(pair -> Math.hypot(pair.tx(), pair.ty())).average().orElse(0.0);
        double net = Math.hypot(tx, ty);
        double jitter = path < 0.4 ? 0.0 : clamp(1.0 - net / Math.max(path, 0.001));
        double horizontal = -tx * fps / width * 100.0;
        double vertical = -ty * fps / width * 100.0;
        double zoomPercent = zoom * fps * 100.0;
        String label = classify(horizontal, vertical, zoomPercent, jitter, path * fps / width * 100.0);
        return new MotionWindow(
                round(start, 3), round(end, 3), label,
                round(confidence, 3), round(horizontal, 3), round(vertical, 3),
                round(zoomPercent, 3), round(jitter, 3)
        );
    }

    private String classify(double horizontal, double vertical, double zoom,
                            double jitter, double translationPath) {
        if (jitter > 0.55 && translationPath > 0.6) return "手持轻微抖动";
        List<String> motions = new ArrayList<>();
        if (Math.abs(horizontal) > 0.5) motions.add(horizontal > 0 ? "向右平移" : "向左平移");
        if (Math.abs(vertical) > 0.5) motions.add(vertical > 0 ? "向下移动" : "向上移动");
        if (Math.abs(zoom) > 0.8) motions.add(zoom > 0 ? "缓慢推近" : "缓慢拉远");
        return motions.isEmpty() ? "固定机位，无明显运镜" : String.join("并", motions);
    }

    private GrayFrame read(Path file) {
        try {
            BufferedImage image = ImageIO.read(file.toFile());
            if (image == null) throw new IOException("不支持的图片格式");
            int width = image.getWidth();
            int height = image.getHeight();
            int[] values = new int[width * height];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int rgb = image.getRGB(x, y);
                    int red = (rgb >> 16) & 0xff;
                    int green = (rgb >> 8) & 0xff;
                    int blue = rgb & 0xff;
                    values[y * width + x] = (red * 77 + green * 150 + blue * 29) >> 8;
                }
            }
            return new GrayFrame(width, height, values);
        } catch (IOException exception) {
            throw new IllegalStateException("读取运镜分析帧失败", exception);
        }
    }

    private boolean inside(GrayFrame frame, int x, int y, int margin) {
        return x >= margin && y >= margin && x < frame.width() - margin && y < frame.height() - margin;
    }

    private double median(List<Double> values) {
        if (values == null || values.isEmpty()) return 0.0;
        List<Double> sorted = values.stream().sorted(Comparator.naturalOrder()).toList();
        int middle = sorted.size() / 2;
        return sorted.size() % 2 == 0
                ? (sorted.get(middle - 1) + sorted.get(middle)) / 2.0
                : sorted.get(middle);
    }

    private double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private double round(double value, int scale) {
        double factor = Math.pow(10, scale);
        return Math.round(value * factor) / factor;
    }

    public record MotionAnalysis(
            String method,
            double samplingFps,
            int analyzedFrameCount,
            List<MotionWindow> windows
    ) { }

    public record MotionWindow(
            double startSecond,
            double endSecond,
            String label,
            double confidence,
            double horizontalPercentPerSecond,
            double verticalPercentPerSecond,
            double zoomPercentPerSecond,
            double jitterScore
    ) { }

    private record PairMotion(
            double startSecond,
            double endSecond,
            double tx,
            double ty,
            double zoom,
            double residual,
            double confidence
    ) { }

    private record FeatureMotion(int x, int y, double dx, double dy, double error) { }

    private record GrayFrame(int width, int height, int[] values) {
        int value(int x, int y) { return values[y * width + x]; }
    }
}
