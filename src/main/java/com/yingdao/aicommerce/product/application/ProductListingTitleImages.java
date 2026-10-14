package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.product.domain.ProductCapture;
import com.yingdao.aicommerce.product.domain.ProductCaptureImage;
import com.yingdao.aicommerce.shop.infrastructure.MarketplaceImageDownloader;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;

@Component
public class ProductListingTitleImages {
    private final MarketplaceImageDownloader downloader;
    public ProductListingTitleImages(MarketplaceImageDownloader downloader) { this.downloader = downloader; }

    public List<Map<String, Object>> captured(Object raw) {
        List<Source> sources = new ArrayList<>();
        if (raw instanceof List<?> images) for (Object image : images) {
            if (image instanceof Map<?, ?> value) {
                Object url = value.get("originalUrl");
                if (url == null) url = value.get("url");
                if (url instanceof String text && !text.isBlank())
                    sources.add(new Source(text, String.valueOf(value.getOrDefault("role", null)), null));
            } else if (image instanceof String url && !url.isBlank()) sources.add(new Source(url, "GALLERY", null));
        }
        return load(sources);
    }

    public List<Map<String, Object>> stored(ProductCapture capture, List<ProductCaptureImage> images) {
        List<Source> sources = new ArrayList<>();
        images.stream().sorted(Comparator.comparingInt(ProductCaptureImage::getSortOrder)).forEach(image -> {
            if (!Objects.equals(capture.getOrganizationId(), image.getOrganizationId())
                    || !Objects.equals(capture.getId(), image.getCaptureId())) throw failed();
            String url = image.getOriginalUrl();
            // Generated drafts retain their original pixels in a tenant-bound archive.
            if (image.getProcessedUrl() != null && (image.getProcessedUrl()+"?original").equals(url)
                    && image.getProcessedUrl().startsWith(CommercePublicationDrafts.PREFIX)) url = image.getProcessedUrl();
            sources.add(new Source(url, image.getRoleName(), image));
        });
        return load(sources);
    }

    private List<Map<String, Object>> load(List<Source> sources) {
        var unique = new LinkedHashMap<String, Source>();
        sources.stream().filter(source -> source.url() != null && !"SIZE_CHART".equalsIgnoreCase(source.role()))
                .sorted(Comparator.comparingInt(source -> priority(source.role())))
                .forEach(source -> unique.putIfAbsent(source.url(), source));
        var selected = unique.values().stream().limit(4).toList();
        if (selected.isEmpty()) throw failed();
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            List<Future<Map<String, Object>>> tasks = new ArrayList<>();
            for (int index = 0; index < selected.size(); index++) {
                var source = selected.get(index);
                String role = index == 0 ? "MAIN" : source.role();
                tasks.add(executor.submit(() -> {
                    var binary = source.image() == null ? downloader.download(source.url())
                            : downloader.downloadProductImage(source.url(), source.image());
                    return Map.of("role", role, "mimeType", "image/jpeg", "data",
                            Base64.getEncoder().encodeToString(compact(binary.bytes())));
                }));
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(55);
            List<Map<String, Object>> result = new ArrayList<>();
            for (int index = 0; index < tasks.size(); index++) {
                try {
                    result.add(tasks.get(index).get(Math.max(1, deadline-System.nanoTime()), TimeUnit.NANOSECONDS));
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt(); throw failed();
                } catch (ExecutionException | TimeoutException error) {
                    // Main image evidence is mandatory. A missing secondary photo may be omitted.
                    if (index == 0) throw failed();
                }
            }
            return result;
        } finally { executor.shutdownNow(); }
    }

    static byte[] compact(byte[] bytes) throws Exception {
        BufferedImage image = CommerceMockupComposer.decode(bytes);
        double scale = Math.min(1, 1280.0 / Math.max(image.getWidth(), image.getHeight()));
        var output = new BufferedImage(Math.max(1, (int)(image.getWidth()*scale)),
                Math.max(1, (int)(image.getHeight()*scale)), BufferedImage.TYPE_INT_RGB);
        var graphics = output.createGraphics();
        try {
            graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, output.getWidth(), output.getHeight());
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.drawImage(image, 0, 0, output.getWidth(), output.getHeight(), null);
        } finally { graphics.dispose(); }
        var outputBytes = new ByteArrayOutputStream();
        if (!ImageIO.write(output, "jpeg", outputBytes)) throw failed();
        return outputBytes.toByteArray();
    }
    private static int priority(String role) {
        return switch (String.valueOf(role).toUpperCase(Locale.ROOT)) {
            case "MAIN" -> 0; case "GALLERY" -> 1; case "VARIANT" -> 2; default -> 3;
        };
    }
    private static BusinessException failed() {
        return new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "AI 标题需要可读取的商品主图，请检查采集图片后重试");
    }
    private record Source(String url, String role, ProductCaptureImage image) {}
}
