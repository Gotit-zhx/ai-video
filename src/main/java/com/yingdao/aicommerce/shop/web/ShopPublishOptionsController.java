package com.yingdao.aicommerce.shop.web;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.api.ApiResponse;
import com.yingdao.aicommerce.shop.application.ShopAuthorizationService;
import com.yingdao.aicommerce.shop.application.TikTokShopListingCapabilityResolver;
import com.yingdao.aicommerce.shop.domain.MarketplacePlatform;
import com.yingdao.aicommerce.shop.infrastructure.ShopeeOfficialClient;
import com.yingdao.aicommerce.shop.infrastructure.MarketplaceApiException;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.time.Duration;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Read-only, tenant-scoped official choices for the simplified publish form. */
@RestController
@RequestMapping("/api/v1/shops")
public class ShopPublishOptionsController implements AutoCloseable {
    private final ShopAuthorizationService shops;
    private final ShopeeOfficialClient shopee;
    private com.yingdao.aicommerce.shop.infrastructure.TikTokShopOfficialClient tiktok;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Duration readTimeout;
    @Autowired
    private com.yingdao.aicommerce.product.application.ProductListingLogisticsService listingLogistics;
    public ShopPublishOptionsController(ShopAuthorizationService shops, ShopeeOfficialClient shopee) {
        this(shops, shopee, Duration.ofSeconds(90));
    }
    @Autowired
    public ShopPublishOptionsController(ShopAuthorizationService shops, ShopeeOfficialClient shopee,
            com.yingdao.aicommerce.shop.infrastructure.TikTokShopOfficialClient tiktok) {
        this(shops, shopee, tiktok, Duration.ofSeconds(90));
    }
    ShopPublishOptionsController(ShopAuthorizationService shops, ShopeeOfficialClient shopee,
            com.yingdao.aicommerce.shop.infrastructure.TikTokShopOfficialClient tiktok, Duration readTimeout) {
        this(shops, shopee, readTimeout);
        this.tiktok = tiktok;
    }
    ShopPublishOptionsController(ShopAuthorizationService shops, ShopeeOfficialClient shopee, Duration readTimeout) {
        this.shops = shops; this.shopee = shopee; this.readTimeout = readTimeout;
    }
    @GetMapping("/{id}/publish-options")
    public ApiResponse<Map<String, Object>> options(@PathVariable String id,
            @AuthenticationPrincipal PlatformPrincipal user) {
        var connection = tiktok == null
                ? shops.connectionForPublishing(user.organizationId(), id, MarketplacePlatform.SHOPEE)
                : shops.connectionForPublishing(user.organizationId(), id);
        boolean isTikTok = tiktok != null && MarketplacePlatform.TIKTOK_SHOP.name().equals(connection.shop().getPlatform());
        // Validate tenant/shop access before either external read. Virtual workers are owned
        // by this bean; cancelling their Future interrupts the transport and its body read.
        var completed = new ExecutorCompletionService<Map.Entry<String, Object>>(executor);
        var tasks = new ArrayList<Future<Map.Entry<String, Object>>>(2);
        Future<TikTokShopListingCapabilityResolver.Capability> capabilityTask = null;
        long started = System.nanoTime();
        try {
            var result = new LinkedHashMap<String, Object>();
            if (!isTikTok && listingLogistics != null)
                result.put("listingLogistics", listingLogistics.config(user.organizationId(), id));
            if (isTikTok) {
                // Resolve before selecting local categories/warehouses. Keep capability
                // lookup within the same cancellable overall options deadline.
                capabilityTask = executor.submit(() -> new TikTokShopListingCapabilityResolver(tiktok).resolve(connection));
                long remaining = readTimeout.toNanos() - (System.nanoTime() - started);
                if (remaining <= 0) throw new TimeoutException();
                var capability = capabilityTask.get(remaining, TimeUnit.NANOSECONDS);
                result.put("listingMethod", capability.listingMethod());
                result.put("canCreateDraft", true);
                if (capability.requestId() != null) result.put("listingRulesRequestId", capability.requestId());
                if (!capability.inventoryRules().isEmpty()) result.put("inventoryRules", capability.inventoryRules());
                tasks.add(completed.submit(() -> Map.entry("categories", tiktok.apiGet(
                        "/product/202309/categories", connection.tokens().accessToken(),
                        connection.shop().getShopCipher(), Map.of("locale", "en-US", "category_version",
                                java.util.Set.of("US", "MY", "PH", "ID", "TH", "VN", "SG").contains(
                                        String.valueOf(connection.shop().getRegion()).toUpperCase()) ? "v2" : "v1"))
                        .getOrDefault("data", Map.of()))));
                tasks.add(completed.submit(() -> Map.entry("warehouses", tiktok.apiGet(
                        "/logistics/202309/warehouses", connection.tokens().accessToken(),
                        connection.shop().getShopCipher(), Map.of()).getOrDefault("data", Map.of()))));
            } else {
            tasks.add(completed.submit(() -> Map.entry("categories", shopee.shopGet(
                    "/api/v2/product/get_category", connection, Map.of("language", "en")).getOrDefault("response", Map.of()))));
            tasks.add(completed.submit(() -> Map.entry("logistics", shopee.shopGet(
                    "/api/v2/logistics/get_channel_list", connection, Map.of()).getOrDefault("response", Map.of()))));
            }
            for (int count = 0; count < 2; count++) {
                long remaining = readTimeout.toNanos() - (System.nanoTime() - started);
                var task = remaining > 0 ? completed.poll(remaining, TimeUnit.NANOSECONDS) : null;
                if (task == null) throw new MarketplaceApiException("读取店铺上传选项超时，请稍后重试",
                        new HttpTimeoutException("店铺上传选项读取超时"));
                var entry = task.get();
                result.put(entry.getKey(), entry.getValue());
            }
            return ApiResponse.ok(result);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new MarketplaceApiException("读取店铺上传选项被中断", exception);
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new MarketplaceApiException("读取店铺上传选项失败", exception.getCause());
        } catch (TimeoutException exception) {
            throw new MarketplaceApiException("跨境刊登模式未就绪：读取店铺刊登能力超时，请稍后重试",
                    new HttpTimeoutException("店铺刊登能力读取超时"));
        } finally {
            if (capabilityTask != null) capabilityTask.cancel(true);
            tasks.forEach(task -> task.cancel(true));
        }
    }
    @Override @PreDestroy public void close() { executor.shutdownNow(); }
}
