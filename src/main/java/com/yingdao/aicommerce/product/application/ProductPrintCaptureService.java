package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.product.domain.ProductCaptureImage;
import com.yingdao.aicommerce.shop.infrastructure.MarketplaceApiException;
import com.yingdao.aicommerce.shop.infrastructure.MarketplaceImageDownloader;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Base64;
import java.util.List;
import java.util.Objects;

@Service
public class ProductPrintCaptureService {
    public record Image(String id, String url) {}
    public record Lookup(String captureId, String sourceUrl, String title, List<Image> images) {}

    private final ProductCaptureService captures;
    private final SafeProductImageUrl imageUrls;
    private final CommerceCaptureSourceArchiveService sourceArchives;
    private final CommerceImageArchive archive;
    private final MarketplaceImageDownloader downloader;
    private final CommerceSampleService samples;

    public ProductPrintCaptureService(ProductCaptureService captures, SafeProductImageUrl imageUrls,
                                      CommerceCaptureSourceArchiveService sourceArchives, CommerceImageArchive archive,
                                      MarketplaceImageDownloader downloader, CommerceSampleService samples) {
        this.captures = captures;
        this.imageUrls = imageUrls;
        this.sourceArchives = sourceArchives;
        this.archive = archive;
        this.downloader = downloader;
        this.samples = samples;
    }

    public Lookup lookup(String url, PlatformPrincipal user) {
        requireEdit(user);
        return lookupView(captures.findPrintSource(url, user));
    }

    public List<ProductCaptureService.PrintSourceSummary> recent(PlatformPrincipal user) {
        requireEdit(user);
        return captures.recentPrintSources(user);
    }

    public Lookup fromCapture(String id, PlatformPrincipal user) {
        requireEdit(user);
        return lookupView(captures.getPrintSource(id, user));
    }

    private Lookup lookupView(ProductCaptureService.PrintSource source) {
        var images = source.images().stream().map(this::displayImage).filter(Objects::nonNull).toList();
        return new Lookup(source.capture().getId(), source.capture().getSourceUrl(), source.capture().getTitle(), images);
    }

    public CommerceSampleService.View extract(String captureId, String imageId, boolean confirmed, String key,
                                               PlatformPrincipal user) {
        requireEdit(user);
        if (!confirmed) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "请确认本次印花提取会调用图片模型");
        }
        var source = captures.getPrintSource(captureId, user);
        var image = source.images().stream().filter(candidate -> candidate.getId().equals(imageId))
                .map(this::displayImage).filter(Objects::nonNull).findFirst()
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "找不到所选商品图片"));
        return samples.extractCapturePrint(key, captureId, image.id(), () -> {
            String archived = sourceArchives.find(captureId, image.id(), source.capture().getVersion(), user);
            if (archived != null) {
                return Base64.getDecoder().decode(archive.read(user.organizationId(), archived).dataBase64());
            }
            try {
                return downloader.download(image.url()).bytes();
            } catch (MarketplaceApiException unavailable) {
                throw new BusinessException(HttpStatus.BAD_GATEWAY, "商品原图读取失败，请更换商品图片后重试");
            }
        }, user);
    }

    private Image displayImage(ProductCaptureImage image) {
        if ("SIZE_CHART".equals(image.getRoleName())) return null;
        try {
            return new Image(image.getId(), imageUrls.validate(image.getOriginalUrl()));
        } catch (BusinessException invalid) {
            return null;
        }
    }

    private static void requireEdit(PlatformPrincipal user) {
        if (user == null) throw new BusinessException(HttpStatus.UNAUTHORIZED, "请先登录");
        if (user.organizationId() == null || user.organizationId().isBlank()
                || !user.permissionCodes().contains("product:edit")) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "缺少印花采集权限");
        }
    }
}
