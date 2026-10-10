package com.yingdao.aicommerce.product.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
public class LocalWatermarkDetector {
    private final boolean enabled;
    private final RestClient client;
    public LocalWatermarkDetector(
            @Value("${app.product-capture.watermark.local-enabled:false}") boolean enabled,
            @Value("${app.product-capture.watermark.local-url:http://watermark-detector:8091}") String url,
            @Value("${app.product-capture.watermark.local-token:}") String token) {
        this.enabled=enabled;
        if(enabled && token.length()<24)throw new IllegalArgumentException("本地水印检测服务缺少内部凭证");
        var factory=new SimpleClientHttpRequestFactory();factory.setConnectTimeout(5000);factory.setReadTimeout(120000);
        client=RestClient.builder().baseUrl(url).requestFactory(factory).defaultHeader("X-Internal-Token",token).build();
    }
    public boolean enabled(){return enabled;}
    public Map<String,Object> detect(byte[] original){
        // Pre-encode to send Content-Length: Python's bounded image endpoint does not
        // consume HTTP chunk framing. Base64 contains no JSON escaping characters.
        byte[] request=("{\"data\":\""+Base64.getEncoder().encodeToString(original)+"\"}").getBytes(StandardCharsets.UTF_8);
        var result=client.post().uri("/detect").contentType(MediaType.APPLICATION_JSON)
                .contentLength(request.length).body(request).retrieve().body(Map.class);
        validate(result,CommerceImageArchive.sha(original));
        return result;
    }
    public byte[] compose(byte[] source,byte[] candidate,List<CommerceArtworkLocalEdit.Region> regions){
        byte[] normalized;
        try(var out=new java.io.ByteArrayOutputStream()){
            javax.imageio.ImageIO.write(CommerceMockupComposer.decode(source),"png",out);normalized=out.toByteArray();
        }catch(java.io.IOException e){throw new IllegalStateException("原图标准化失败",e);}
        byte[] request=new tools.jackson.databind.ObjectMapper().writeValueAsBytes(Map.of("source",Base64.getEncoder().encodeToString(normalized),"candidate",Base64.getEncoder().encodeToString(candidate),"regions",regions));
        var response=client.post().uri("/compose").contentType(MediaType.APPLICATION_JSON).contentLength(request.length).body(request).retrieve().body(Map.class);
        if(response==null||!CommerceImageArchive.sha(normalized).equals(response.get("sourceSha256"))||!CommerceImageArchive.sha(candidate).equals(response.get("candidateSha256"))||!(response.get("data") instanceof String data))throw new IllegalStateException("本地合成来源校验失败");
        byte[] output=Base64.getDecoder().decode(data);
        if(!CommerceImageArchive.sha(output).equals(response.get("outputSha256")))throw new IllegalStateException("本地合成内容校验失败");
        ProductWatermarkLocalEdit.verifyOutside(source,output,regions);
        return output;
    }
    static void validate(Map<String,Object> result,String sourceHash){
        if(result==null || !Objects.equals(sourceHash,result.get("sourceSha256"))
                || !(result.get("modelSha256") instanceof String modelHash) || !modelHash.matches("[a-f0-9]{64}")
                || !(result.get("detections") instanceof List<?> boxes)
                || !List.of("NO_CANDIDATE","SUSPECTED").contains(result.get("status")))
            throw new IllegalStateException("本地水印检测返回了无效结果");
        if("NO_CANDIDATE".equals(result.get("status"))!=boxes.isEmpty())
            throw new IllegalStateException("本地水印判定与检测框不一致");
        for(Object value:boxes){
            if(!(value instanceof Map<?,?> box))throw new IllegalStateException("本地水印检测框无效");
            double x=number(box.get("x")),y=number(box.get("y")),w=number(box.get("width")),h=number(box.get("height")),confidence=number(box.get("confidence"));
            if(x<0||y<0||w<=0||h<=0||x+w>1.000001||y+h>1.000001||confidence<0||confidence>1)
                throw new IllegalStateException("本地水印检测框超出原图");
        }
    }
    static boolean confirmedAbsent(Map<String,Object> result,String sourceHash){
        validate(result,sourceHash);
        return "NO_CANDIDATE".equals(result.get("status"))
                && "USER_CONFIRMED_EXACT_IMAGE".equals(result.get("absenceBasis"))
                && sourceHash.equals(result.get("confirmedAbsentSourceSha256"))
                && Boolean.TRUE.equals(result.get("canAutoPass"));
    }
    private static double number(Object value){
        if(value instanceof Number n && Double.isFinite(n.doubleValue()))return n.doubleValue();
        throw new IllegalStateException("本地水印检测缺少坐标或置信度");
    }
}
