package com.yingdao.aicommerce.shop.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;

@Component
public class ShopIntegrationSettings {
    private final Duration stateTtl;
    private final Duration tokenRefreshSkew;
    private final URI callbackBaseUrl;
    private final URI frontendBaseUrl;
    private final boolean livePublishingEnabled;
    private final String publishingProvider;

    public ShopIntegrationSettings(
            @Value("${app.shop-auth.state-ttl:1h}") Duration stateTtl,
            @Value("${app.shop-auth.token-refresh-skew:5m}") Duration tokenRefreshSkew,
            @Value("${app.shop-auth.callback-base-url}") URI callbackBaseUrl,
            @Value("${app.shop-auth.frontend-base-url}") URI frontendBaseUrl,
            @Value("${app.shop-auth.live-publishing-enabled:false}") boolean livePublishingEnabled,
            @Value("${app.product-capture.publishing.provider:DRY_RUN}") String publishingProvider
    ) {
        this.stateTtl = stateTtl;
        this.tokenRefreshSkew = tokenRefreshSkew;
        this.callbackBaseUrl = withoutTrailingSlash(callbackBaseUrl);
        this.frontendBaseUrl = withoutTrailingSlash(frontendBaseUrl);
        this.livePublishingEnabled = livePublishingEnabled;
        this.publishingProvider = publishingProvider == null ? "DRY_RUN" : publishingProvider.trim();
    }

    public Duration stateTtl() { return stateTtl; }
    public Duration tokenRefreshSkew() { return tokenRefreshSkew; }
    public URI callbackBaseUrl() { return callbackBaseUrl; }
    public URI frontendBaseUrl() { return frontendBaseUrl; }
    public boolean livePublishingEnabled() { return livePublishingEnabled; }
    public String publishingProvider() { return publishingProvider; }
    public boolean effectiveLivePublishing() {
        return livePublishingEnabled && !"DRY_RUN".equalsIgnoreCase(publishingProvider);
    }

    private static URI withoutTrailingSlash(URI value) {
        String text = value.toString();
        while (text.endsWith("/")) text = text.substring(0, text.length() - 1);
        return URI.create(text);
    }
}
