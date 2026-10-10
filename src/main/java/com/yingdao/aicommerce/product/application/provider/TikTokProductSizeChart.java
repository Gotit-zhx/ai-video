package com.yingdao.aicommerce.product.application.provider;

import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.product.domain.ProductCapture;
import com.yingdao.aicommerce.product.domain.ProductCaptureImage;
import com.yingdao.aicommerce.shop.application.MarketplaceAccess;
import com.yingdao.aicommerce.shop.infrastructure.MarketplaceImageDownloader;
import com.yingdao.aicommerce.shop.infrastructure.TikTokShopOfficialClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Publishes a verified source chart or renders actual, labeled source measurements without inventing values. */
@Component
public class TikTokProductSizeChart {
    private static final String SIZE = "(?:XXXL|XXL|XXS|XL|XS|S|M|L|[2-9]XL|均码|均碼)";
    private static final String UNIT = "(?:cm|mm|inches|inch|in|厘米|公分|毫米)";
    private static final String MEASUREMENT = "[0-9]+(?:[.,][0-9]+)?(?:\\s*[-–~至]\\s*[0-9]+(?:[.,][0-9]+)?)?\\s*" + UNIT;
    private static final Pattern ROW = Pattern.compile("(?<![A-Za-z0-9])(" + SIZE + ")\\s*(?:[（(][^()（）]{1,45}[）)])?\\s*(?:[,，:：|]?\\s*(?:(?:衣长|衣長|胸围|胸圍|肩宽|肩寬|袖长|袖長|腰围|腰圍|裤长|褲長|臀围|臀圍)\\s*[:：]?\\s*)?" + MEASUREMENT + "\\s*){2,12}", Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMERIC_ROW = Pattern.compile("(?<![A-Za-z0-9])(" + SIZE + ")\\s*(?:[（(][^()（）]{1,45}[）)])?\\s*(?:[,，:：|]?\\s*[0-9]+(?:\\.[0-9]+)?(?:\\s*[-–~至]\\s*[0-9]+(?:\\.[0-9]+)?)?\\s+){1,11}[0-9]+(?:\\.[0-9]+)?(?:\\s*[-–~至]\\s*[0-9]+(?:\\.[0-9]+)?)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern HEADER = Pattern.compile("(?i)(?:\\bsize(?:\\s*[（(][^()（）]{1,35}[）)])?|\\bsaiz|尺码|尺碼|尺寸)[^.。!?！？\\n]{0,260}$");
    private static final Pattern DIMENSION = Pattern.compile("(?i)length|chest|bust|waist|hips?|sleeve|shoulder|inseam|panjang|dada|bahu|pinggang|衣长|衣長|胸围|胸圍|肩宽|肩寬|袖长|袖長|腰围|腰圍|裤长|褲長|臀围|臀圍");
    private final TikTokShopOfficialClient client;
    private final MarketplaceImageDownloader downloader;
    private final ObjectMapper json;

    public TikTokProductSizeChart(TikTokShopOfficialClient client, MarketplaceImageDownloader downloader, ObjectMapper json) {
        this.client = client; this.downloader = downloader; this.json = json;
    }

    public PreparedSizeChart prepare(ProductCapture capture, List<ProductCaptureImage> images,
                                     MarketplaceAccess.Connection connection, Map<String, Object> categoryRules) {
        if (!Objects.equals(capture.getOrganizationId(), connection.shop().getOrganizationId())) throw conflict("尺码表不属于目标店铺公司");
        Map<?, ?> rules = categoryRules == null ? Map.of() : categoryRules;
        if (rules.get("data") instanceof Map<?, ?> data) rules = data;
        Map<?, ?> sizeRules = rules.get("size_chart") instanceof Map<?, ?> map ? map : Map.of();
        boolean required = Boolean.TRUE.equals(sizeRules.get("is_required"));
        if (Boolean.FALSE.equals(sizeRules.get("is_supported"))) return PreparedSizeChart.none();
        Map<String, Object> normalized = normalized(capture);
        String imageId = string(normalized.get("sizeChartImageId"));
        ProductCaptureImage selected = null;
        if (imageId != null) {
            for (ProductCaptureImage image : images == null ? List.<ProductCaptureImage>of() : images) {
                if (imageId.equals(image.getId())) { selected = image; break; }
            }
            if (selected == null) throw conflict("原商品已标注的尺码表图片不存在，请重新采集该尺码表");
        } else {
            // A semantic collector role is explicit evidence; an arbitrary DETAIL or first gallery image is not.
            for (ProductCaptureImage image : images == null ? List.<ProductCaptureImage>of() : images) {
                if ("SIZE_CHART".equalsIgnoreCase(image.getRoleName()) || explicitSizeChartLabel(image.getAltText())) {
                    if (selected != null && !Objects.equals(selected.getProcessedUrl(), image.getProcessedUrl())) {
                        throw conflict("原商品存在多张不同尺码表，请先指定对应商品的尺码表图片");
                    }
                    selected = image;
                }
            }
        }
        MarketplaceImageDownloader.ImageBinary binary;
        String sourceType;
        if (selected != null) {
            if (!Objects.equals(capture.getOrganizationId(), selected.getOrganizationId())
                    || !Objects.equals(capture.getId(), selected.getCaptureId())) throw conflict("尺码表图片不属于当前商品");
            if (!"PROCESSED".equals(selected.getStatus()) || string(selected.getProcessedUrl()) == null) {
                throw conflict("原商品尺码表图片尚未处理完成，请等待处理完成后上传");
            }
            binary = normalizeImage(downloader.downloadProductImage(selected.getProcessedUrl(), selected));
            sourceType = "SOURCE_IMAGE";
        } else {
            List<String> lines = sourceMeasurementLines(capture.getDescription());
            if (lines.isEmpty()) {
                if (required) throw conflict("目标类目必须有尺码表，但原商品没有可确认的尺码表图片或完整尺寸数据；请补采原商品尺码表后上传");
                return PreparedSizeChart.none();
            }
            binary = renderSourceMeasurements(lines);
            sourceType = "SOURCE_MEASUREMENTS";
        }
        Map<String, Object> response = client.apiMultipart("/product/" + client.apiVersion() + "/images/upload",
                connection, Map.of(), "size-chart" + binary.extension(), binary.contentType(), binary.bytes(),
                Map.of("use_case", "SIZE_CHART_IMAGE"));
        Map<?, ?> data = response.get("data") instanceof Map<?, ?> map ? map : Map.of();
        if (data.get("image") instanceof Map<?, ?> image) data = image;
        String uri = string(data.get("uri"));
        if (uri == null || uri.length() > 2048 || uri.matches(".*[\\s<>\\\"].*") || uri.contains("://")) {
            throw conflict("TikTok 尺码表上传未返回有效图片 URI，未提交商品");
        }
        return new PreparedSizeChart(Map.of("image", Map.of("uri", uri)), selected == null ? null : selected.getId(), sourceType);
    }

    static boolean explicitSizeChartLabel(String value) {
        return value != null && value.length() <= 60 && value.trim().matches(
                "(?i)^(?:size[ _-]*(?:chart|guide)|measurement[ _-]*chart|尺码表|尺碼表|尺寸表|尺码指南|尺碼指南)(?:\\s*[（(](?:cm|mm|inch|inches|厘米|英寸)[）)])?$"
        );
    }

    static List<String> sourceMeasurementLines(String description) {
        if (description == null || description.length() > 100_000) return List.of();
        String source = description.replaceAll("(?i)<(?:br\\s*/?|/?(?:p|div|tr))>", "\n")
                .replaceAll("<[^>]+>", " ").replace("&nbsp;", " ").replace("&#160;", " ");
        var rows = ROW.matcher(source);
        boolean explicitUnits = rows.find();
        if (!explicitUnits) { rows = NUMERIC_ROW.matcher(source); if (!rows.find()) return List.of(); }
        int first = rows.start();
        String before = source.substring(Math.max(0, first - 280), first).trim();
        var heading = HEADER.matcher(before);
        String header = heading.find() ? heading.group().trim() : "";
        // A header immediately above the data can span several source lines.
        if (header.isEmpty()) {
            before = before.replace('\n', ' ');
            heading = HEADER.matcher(before);
            if (heading.find()) header = heading.group().trim();
        }
        if (header.isEmpty() || (!explicitUnits && !Pattern.compile(UNIT, Pattern.CASE_INSENSITIVE).matcher(header).find())) return List.of();
        List<String> lines = new ArrayList<>();
        lines.add(header);
        HashSet<String> sizes = new HashSet<>();
        int end = first, expectedMeasurements = -1;
        do {
            String gap = source.substring(end, rows.start());
            if (!gap.matches("[\\s,，;；|]*")) break;
            if (!sizes.add(rows.group(1).toUpperCase(java.util.Locale.ROOT))) return List.of();
            if (explicitUnits) {
                var measurements = Pattern.compile(MEASUREMENT, Pattern.CASE_INSENSITIVE).matcher(rows.group());
                int count = 0; while (measurements.find()) count++;
                if (expectedMeasurements >= 0 && count != expectedMeasurements) return List.of();
                expectedMeasurements = count;
            }
            lines.add(rows.group().trim());
            end = rows.end();
            if (lines.size() > 25) return List.of();
        } while (rows.find());
        if (lines.size() < 3) return List.of();
        var dimensions = DIMENSION.matcher(String.join(" ", lines));
        HashSet<String> labels = new HashSet<>();
        while (dimensions.find()) labels.add(dimensions.group().toLowerCase(java.util.Locale.ROOT));
        if (labels.size() < 2) return List.of();
        String trailing = source.substring(end).stripLeading();
        if (Pattern.compile("^(?:" + SIZE + ")(?![A-Za-z])", Pattern.CASE_INSENSITIVE).matcher(trailing).find()) return List.of();
        if (trailing.matches("(?is)^(?:size description|measurement note|测量说明|測量說明|尺寸说明|尺寸說明).*")) {
            var note = Pattern.compile("(?s)^.{1,250}?(?:[。.!?](?=\\s|[A-Z])|$)").matcher(trailing);
            if (note.find()) lines.add(note.group().trim());
        }
        return List.copyOf(lines);
    }

    static MarketplaceImageDownloader.ImageBinary renderSourceMeasurements(List<String> sourceLines) {
        int width = 1800, padding = 90, fontSize = 36, lineHeight = 56;
        Font font = new Font(Font.SANS_SERIF, Font.PLAIN, fontSize);
        for (String line : sourceLines) if (font.canDisplayUpTo(line) >= 0) throw conflict("运行环境缺少原文尺码表所需字体，请补采原尺码表图片");
        BufferedImage measure = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        var measuring = measure.createGraphics();
        measuring.setFont(font);
        List<String> lines = new ArrayList<>();
        try {
            FontMetrics metrics = measuring.getFontMetrics();
            for (String original : sourceLines) {
                StringBuilder line = new StringBuilder();
                for (int offset = 0; offset < original.length();) {
                    int point = original.codePointAt(offset); String character = new String(Character.toChars(point));
                    if (!line.isEmpty() && metrics.stringWidth(line + character) > width - 2 * padding) {
                        lines.add(line.toString()); line.setLength(0);
                    }
                    line.append(character); offset += Character.charCount(point);
                }
                lines.add(line.toString());
            }
        } finally { measuring.dispose(); }
        int height = Math.max(1200, 2 * padding + (lines.size() + 2) * lineHeight);
        if (height > 6000) throw conflict("原文尺寸数据过长，请使用原尺码表图片");
        BufferedImage output = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = output.createGraphics();
        try {
            graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, width, height);
            graphics.setColor(Color.BLACK); graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setFont(font.deriveFont(Font.BOLD, 44f)); graphics.drawString("Size chart", padding, padding + 40);
            graphics.setFont(font); int y = padding + 2 * lineHeight;
            for (String line : lines) { graphics.drawString(line, padding, y); y += lineHeight; }
        } finally { graphics.dispose(); }
        return png(output);
    }

    static MarketplaceImageDownloader.ImageBinary normalizeImage(MarketplaceImageDownloader.ImageBinary binary) {
        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(binary.bytes()));
            if (source == null || source.getWidth() < 1 || source.getHeight() < 1) throw conflict("尺码表图片无法读取");
            if (Math.max(source.getWidth(), source.getHeight()) > 20000) throw conflict("原尺码表图片像素超过官方限制");
            double scale = Math.max(1, 1024.0 / Math.min(source.getWidth(), source.getHeight()));
            if (scale == 1) return binary;
            int width = (int) Math.ceil(source.getWidth() * scale), height = (int) Math.ceil(source.getHeight() * scale);
            if (Math.max(width, height) > 20000) throw conflict("原尺码表比例过长，无法达到官方短边 1024 像素要求");
            BufferedImage output = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            var graphics = output.createGraphics();
            try {
                graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, width, height);
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                graphics.drawImage(source, 0, 0, width, height, null);
            } finally { graphics.dispose(); }
            return png(output);
        } catch (BusinessException exception) { throw exception; }
        catch (Exception exception) { throw conflict("原尺码表图片读取失败，请重新采集该图片"); }
    }

    private static MarketplaceImageDownloader.ImageBinary png(BufferedImage image) {
        try (var bytes = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", bytes);
            if (bytes.size() > 10_000_000) throw conflict("尺码表图片超过 TikTok 10MB 上限，请压缩原图");
            return new MarketplaceImageDownloader.ImageBinary(bytes.toByteArray(), "image/png", ".png");
        } catch (java.io.IOException exception) { throw conflict("尺码表图片生成失败"); }
    }
    private Map<String, Object> normalized(ProductCapture capture) {
        if (capture.getNormalizedData() == null || capture.getNormalizedData().isBlank()) return Map.of();
        try { return json.readValue(capture.getNormalizedData(), new TypeReference<>() {}); }
        catch (Exception exception) { throw conflict("原商品尺码表标注读取失败"); }
    }
    private static String string(Object value) { return value instanceof String text && !text.isBlank() ? text.trim() : null; }
    private static BusinessException conflict(String message) { return new BusinessException(HttpStatus.CONFLICT, message); }
    public record PreparedSizeChart(Map<String, Object> payload, String sourceImageId, String sourceType) {
        public boolean present() { return payload != null && !payload.isEmpty(); }
        public static PreparedSizeChart none() { return new PreparedSizeChart(Map.of(), null, "NONE"); }
    }
}
