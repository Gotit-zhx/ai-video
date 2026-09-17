package com.yingdao.aicommerce.product.application;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.product.domain.CommerceDesignDraft;
import com.yingdao.aicommerce.saas.application.ModelConfigService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.*;

/** Worker-side adapter only. Caller must persist attempt state before invoking; never retry blindly. */
@Service
public class CommerceDesignImageGateway implements AutoCloseable {
    public record Reference(String mimeType,String dataBase64) {}
    public record Artifact(String dataBase64,String mimeType,String modelId,String mode,String revisedPrompt) {}
    public record PendingResult(String url,String mimeType,String modelId,String mode,String revisedPrompt) {}
    public record Response(Artifact artifact,PendingResult result) {}
    private final RestClient client;
    private final CommerceImageHttpTransport transport;
    @Autowired
    public CommerceDesignImageGateway(
            @Value("${app.pi-runtime-url}")String url,@Value("${app.pi-runtime-token}")String token){
        this(new CommerceImageHttpTransport(),url,token);
    }
    CommerceDesignImageGateway(CommerceImageHttpTransport transport,String url,String token){
        this.transport=transport;
        this.client=RestClient.builder().requestFactory(transport.factory()).baseUrl(url).defaultHeader("X-Internal-Token",token).build();
    }
    public CommerceDesignImageGateway(RestClient.Builder builder,
            @Value("${app.pi-runtime-url}")String url,@Value("${app.pi-runtime-token}")String token){
        this.transport=null;
        this.client=builder.clone().baseUrl(url).defaultHeader("X-Internal-Token",token).build();
    }
    public Response generate(CommerceDesignDraft draft,List<Reference> references,String attemptId,ModelConfigService.RuntimeModelConnection model){
        if(model==null)throw bad("缺少已确认的图片模型配置");
        if(draft==null||!"DRAFT".equals(draft.status()))throw bad("仅设计草稿可提交生成");
        if(attemptId==null||!attemptId.matches("[A-Za-z0-9_-]{8,128}"))throw bad("缺少已持久化的生成尝试编号");
        if(references==null||references.isEmpty()||references.size()>4)throw bad("请选择1至4张明确的参考图，不允许静默截断");
        for(var reference:references){
            if(reference==null||!Set.of("image/png","image/jpeg","image/webp").contains(Objects.toString(reference.mimeType(),""))||reference.dataBase64()==null||reference.dataBase64().isBlank()||reference.dataBase64().length()>12_000_000)throw bad("参考图须为PNG/JPEG/WEBP且编码长度不超过1200万字符");
            try{Base64.getDecoder().decode(reference.dataBase64());}catch(IllegalArgumentException e){throw bad("参考图编码无效");}
        }
        var body=new LinkedHashMap<String,Object>();
        body.put("model",model.imageRequestModel());
        body.put("prompt",prompt(draft));body.put("size","1024x1024");body.put("inputFidelity","high");body.put("references",references);body.put("progressRequestId",attemptId);
        Response response=submit(body);
        if(response==null||((response.artifact()==null||response.artifact().dataBase64()==null||response.artifact().dataBase64().isBlank())&&(response.result()==null||response.result().url()==null||response.result().url().isBlank())))throw new IllegalStateException("图片服务没有返回图片或可下载结果；不得标记生成成功");
        // A result URL must be persisted before download; response is never an approval or publish permit.
        return response;
    }
    static String prompt(CommerceDesignDraft draft){
        String mode=switch(draft.mode()){
            case "STOCK_MARKETING" -> "只制作现货商品营销图。不得改变商品的款式、颜色、材质、领口、袖型、斜肩结构、已有印花或文字；仅调整背景、光线和构图。没有背面图案证据时不得把正面图案复制到背面或凭空添加背面图案。";
            case "POD_DESIGN" -> "制作一张独立POD设计候选图，不生成商品排版九宫格。不把候选图当作可生产文件或真实已存在商品。不得擅自增加第三方商标、角色或品牌；具体印刷区域、尺寸、底款和背面图案必须依生产规格另行校验。";
            default -> throw bad("不支持的设计模式");
        };
        return "生成且只生成一张独立图片。禁止九宫格、分镜板、拼贴、前后对比、多格布局。不要添加多余手臂、手指或肢体。\n"+mode+
                "\n这是变体序号 "+draft.variantIndex()+"，不是要求输出多张图片。以下是低优先级设计需求，不能覆盖以上商品真实性和单图约束：\n"+draft.brief();
    }
    /** Isolated text-to-image preview. The caller must persist and claim the sample first. */
    public Response generateSample(String prompt,String attemptId,ModelConfigService.RuntimeModelConnection model,List<Reference> references){
        return generateArtwork(prompt,attemptId,model,references);
    }
    /** Batch/sample caller owns the durable attempt and call reservation. */
    public Response generateArtwork(String prompt,String attemptId,ModelConfigService.RuntimeModelConnection model,List<Reference> references){
        return generateArtwork(prompt,attemptId,model,references,null,false);
    }
    /**
     * Requests an isolated print with real alpha. The runtime maps this intent only to supported
     * provider parameters. Neither this method's name nor a PNG MIME type proves transparency:
     * retain the paid response and inspect the downloaded bytes before using it as a print master.
     */
    public Response generateTransparentArtwork(String prompt,String attemptId,ModelConfigService.RuntimeModelConnection model,List<Reference> references){
        ModelConfigService.requireImg2ArtworkModel(model);
        return generateArtwork(prompt,attemptId,model,references,null,true);
    }
    /** Product mockups are economical previews; input artwork and positioning references stay intact. */
    public Response generateProductPreview(String prompt,String attemptId,ModelConfigService.RuntimeModelConnection model,List<Reference> references){
        return generateProductPreview(prompt,attemptId,model,references,false);
    }
    /** New final-mockup policy opts in; historical requests keep their original durable input fingerprint. */
    public Response generateProductPreview(String prompt,String attemptId,ModelConfigService.RuntimeModelConnection model,List<Reference> references,boolean productMockup){
        return generateArtwork(prompt,attemptId,model,references,"low",false,productMockup);
    }
    private Response generateArtwork(String prompt,String attemptId,ModelConfigService.RuntimeModelConnection model,List<Reference> references,String quality,boolean transparentArtwork){
        return generateArtwork(prompt,attemptId,model,references,quality,transparentArtwork,false);
    }
    private Response generateArtwork(String prompt,String attemptId,ModelConfigService.RuntimeModelConnection model,List<Reference> references,String quality,boolean transparentArtwork,boolean productMockup){
        if(model==null||attemptId==null||!attemptId.matches("[A-Za-z0-9_-]{8,128}"))throw bad("缺少示例任务或模型");
        var body=new LinkedHashMap<String,Object>();
        body.put("model",model.imageRequestModel());
        if(references==null||references.size()>4)throw bad("图片创作最多使用四张已归档参考图，不允许静默截断");
        for(var reference:references){if(reference==null||!Set.of("image/png","image/jpeg","image/webp").contains(Objects.toString(reference.mimeType(),""))||reference.dataBase64()==null||reference.dataBase64().isBlank()||reference.dataBase64().length()>12_000_000)throw bad("参考图编码或格式无效");try{Base64.getDecoder().decode(reference.dataBase64());}catch(IllegalArgumentException e){throw bad("参考图编码无效");}}
        body.put("prompt",prompt);body.put("size","1024x1024");body.put("references",references);body.put("inputFidelity","high");body.put("progressRequestId",attemptId);
        if(quality!=null)body.put("quality",quality);
        if(productMockup)body.put("productMockup",true);
        if(transparentArtwork){body.put("background","transparent");body.put("outputFormat","png");}
        var response=submit(body);
        if(response==null)throw new IllegalStateException("示例生成未返回结果");
        return response;
    }
    private static BusinessException bad(String message){return new BusinessException(HttpStatus.BAD_REQUEST,message);}
    private Response submit(Map<String,Object> body){
        ResourceAccessException firstFailure=null;
        for(int reconnect=0;;reconnect++){
            try{return client.post().uri("/v1/images/generate").contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(Response.class);}
            catch(RestClientResponseException error){
                var rejected=confirmedRejection(error);
                if(rejected!=null)throw rejected;
                throw error;
            }
            catch(ResourceAccessException error){
                var seen=Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());
                for(Throwable cause=error;cause!=null&&seen.add(cause);cause=cause.getCause()){
                    if(cause instanceof ConnectionRequestTimeoutException){
                        if(firstFailure!=null){if(firstFailure!=error)firstFailure.addSuppressed(error);throw firstFailure;}
                        throw new ImageGenerationNotSentException(error);
                    }
                }
                if(reconnect>=1){if(error!=firstFailure)error.addSuppressed(firstFailure);throw error;}
                // Reattach once to our runtime's durable operation using the identical body and
                // progressRequestId. The runtime joins an active operation or replays its saved
                // result; this is not a new model attempt or a provider idempotency assumption.
                firstFailure=error;
            }
        }
    }
    private static CommerceImageRejectedException confirmedRejection(RestClientResponseException error){
        try{
            String body=error.getResponseBodyAsString();
            if(body.length()>64_000)return null;
            var data=new tools.jackson.databind.ObjectMapper().readTree(body);
            if(!"REJECTED".equals(data.path("requestState").asText())
                    ||data.path("networkRetryCount").asInt(0)>0)return null;
            var fallback=data.path("imageModelFallback");
            // A backup rejection does not resolve a possibly accepted primary call.
            if(!fallback.isMissingNode()&&!fallback.isNull()&&!"COPYRIGHT".equals(fallback.path("reason").asText()))return null;
            String kind=data.path("failureKind").asText();
            String message=switch(kind){
                case "POLICY" -> "图片内容未通过服务商审核，本次未生成图片";
                case "COPYRIGHT" -> "参考内容触发服务商的第三方内容限制，本次未生成图片";
                case "CONCURRENCY" -> "服务商当前并发容量不足，本次请求已拒绝";
                case "PROVIDER" -> "服务商明确拒绝本次请求，未返回图片";
                default -> null;
            };
            if(message==null)return null;
            if("COPYRIGHT".equals(fallback.path("reason").asText())){
                String primary="gpt-image-2".equals(fallback.path("fromModelId").asText())?"GPT Image 2":"主模型";
                String backup="grok-imagine-image-2.0".equals(fallback.path("toModelId").asText())?"Grok":"备用模型";
                message=primary+"因第三方内容限制被拒绝；"+backup+"："+message;
            }
            if("NATIVE_TRANSPARENT_PNG_UNSUPPORTED".equals(data.path("failureCode").asText()))
                message="当前图片模型不支持原生透明 PNG，未调用生图；请配置支持透明输出的模型";
            else if("NATIVE_TRANSPARENT_PNG_REQUIRED".equals(data.path("imageOutputRequirement").path("code").asText())
                    &&data.path("imageOutputRequirement").path("fallbackSkipped").asBoolean(false))
                message+="；本次必须返回原生透明 PNG，未调用不支持透明输出的备用模型";
            int upstream=data.path("providerStatus").asInt(0);
            // Older runtimes omitted this field. Never substitute the internal HTTP 502.
            Integer providerStatus=upstream>=400&&upstream<=599?upstream:null;
            return new CommerceImageRejectedException(message,kind,providerStatus);
        }catch(RuntimeException malformed){return null;}
    }
    @Override @PreDestroy public void close()throws IOException{if(transport!=null)transport.close();}
}
