package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.product.application.CommerceModelDetailStyleGuide.Region;
import javax.imageio.ImageIO;
import javax.imageio.IIOImage;
import javax.imageio.ImageWriteParam;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Color;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Pixel-only 2x2 layout; image quality and product identity remain the reviewer's responsibility. */
public final class CommerceModelDetailGridComposer {
    public static final int CELL_SIZE = 768;
    public static final int GRID_SIZE = CELL_SIZE * 2;
    public static final int MAX_IMAGES = 4;
    public static final int MIN_GENERATED_CELL_SIZE = 512;
    public static final int ARCHIVE_MAX_BYTES = 8 * 1024 * 1024;
    private static final int AMBIENCE_SIZE = 32;
    private static final int MAX_BYTES = 32 * 1024 * 1024;
    private static final int MAX_DIMENSION = 8192;
    private static final long MAX_PIXELS = 16_777_216L;
    private static final Set<String> FORMATS = Set.of("png", "jpeg", "jpg", "webp");

    private CommerceModelDetailGridComposer() {}

    /** Repeats only the new product in active slots; this is the clear editing identity reference. */
    public static byte[] productGrid(byte[] product, int actualCount) {
        requireCount(actualCount);
        requireBytes(product);
        return compose(java.util.Collections.nCopies(actualCount, product));
    }

