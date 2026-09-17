package com.yingdao.aicommerce.product.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.ObjectMapper;
import java.util.*;

/** Local CPU processing only. Never invokes an image-generation provider. */
@Component
public class CommerceDrapeImageProcessor {
    static final String VERSION="drape-reuse-v1";
    private final RestClient client;
    private final boolean enabled;
    public CommerceDrapeImageProcessor(
            @Value("${app.product-capture.watermark.local-url:http://watermark-detector:8091}") String url,
            @Value("${app.product-capture.watermark.local-token:}") String token) {
        enabled=token!=null&&token.length()>=24;
        var factory=new SimpleClientHttpRequestFactory();factory.setConnectTimeout(5000);factory.setReadTimeout(90000);
        client=RestClient.builder().baseUrl(url).requestFactory(factory).defaultHeader("X-Internal-Token",Objects.toString(token,"")).build();
    }
    Map<String,Object> prepare(byte[] master,byte[] guide,byte[] uv,byte[] artwork,List<String> ids) {
        var result=post("/drape-prepare",Map.of("master",encoded(master),"guide",encoded(guide),"uv",encoded(uv),"artwork",encoded(artwork),"printIds",ids));
        if(!VERSION.equals(result.get("algorithm"))||!CommerceImageArchive.sha(master).equals(result.get("sourceSha256"))
                ||!CommerceImageArchive.sha(guide).equals(result.get("guideSha256"))||!CommerceImageArchive.sha(uv).equals(result.get("uvSha256"))
                ||!CommerceImageArchive.sha(artwork).equals(result.get("artworkSha256")))
            throw new IllegalArgumentException("搭放定位结果与固定参考图不一致，未重新生图");
        requirePassed(result);
        byte[] blank=decoded(result.get("blank")),alpha=decoded(result.get("alpha"));
        sameDimensions(master,blank);sameDimensions(master,alpha);
        if(!(result.get("mapping") instanceof Map<?,?> mapping)||!VERSION.equals(mapping.get("algorithm"))
                ||!CommerceImageArchive.sha(master).equals(mapping.get("sourceSha256"))||!CommerceImageArchive.sha(guide).equals(mapping.get("guideSha256"))
                ||!CommerceImageArchive.sha(uv).equals(mapping.get("uvSha256"))||!CommerceImageArchive.sha(artwork).equals(mapping.get("artworkSha256"))
                ||!CommerceImageArchive.sha(blank).equals(mapping.get("blankSha256"))||!CommerceImageArchive.sha(alpha).equals(mapping.get("alphaSha256")))
            throw new IllegalArgumentException("搭放定位缺少与固定输入一致的映射");
        return result;
    }
    Map<String,Object> render(byte[] master,byte[] blank,byte[] alpha,Object mapping,byte[] artwork,String rgb,boolean original) {
        var body=new LinkedHashMap<String,Object>();body.put("master",encoded(master));body.put("blank",encoded(blank));body.put("alpha",encoded(alpha));
        body.put("mapping",mapping);body.put("artwork",encoded(artwork));body.put("targetRgb",rgb);body.put("originalColor",original);
        var result=post("/drape-render",body);
        requirePassed(result);
        var check=(Map<?,?>)result.get("report");
        byte[] output=decoded(result.get("data"));
        if(!VERSION.equals(result.get("algorithm"))||!CommerceImageArchive.sha(master).equals(check.get("sourceSha256"))
                ||!CommerceImageArchive.sha(blank).equals(check.get("blankSha256"))||!CommerceImageArchive.sha(alpha).equals(check.get("alphaSha256"))
                ||!CommerceImageArchive.sha(artwork).equals(check.get("artworkSha256"))||!CommerceImageArchive.sha(output).equals(check.get("outputSha256"))
                ||!Boolean.valueOf(original).equals(check.get("originalColor"))||(!original&&!rgb.equalsIgnoreCase(Objects.toString(check.get("targetRgb"),""))))
            throw new IllegalArgumentException("搭放合成结果与固定母图、印花或SKU颜色不一致");
        sameDimensions(master,output);
        return result;
    }
    private Map<String,Object> post(String path,Map<String,Object> body) {
        if(!enabled)throw new IllegalArgumentException("本地搭放处理服务未配置；母图已保留，不会再次生图");
        try {
            byte[] encoded=new ObjectMapper().writeValueAsBytes(body);
            @SuppressWarnings("unchecked") var result=(Map<String,Object>)client.post().uri(path).contentType(MediaType.APPLICATION_JSON).contentLength(encoded.length).body(encoded).retrieve().body(Map.class);
            if(result==null)throw new IllegalArgumentException("本地搭放处理没有返回结果");
            return result;
        } catch(RestClientResponseException error) {
            String reason="本地搭放检查未通过";
            try {var data=new ObjectMapper().readTree(error.getResponseBodyAsString());String code=data.path("code").asText("");reason=stopReason(code);}catch(RuntimeException ignored){}
            throw new IllegalArgumentException(reason+"；保留母图与印花，恢复仅重做本地处理，不追加生图",error);
        }
    }
    static String stopReason(String code) {
        return switch(Objects.toString(code,"")) {
            case "FEATURE_TRACKING_FAILED" -> "母图中的印花与参考图未能可靠匹配，暂时无法确定替换位置";
            case "GUIDE_ARTWORK_MISMATCH" -> "高清印花与已保存的位置参考不一致，已停止使用错误图案定位";
            case "MAPPING_CHECKSUM_INVALID" -> "已保存的印花定位数据未通过完整性检查，需修复本地映射后继续";
            case "FEATURE_TRACKING_UNSTABLE", "PRINT_GEOMETRY_UNCERTAIN" -> "不同尺度的印花定位结果不一致，已停止错误位置套版";
            case "GARMENT_MASK_UNCERTAIN", "SEGMENTATION_BINDING_INVALID" -> "衣服轮廓分离不可靠，已停止换色以保留背景";
            case "GARMENT_CROPPED" -> "识别的衣服区域触及图片边缘，无法可靠分离完整服装";
            case "WHITE_MASTER_REQUIRED" -> "搭放母图中的布料不符合中性白色条件，已停止不可靠换色";
            case "PRINT_OUTSIDE_GARMENT", "CHEST_REGION_REQUIRED" -> "印花位置越过可靠布料区域或胸前范围，已停止套版";
            case "PRINT_RESIDUE_DETECTED", "CLEANUP_NOT_CONVERGED" -> "原印花尚未清理干净，已保留母图等待本地修复";
            case "LOCAL_TIME_LIMIT" -> "本地印花定位处理超时，本次尚未完成";
            default -> "本地搭放检查未通过"+(code!=null&&code.matches("[A-Z_]{3,80}")?"（"+code+"）":"");
        };
    }
    static byte[] decoded(Object data) {
        if(!(data instanceof String s)||s.length()>12_000_000)throw new IllegalArgumentException("本地搭放图片编码无效");
        byte[] bytes=Base64.getDecoder().decode(s);CommerceMockupComposer.decode(bytes);return bytes;
    }
    static void requirePassed(Map<String,Object> result) {
        if(!(result.get("report") instanceof Map<?,?> report)||!Boolean.TRUE.equals(report.get("passed")))
            throw new IllegalArgumentException("本地搭放检查未通过，结果未归档为成品；母图保留，不追加生图");
    }
    private static void sameDimensions(byte[] source,byte[] output){
        var a=CommerceMockupComposer.decode(source);var b=CommerceMockupComposer.decode(output);
        if(a.getWidth()!=b.getWidth()||a.getHeight()!=b.getHeight())throw new IllegalArgumentException("搭放结果尺寸与母图不一致");
    }
    private static String encoded(byte[] bytes){return Base64.getEncoder().encodeToString(bytes);}
}
