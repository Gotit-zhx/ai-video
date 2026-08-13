package com.yingdao.aicommerce.video.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class VideoReferenceLinkService {
    private final String publicBaseUrl;

    public VideoReferenceLinkService(
            @Value("${app.public-base-url:http://localhost:8080}") String publicBaseUrl) {
        this.publicBaseUrl = publicBaseUrl.replaceAll("/+$", "");
    }

    public String asset(String assetId) { return link("asset", assetId); }
    public String deliveryAsset(String assetId) { return link("delivery-asset", assetId); }
    public String source(String itemId) { return link("source", itemId); }
    public String scriptSource(String scriptId) { return link("script-source", scriptId); }
    public String taskAudio(String taskId) { return link("task-audio", taskId); }

    public boolean isPubliclyReachable() {
        String value = publicBaseUrl.toLowerCase();
        return value.startsWith("https://") && !value.contains("localhost") && !value.contains("127.0.0.1");
    }

    private String link(String kind, String id) {
        return publicBaseUrl + "/api/public/v1/video-references/" + kind + "/" + id;
    }
}
