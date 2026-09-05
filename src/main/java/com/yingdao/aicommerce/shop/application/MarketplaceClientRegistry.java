package com.yingdao.aicommerce.shop.application;

import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.shop.domain.MarketplacePlatform;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class MarketplaceClientRegistry {
    private final Map<MarketplacePlatform, MarketplaceAccess.Client> clients;

    public MarketplaceClientRegistry(List<MarketplaceAccess.Client> clients) {
        EnumMap<MarketplacePlatform, MarketplaceAccess.Client> values =
                new EnumMap<>(MarketplacePlatform.class);
        for (MarketplaceAccess.Client client : clients) {
            if (values.put(client.platform(), client) != null) {
                throw new IllegalStateException("Duplicate marketplace client: " + client.platform());
            }
        }
        this.clients = Map.copyOf(values);
    }

    public MarketplaceAccess.Client require(MarketplacePlatform platform) {
        MarketplaceAccess.Client client = clients.get(platform);
        if (client == null || !client.configured()) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    displayName(platform) + " 官方应用尚未配置或启用");
        }
        return client;
    }

    public boolean configured(MarketplacePlatform platform) {
        MarketplaceAccess.Client client = clients.get(platform);
        return client != null && client.configured();
    }

    private String displayName(MarketplacePlatform platform) {
        return platform == MarketplacePlatform.SHOPEE ? "Shopee" : "TikTok Shop";
    }
}
