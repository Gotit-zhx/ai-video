package com.yingdao.aicommerce.shop.infrastructure;

public class MarketplaceApiException extends RuntimeException {
    public MarketplaceApiException(String message) {
        super(message);
    }

    public MarketplaceApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
