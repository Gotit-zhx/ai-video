package com.yingdao.aicommerce.shop.infrastructure;

import com.yingdao.aicommerce.product.application.SafeProductImageUrl;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;

@Component
public class MarketplaceImageDownloader {
    private static final int MAX_BYTES = 10 * 1024 * 1024;
    private final SafeProductImageUrl validator;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public MarketplaceImageDownloader(SafeProductImageUrl validator) {
        this.validator = validator;
    }

    public ImageBinary download(String url) {
        URI uri = URI.create(validator.validate(url));
        rejectPrivateResolution(uri.getHost());
        try {
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(45)).GET().build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new MarketplaceApiException("读取待发布商品图失败，HTTP " + response.statusCode());
            }
            String contentType = response.headers().firstValue("Content-Type")
                    .orElse("application/octet-stream").split(";", 2)[0].toLowerCase(Locale.ROOT);
            if (!contentType.startsWith("image/")) {
                throw new MarketplaceApiException("待发布地址返回的不是图片");
            }
            try (InputStream input = response.body(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int total = 0;
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    total += read;
                    if (total > MAX_BYTES) throw new MarketplaceApiException("单张商品图不能超过 10 MB");
                    out.write(buffer, 0, read);
                }
                return new ImageBinary(out.toByteArray(), contentType, extension(contentType));
            }
        } catch (MarketplaceApiException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new MarketplaceApiException("读取待发布商品图被中断", exception);
        } catch (Exception exception) {
            throw new MarketplaceApiException("读取待发布商品图失败", exception);
        }
    }

    private void rejectPrivateResolution(String host) {
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                        || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                        || address.isMulticastAddress()) {
                    throw new MarketplaceApiException("待发布商品图不能指向本机或内网");
                }
            }
        } catch (MarketplaceApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new MarketplaceApiException("无法验证待发布商品图域名", exception);
        }
    }

    private String extension(String contentType) {
        return switch (contentType) {
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            default -> ".jpg";
        };
    }

    public record ImageBinary(byte[] bytes, String contentType, String extension) {}
}