    /** Retains coarse pose, lighting and scene colors without a legible old print or lettering. */
    public static byte[] styleAmbience(byte[] original) {
        var source = decode(original, false);
        var small = new BufferedImage(AMBIENCE_SIZE, AMBIENCE_SIZE, BufferedImage.TYPE_INT_RGB);
        var graphics = small.createGraphics();
        try {
            graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, AMBIENCE_SIZE, AMBIENCE_SIZE);
            double scale = Math.min((double) AMBIENCE_SIZE / source.getWidth(), (double) AMBIENCE_SIZE / source.getHeight());
            int width = Math.max(1, Math.min(AMBIENCE_SIZE, (int) Math.round(source.getWidth() * scale)));
            int height = Math.max(1, Math.min(AMBIENCE_SIZE, (int) Math.round(source.getHeight() * scale)));
            // Area averaging suppresses high-frequency aliasing before blurring; interpolation alone cannot.
            var scaled = source.getScaledInstance(width, height, Image.SCALE_AREA_AVERAGING);
            try { graphics.drawImage(scaled, (AMBIENCE_SIZE - width) / 2, (AMBIENCE_SIZE - height) / 2, null); }
            finally { scaled.flush(); }
        } finally { graphics.dispose(); source.flush(); }
        var result = new BufferedImage(CELL_SIZE, CELL_SIZE, BufferedImage.TYPE_INT_RGB);
        var output = result.createGraphics();
        try {
            // Two separable radius-3 binomial passes remove remaining tiny letters/eyes/line art.
            lowPass(small); lowPass(small);
            output.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            output.drawImage(small, 0, 0, CELL_SIZE, CELL_SIZE, null);
            return png(result);
        } finally { output.dispose(); small.flush(); result.flush(); }
    }

    /** Same frozen ordinal layout as compose, but every original is reduced to atmosphere first. */
    public static byte[] styleAmbienceGrid(List<byte[]> originals) {
        if (originals == null) throw invalid("缺少原详情图片");
        requireCount(originals.size()); originals.forEach(CommerceModelDetailGridComposer::requireBytes);
        var ambience = new ArrayList<byte[]>(originals.size());
        for (byte[] original : originals) ambience.add(styleAmbience(original));
        return compose(ambience);
    }

    /** Suppresses only explicitly marked old prints; every pixel outside their rectangles stays exact. */
    public static byte[] selectiveStyle(byte[] original,List<Region> regions) {
        if(regions==null||regions.size()>16||regions.stream().anyMatch(java.util.Objects::isNull))throw invalid("旧印花区域清单无效");
        var source=decode(original,false);
        try {
            if(regions.isEmpty())return original;
            int width=source.getWidth(),height=source.getHeight();
            var result=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);
            result.setRGB(0,0,width,height,source.getRGB(0,0,width,height,null,0,width),0,width);
            try {
                for(var region:regions) {
                    // Pixel centers, not a padded box: edge feathering never reaches outside the region.
                    int left=Math.max(0,(int)Math.ceil(region.x()*width-.5));
                    int top=Math.max(0,(int)Math.ceil(region.y()*height-.5));
                    int right=Math.min(width,(int)Math.ceil((region.x()+region.width())*width-.5));
                    int bottom=Math.min(height,(int)Math.ceil((region.y()+region.height())*height-.5));
                    if(right<=left||bottom<=top)continue;
                    var softened=softenedRegion(source,left,top,right-left,bottom-top);
                    try {
                        double feather=Math.max(1,Math.min(64,Math.min(right-left,bottom-top)*.12));
                        for(int y=top;y<bottom;y++)for(int x=left;x<right;x++) {
                            double distance=Math.min(Math.min(left==0?Double.POSITIVE_INFINITY:x-left+.5,right==width?Double.POSITIVE_INFINITY:right-x-.5),
                                    Math.min(top==0?Double.POSITIVE_INFINITY:y-top+.5,bottom==height?Double.POSITIVE_INFINITY:bottom-y-.5));
                            double amount=Math.min(1,distance/feather);amount=amount*amount*(3-2*amount);
                            int before=result.getRGB(x,y),after=softened.getRGB(x-left,y-top);
                            int red=(int)Math.round(((before>>>16)&255)*(1-amount)+((after>>>16)&255)*amount);
                            int green=(int)Math.round(((before>>>8)&255)*(1-amount)+((after>>>8)&255)*amount);
                            int blue=(int)Math.round((before&255)*(1-amount)+(after&255)*amount);
                            result.setRGB(x,y,(before&0xff000000)|(red<<16)|(green<<8)|blue);
                        }
                    } finally {softened.flush();}
                }
                return png(result);
            } finally {result.flush();}
        } finally {source.flush();}
    }

    /** Keeps each original's framing, face, pose and background; only marked print regions are softened. */
    public static byte[] selectiveStyleGrid(List<byte[]> originals,List<List<Region>> regions) {
        if(originals==null||regions==null||regions.size()!=originals.size())throw invalid("详情图片与旧印花区域数量不一致");
        requireCount(originals.size());originals.forEach(CommerceModelDetailGridComposer::requireBytes);
        var styled=new ArrayList<byte[]>(originals.size());
        for(int i=0;i<originals.size();i++)styled.add(selectiveStyle(originals.get(i),regions.get(i)));
        return compose(styled);
    }

    /** V4 retains pose geometry but removes source color authority and suppresses the old print. */
    public static byte[] poseOnlyStyle(byte[] original,List<Region> regions) {
        var source=decode(selectiveStyle(original,regions),false);
        var result=new BufferedImage(source.getWidth(),source.getHeight(),BufferedImage.TYPE_INT_ARGB);
        try {
            for(int y=0;y<source.getHeight();y++)for(int x=0;x<source.getWidth();x++) {
                int pixel=source.getRGB(x,y);
                int gray=(299*((pixel>>>16)&255)+587*((pixel>>>8)&255)+114*(pixel&255)+500)/1000;
                result.setRGB(x,y,(pixel&0xff000000)|(gray<<16)|(gray<<8)|gray);
            }
            return png(result);
        } finally {source.flush();result.flush();}
    }

    /** Same fixed ordinal slots as V3, without changing or recropping the original reference geometry. */
    public static byte[] poseOnlyStyleGrid(List<byte[]> originals,List<List<Region>> regions) {
        if(originals==null||regions==null||regions.size()!=originals.size())throw invalid("详情图片与姿势参考数量不一致");
        requireCount(originals.size());originals.forEach(CommerceModelDetailGridComposer::requireBytes);
        var styled=new ArrayList<byte[]>(originals.size());
        for(int i=0;i<originals.size();i++)styled.add(poseOnlyStyle(originals.get(i),regions.get(i)));
        return compose(styled);
    }

    private static BufferedImage softenedRegion(BufferedImage source,int left,int top,int width,int height) {
        double scale=Math.min(1,16.0/Math.max(width,height));
        int smallWidth=Math.max(1,(int)Math.round(width*scale)),smallHeight=Math.max(1,(int)Math.round(height*scale));
        var small=new BufferedImage(smallWidth,smallHeight,BufferedImage.TYPE_INT_RGB);
        var graphics=small.createGraphics();
        var crop=source.getSubimage(left,top,width,height);
        var reduced=crop.getScaledInstance(smallWidth,smallHeight,Image.SCALE_AREA_AVERAGING);
        try {
            graphics.setColor(Color.WHITE);graphics.fillRect(0,0,smallWidth,smallHeight);
            graphics.drawImage(reduced,0,0,null);
        } finally {graphics.dispose();reduced.flush();}
        var result=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);
        var output=result.createGraphics();
        try {
            lowPass(small);lowPass(small);
            output.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            output.drawImage(small,0,0,width,height,null);return result;
        } finally {output.dispose();small.flush();}
    }

    private static void lowPass(BufferedImage image) {
        int width = image.getWidth(), height = image.getHeight();
        int[] input = image.getRGB(0, 0, width, height, null, 0, width), horizontal = new int[input.length], vertical = new int[input.length];
        int[] weights = {1, 6, 15, 20, 15, 6, 1};
        for (int axis = 0; axis < 2; axis++) {
            int[] from = axis == 0 ? input : horizontal, to = axis == 0 ? horizontal : vertical;
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                int red = 0, green = 0, blue = 0;
                for (int offset = -3; offset <= 3; offset++) {
                    int xx = axis == 0 ? Math.max(0, Math.min(width - 1, x + offset)) : x;
                    int yy = axis == 0 ? y : Math.max(0, Math.min(height - 1, y + offset));
                    int pixel = from[yy * width + xx], weight = weights[offset + 3];
                    red += ((pixel >>> 16) & 255) * weight; green += ((pixel >>> 8) & 255) * weight; blue += (pixel & 255) * weight;
                }
                to[y * width + x] = 0xff000000 | ((red + 32) / 64) << 16 | ((green + 32) / 64) << 8 | ((blue + 32) / 64);
            }
        }
        image.setRGB(0, 0, width, height, vertical, 0, width);
    }

    /** Places one to four original images in row order; unused slots stay white. */
    public static byte[] compose(List<byte[]> originals) {
        if (originals == null) throw invalid("缺少原详情图片");
        requireCount(originals.size());
        // Check all byte payloads before allocating the destination or decoding any input.
        originals.forEach(CommerceModelDetailGridComposer::requireBytes);
        var grid = new BufferedImage(GRID_SIZE, GRID_SIZE, BufferedImage.TYPE_INT_RGB);
        var graphics = grid.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, GRID_SIZE, GRID_SIZE);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
            for (int index = 0; index < originals.size(); index++) {
                var source = decode(originals.get(index), false);
                try {
                    double scale = Math.min((double) CELL_SIZE / source.getWidth(), (double) CELL_SIZE / source.getHeight());
                    int width = Math.max(1, Math.min(CELL_SIZE, (int) Math.round(source.getWidth() * scale)));
                    int height = Math.max(1, Math.min(CELL_SIZE, (int) Math.round(source.getHeight() * scale)));
                    int x = (index % 2) * CELL_SIZE + (CELL_SIZE - width) / 2;
                    int y = (index / 2) * CELL_SIZE + (CELL_SIZE - height) / 2;
                    graphics.drawImage(source, x, y, width, height, null);
                } finally {
                    source.flush();
                }
            }
            return archiveSized(grid, png(grid));
        } finally {
            graphics.dispose();
            grid.flush();
        }
    }

    /** Keeps valid small provider bytes intact; oversized grids retain dimensions at bounded JPEG quality. */
    public static byte[] normalizeGenerated(byte[] generatedGrid) {
        var grid = decode(generatedGrid, true);
        try {
            return archiveSized(grid, generatedGrid);
        } finally {
            grid.flush();
        }
    }

    /** Lossless crops of a generated grid: top-left, top-right, bottom-left, bottom-right. */
    public static List<byte[]> split(byte[] generatedGrid, int actualCount) {
        requireCount(actualCount);
        var grid = decode(generatedGrid, true);
        try {
            int cellSize = grid.getWidth() / 2;
            var outputs = new ArrayList<byte[]>(actualCount);
            for (int index = 0; index < actualCount; index++) {
                var cell = grid.getSubimage((index % 2) * cellSize, (index / 2) * cellSize, cellSize, cellSize);
                outputs.add(png(cell));
            }
            return List.copyOf(outputs);
        } finally {
            grid.flush();
        }
    }

    private static void requireCount(int count) {
        if (count < 1 || count > MAX_IMAGES) throw invalid("每组详情图片数量须为1至4张");
    }

    private static void requireBytes(byte[] bytes) {
        if (bytes == null || bytes.length < 12 || bytes.length > MAX_BYTES)
            throw invalid("详情图片缺失或文件大小无效（上限32MiB）");
    }

    private static BufferedImage decode(byte[] bytes, boolean generated) {
        requireBytes(bytes);
        // Memory stream avoids ImageIO's global disk-cache settings and temporary files.
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw invalid("详情图片须为有效的PNG、JPEG或WEBP");
            var reader = readers.next();
            try {
                if (!FORMATS.contains(reader.getFormatName().toLowerCase(Locale.ROOT)))
                    throw invalid("详情图片仅支持PNG、JPEG或WEBP");
                reader.setInput(input, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                // Inspect the header before decompression; cast before multiplying to avoid overflow.
                if (width < 1 || height < 1 || width > MAX_DIMENSION || height > MAX_DIMENSION
                        || (long) width * height > MAX_PIXELS) throw invalid("详情图片像素超出处理范围");
                if (generated && (width != height || width % 2 != 0 || width < MIN_GENERATED_CELL_SIZE * 2))
                    throw invalid("生成详情图须为偶数边长的正方形2×2图，每格至少512像素");
                var image = reader.read(0);
                if (image == null || image.getWidth() != width || image.getHeight() != height)
                    throw invalid("详情图片无法完整解码");
                return image;
            } finally {
                reader.dispose();
            }
        } catch (IOException error) {
            throw new IllegalArgumentException("详情图片无法解码", error);
        } catch (RuntimeException error) {
            if (error instanceof IllegalArgumentException invalid) throw invalid;
            throw new IllegalArgumentException("详情图片无法解码", error);
        }
    }

    private static byte[] png(BufferedImage image) {
        try (var output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "png", output)) throw new IllegalStateException("详情图片PNG编码器不可用");
            return output.toByteArray();
        } catch (IOException error) {
            throw new IllegalStateException("详情图片PNG编码失败", error);
        }
    }

    private static byte[] archiveSized(BufferedImage image, byte[] original) {
        if (original.length <= ARCHIVE_MAX_BYTES) return original;
        var rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        var graphics = rgb.createGraphics();
        try {
            graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
            graphics.drawImage(image, 0, 0, null);
        } finally { graphics.dispose(); }
        try {
            for (float quality : new float[]{.94f, .90f, .85f, .80f, .70f, .60f, .50f, .40f, .30f, .20f, .10f, .05f}) {
                var writers = ImageIO.getImageWritersByFormatName("jpeg");
                if (!writers.hasNext()) throw new IllegalStateException("详情图片JPEG编码器不可用");
                var writer = writers.next();
                try (var bytes = new ByteArrayOutputStream(); var output = new MemoryCacheImageOutputStream(bytes)) {
                    var options = writer.getDefaultWriteParam();
                    options.setCompressionMode(ImageWriteParam.MODE_EXPLICIT); options.setCompressionQuality(quality);
                    writer.setOutput(output); writer.write(null, new IIOImage(rgb, null, null), options); output.flush();
                    if (bytes.size() <= ARCHIVE_MAX_BYTES) return bytes.toByteArray();
                } catch (IOException error) {
                    throw new IllegalStateException("详情图片JPEG编码失败", error);
                } finally { writer.dispose(); }
            }
            throw new IllegalArgumentException("详情图片在保持原尺寸时仍超过8MiB归档限制");
        } finally { rgb.flush(); }
    }

    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
