package com.yingdao.aicommerce.video.application.provider;

public class VideoProviderException extends RuntimeException {
    private final boolean safeToFailover;

    public VideoProviderException(String message, boolean safeToFailover) {
        super(message);
        this.safeToFailover = safeToFailover;
    }

    public boolean isSafeToFailover() { return safeToFailover; }
}
