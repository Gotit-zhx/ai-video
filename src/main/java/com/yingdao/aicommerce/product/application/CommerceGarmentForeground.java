package com.yingdao.aicommerce.product.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import java.util.*;

/** Offline alpha evidence; never authorizes geometry, publishing, or invented RGB values. */
@Component
public class CommerceGarmentForeground {
    public static final String VERSION="u2netp-cloth-evidence-v2";
    private final RestClient client;
    private final boolean enabled;
    public CommerceGarmentForeground(
            @Value("${app.product-capture.watermark.local-url:http://watermark-detector:8091}") String url,
            @Value("${app.product-capture.watermark.local-token:}") String token) {
        enabled=token!=null&&token.length()>=24;
        var factory=new SimpleClientHttpRequestFactory();factory.setConnectTimeout(5000);factory.setReadTimeout(45000);
        client=RestClient.builder().baseUrl(url).requestFactory(factory).defaultHeader("X-Internal-Token",Objects.toString(token,"")).build();
    }
    public byte[] isolate(byte[] source) {
        if(!enabled)throw new IllegalArgumentException("本地衣服取色服务未配置，无法分离场景背景；已有素材保留");
        try {
            // Decode JPEG once so native decoder rounding cannot alter color evidence.
            var normalized=new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(CommerceMockupComposer.decode(source),"png",normalized);
            byte[] input=normalized.toByteArray();
            byte[] body=new tools.jackson.databind.ObjectMapper().writeValueAsBytes(Map.of("data",Base64.getEncoder().encodeToString(input)));
            var response=client.post().uri("/garment-foreground").contentType(MediaType.APPLICATION_JSON).contentLength(body.length)
                    .body(body).retrieve().body(Map.class);
            if(response==null||!CommerceImageArchive.sha(input).equals(response.get("sourceSha256"))||!VERSION.equals(response.get("algorithm")))
                throw new IllegalArgumentException("本地衣服遮罩与原图不一致");
            byte[] cutout=Base64.getDecoder().decode((String)response.get("data"));
            validatePixels(source,cutout);
            return cutout;
        } catch(java.io.IOException | RuntimeException e) {throw new IllegalArgumentException("本地衣服取色未完成："+(e instanceof IllegalArgumentException?e.getMessage():"抠图服务不可用，请恢复服务后重试；不会重复调用生图模型"),e);}
    }
    static void validatePixels(byte[] source,byte[] cutout) {
        var a=CommerceMockupComposer.decode(source);var b=CommerceMockupComposer.decode(cutout);
        if(a.getWidth()!=b.getWidth()||a.getHeight()!=b.getHeight())throw new IllegalArgumentException("取色遮罩尺寸不匹配");
        for(int y=0;y<a.getHeight();y++)for(int x=0;x<a.getWidth();x++)
            if((a.getRGB(x,y)&0xffffff)!=(b.getRGB(x,y)&0xffffff))throw new IllegalArgumentException("抠图不得改变原图布色");
    }
}
