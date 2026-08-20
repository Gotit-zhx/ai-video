package com.yingdao.aicommerce.video.application.provider;

public class VideoProviderException extends RuntimeException {
    private final boolean safeToFailover;
    private final String requestJson;
    private final String responseJson;

    public VideoProviderException(String message, boolean safeToFailover) {
        this(message, safeToFailover, null, null);
    }

    public VideoProviderException(String message, boolean safeToFailover, String requestJson, String responseJson) {
        super(message);
        this.safeToFailover = safeToFailover;
        this.requestJson = requestJson;
        this.responseJson = responseJson;
    }

    public boolean isSafeToFailover() { return safeToFailover; }
    public String getRequestJson() { return requestJson; }
    public String getResponseJson() { return responseJson; }

    public VideoProviderException withRequest(String value) {
        return new VideoProviderException(getMessage(), safeToFailover,
                value, responseJson);
    }
}
