package com.yingdao.aicommerce.video.application;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/** Splits the original storyboard without repainting or removing subject pixels. */
final class StoryboardFrameExtractor {
    private StoryboardFrameExtractor() {}

    static List<BufferedImage> split(BufferedImage source) {
        if (source == null || source.getWidth() < 30 || source.getHeight() < 30) {
            throw new IllegalArgumentException("九宫格图片尺寸不足，无法生成九张独立参考图");
        }
        int[][] columns = boundaries(source, true);
        int[][] rows = boundaries(source, false);
        List<BufferedImage> frames = new ArrayList<>(9);
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                int x = columns[column][1], y = rows[row][1];
                int width = columns[column + 1][0] - x;
                int height = rows[row + 1][0] - y;
                BufferedImage frame = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = frame.createGraphics();
                try { graphics.drawImage(source, 0, 0, width, height, x, y, x + width, y + height, null); }
                finally { graphics.dispose(); }
                frames.add(frame);
            }
        }
        return List.copyOf(frames);
    }

    private static int[][] boundaries(BufferedImage source, boolean vertical) {
        int length = vertical ? source.getWidth() : source.getHeight();
        int[][] boundaries = {{0, 0}, {length / 3, length / 3},
                {length * 2 / 3, length * 2 / 3}, {length, length}};
        // A separator must be a narrow, uninterrupted black/white band across
        // the WHOLE original grid. Never trim outer edges, crop a fixed percent,
        // remove corner labels, or paint over clothing/face pixels.
        int radius = Math.max(2, length / 100);
        int maxBandWidth = Math.max(2, Math.min(12, length / 100));
        for (int boundary = 1; boundary <= 2; boundary++) {
            int expected = length * boundary / 3;
            int bestDistance = Integer.MAX_VALUE;
            int start = Math.max(1, expected - radius), limit = Math.min(length - 1, expected + radius);
            for (int at = start; at <= limit;) {
                int color = separatorColor(source, vertical, at);
                if (color == 0) { at++; continue; }
                int end = at + 1;
                while (end < length && separatorColor(source, vertical, end) == color) end++;
                int distance = Math.abs((at + end) / 2 - expected);
                boolean bounded = at > start && end <= limit && end - at <= maxBandWidth;
                if (bounded && distance < bestDistance) {
                    boundaries[boundary] = new int[]{at, end};
                    bestDistance = distance;
                }
                at = end;
            }
        }
        return boundaries;
    }

    private static int separatorColor(BufferedImage source, boolean vertical, int position) {
        int span = vertical ? source.getHeight() : source.getWidth();
        int kind = 0;
        for (int offset = 0; offset < span; offset++) {
            int rgb = source.getRGB(vertical ? position : offset, vertical ? offset : position);
            int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
            int pixelKind = r >= 250 && g >= 250 && b >= 250 ? 1
                    : r <= 5 && g <= 5 && b <= 5 ? -1 : 0;
            if (pixelKind == 0 || (kind != 0 && kind != pixelKind)) return 0;
            kind = pixelKind;
        }
        return kind;
    }
}
