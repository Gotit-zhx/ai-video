package com.yingdao.aicommerce.product.application.provider;

import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.product.domain.ProductCapture;
import com.yingdao.aicommerce.product.domain.ProductCaptureImage;
import com.yingdao.aicommerce.shop.application.MarketplaceAccess;
import com.yingdao.aicommerce.shop.infrastructure.MarketplaceImageDownloader;
import com.yingdao.aicommerce.shop.infrastructure.TikTokShopOfficialClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Builds TikTok HTML using processed, tenant-scoped images uploaded as DESCRIPTION_IMAGE. */
@Component
public class TikTokProductDescription {
    static final int MAX_IMAGES = 30;
    static final int MAX_HTML_LENGTH = 10_000;
    static final int MAX_IMAGE_SIDE = 3999;
    private static final Pattern IMAGE_TAG = Pattern.compile("<img\\b(?:[^\\\"'<>]|\\\"[^\\\"]*\\\"|'[^']*')*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern IMAGE_SOURCE = Pattern.compile("\\bsrc\\s*=\\s*(?:\\\"([^\\\"]*)\\\"|'([^']*)'|([^\\s>]+))", Pattern.CASE_INSENSITIVE);
    private final TikTokShopOfficialClient client;
    private final MarketplaceImageDownloader downloader;

    public TikTokProductDescription(TikTokShopOfficialClient client, MarketplaceImageDownloader downloader) {
        this.client = client;
        this.downloader = downloader;
    }

    public PreparedDescription prepare(ProductCapture capture, List<ProductCaptureImage> images,
                                       MarketplaceAccess.Connection connection) {
        if (!Objects.equals(capture.getOrganizationId(), connection.shop().getOrganizationId())) {
            throw conflict("TikTok Shop 详情图片不属于当前店铺公司");
        }
        List<ProductCaptureImage> ordered = new ArrayList<>(images == null ? List.of() : images);
        ordered.sort(Comparator.comparingInt(ProductCaptureImage::getSortOrder));
        Map<String, ProductCaptureImage> byUrl = new LinkedHashMap<>();
        for (ProductCaptureImage image : ordered) {
            if (!Objects.equals(image.getOrganizationId(), capture.getOrganizationId())
                    || !Objects.equals(image.getCaptureId(), capture.getId())) {
                throw conflict("TikTok Shop 详情图片不属于当前商品");
            }
            if (image.getOriginalUrl() != null) byUrl.putIfAbsent(image.getOriginalUrl(), image);
            if (image.getProcessedUrl() != null) byUrl.putIfAbsent(image.getProcessedUrl(), image);
        }
        String html = htmlDescription(capture.getDescription());
        var matcher = IMAGE_TAG.matcher(html);
        List<Part> parts = new ArrayList<>();
        Map<String, ProductCaptureImage> selected = new LinkedHashMap<>();
        int end = 0;
        while (matcher.find()) {
            parts.add(new Part(html.substring(end, matcher.start()), null));
            var source = IMAGE_SOURCE.matcher(matcher.group());
            if (!source.find()) throw conflict("商品描述图片缺少 src，请重新采集补齐详情图片");
            String url = source.group(1) != null ? source.group(1) : source.group(2) != null ? source.group(2) : source.group(3);
            ProductCaptureImage image = byUrl.get(decodeUrl(url));
            if (image == null) throw conflict("商品描述含未采集处理的图片，请重新采集补齐详情图片后上传");
            select(image, selected, parts);
            end = matcher.end();
        }
        parts.add(new Part(html.substring(end), null));
        for (ProductCaptureImage image : ordered) {
            if (isDetail(image)) select(image, selected, parts);
        }
        if (selected.size() > MAX_IMAGES) {
            throw conflict("TikTok Shop 商品描述最多支持 30 张详情图片，当前有 " + selected.size() + " 张；请先精简详情图片");
        }
        Map<String, UploadedImage> uploads = new LinkedHashMap<>();
        int position = 0;
        for (Map.Entry<String, ProductCaptureImage> entry : selected.entrySet()) {
            ProductCaptureImage image = entry.getValue();
            PreparedBinary prepared = prepareBinary(downloader.downloadProductImage(entry.getKey(), image));
            Map<String, Object> response = client.apiMultipart("/product/" + client.apiVersion() + "/images/upload",
                    connection, Map.of(), "description-" + (++position) + prepared.binary().extension(),
                    prepared.binary().contentType(), prepared.binary().bytes(), Map.of("use_case", "DESCRIPTION_IMAGE"));
            Map<?, ?> data = response.get("data") instanceof Map<?, ?> map ? map : Map.of();
            if (data.get("image") instanceof Map<?, ?> nested) data = nested;
            String uploadedUrl = officialUrl(data.get("url"));
            int width = positiveInt(data.get("width"), prepared.width());
            int height = positiveInt(data.get("height"), prepared.height());
            if (width > MAX_IMAGE_SIDE || height > MAX_IMAGE_SIDE) throw conflict("TikTok Shop 返回的详情图片尺寸超过 3999 像素，未提交商品");
            uploads.put(entry.getKey(), new UploadedImage(uploadedUrl, width, height));
        }
        StringBuilder result = new StringBuilder();
        for (Part part : parts) {
            if (part.image() == null) result.append(part.html());
            else {
                UploadedImage image = uploads.get(part.image().getProcessedUrl());
                result.append("<img src=\"").append(escape(image.url())).append("\" width=\"")
                        .append(image.width()).append("\" height=\"").append(image.height()).append("\" />");
            }
        }
        if (result.length() > MAX_HTML_LENGTH) throw conflict("TikTok Shop 商品描述连同详情图片超过 10000 字符，未提交商品；请精简文字描述");
        return new PreparedDescription(result.toString(), uploads.size());
    }

    public static boolean isDetail(ProductCaptureImage image) {
        return image != null && "DETAIL".equalsIgnoreCase(image.getRoleName());
    }

    private void select(ProductCaptureImage image, Map<String, ProductCaptureImage> selected, List<Part> parts) {
        String processedUrl = image.getProcessedUrl();
        if (!"PROCESSED".equals(image.getStatus()) || processedUrl == null || processedUrl.isBlank()) {
            throw conflict("详情图片尚未处理完成，请等待图片处理完成后上传");
        }
        if (selected.putIfAbsent(processedUrl, image) == null) parts.add(new Part(null, image));
    }

    static PreparedBinary prepareBinary(MarketplaceImageDownloader.ImageBinary binary) {
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(binary.bytes()))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw conflict("详情图片无法解码，请重新处理该图片");
            var reader = readers.next();
            try {
                reader.setInput(input);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 100 || height < 100 || width > 20000 || height > 20000) {
                    throw conflict("TikTok Shop 详情图片尺寸必须为 100–20000 像素，请更换该图片");
                }
                if (width <= MAX_IMAGE_SIDE && height <= MAX_IMAGE_SIDE) return new PreparedBinary(binary, width, height);
                double scale = (double) MAX_IMAGE_SIDE / Math.max(width, height);
                int outputWidth = Math.max(1, (int) Math.floor(width * scale));
                int outputHeight = Math.max(1, (int) Math.floor(height * scale));
                if (Math.min(outputWidth, outputHeight) < 100) throw conflict("详情图片过长，缩放后宽度不足 100 像素；请将长图分段后上传");
                var parameters = reader.getDefaultReadParam();
                int sample = Math.max(1, Math.max(width, height) / MAX_IMAGE_SIDE);
                parameters.setSourceSubsampling(sample, sample, 0, 0);
                BufferedImage original = reader.read(0, parameters);
                BufferedImage resized = new BufferedImage(outputWidth, outputHeight, BufferedImage.TYPE_INT_ARGB);
                var graphics = resized.createGraphics();
                try {
                    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                    graphics.drawImage(original, 0, 0, outputWidth, outputHeight, null);
                } finally { graphics.dispose(); }
                try (var bytes = new ByteArrayOutputStream()) {
                    ImageIO.write(resized, "png", bytes);
                    byte[] encoded = bytes.toByteArray();
                    if (encoded.length <= 10 * 1024 * 1024) {
                        return new PreparedBinary(new MarketplaceImageDownloader.ImageBinary(encoded, "image/png", ".png"), outputWidth, outputHeight);
                    }
                    // Long photographic details may expand beyond the upload limit when converted to PNG.
                    BufferedImage opaque = new BufferedImage(outputWidth, outputHeight, BufferedImage.TYPE_INT_RGB);
                    var canvas = opaque.createGraphics();
                    try {
                        canvas.setColor(java.awt.Color.WHITE); canvas.fillRect(0, 0, outputWidth, outputHeight);
                        canvas.drawImage(resized, 0, 0, null);
                    } finally { canvas.dispose(); }
                    bytes.reset();
                    ImageIO.write(opaque, "jpeg", bytes);
                    if (bytes.size() > 10 * 1024 * 1024) throw conflict("详情图片缩放后仍超过 10MB，请压缩该图片后上传");
                    return new PreparedBinary(new MarketplaceImageDownloader.ImageBinary(bytes.toByteArray(), "image/jpeg", ".jpg"), outputWidth, outputHeight);
                }
            } finally { reader.dispose(); }
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw conflict("详情图片读取或缩放失败，请重新处理该图片");
        }
    }

    private static String officialUrl(Object value) {
        String text = value instanceof String string ? string.trim() : "";
        try {
            URI uri = URI.create(text);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
                throw new IllegalArgumentException();
            }
            return text;
        } catch (IllegalArgumentException exception) {
            throw conflict("TikTok Shop 详情图片上传响应缺少可用的图片 URL，未提交商品");
        }
    }

    private static int positiveInt(Object value, int fallback) {
        try { int parsed = Integer.parseInt(String.valueOf(value)); return parsed > 0 ? parsed : fallback; }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private static String htmlDescription(String value) {
        String source = value == null ? "" : value.trim();
        String html = source.matches("(?s).*<[^>]+>.*") ? source : "<p>" + escape(source).replace("\n", "<br>") + "</p>";
        if (html.length() > MAX_HTML_LENGTH) throw conflict("TikTok Shop 商品描述超过 10000 字符，请精简文字描述");
        return html;
    }

    private static String decodeUrl(String value) {
        return value.replace("&amp;", "&").replace("&#38;", "&").replace("&#x26;", "&").trim();
    }
    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
    private static BusinessException conflict(String message) { return new BusinessException(HttpStatus.CONFLICT, message); }
    private record Part(String html, ProductCaptureImage image) {}
    private record UploadedImage(String url, int width, int height) {}
    record PreparedBinary(MarketplaceImageDownloader.ImageBinary binary, int width, int height) {}
    public record PreparedDescription(String html, int imageCount) {}
}
