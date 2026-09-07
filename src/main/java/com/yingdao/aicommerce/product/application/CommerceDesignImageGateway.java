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
import java.util.*;

/** Worker-side adapter only. Caller must persist attempt state before invoking; never retry blindly. */
@Service
public class CommerceDesignImageGateway {
    public record Reference(String mimeType,String dataBase64) {}
    public record Artifact(String dataBase64,String mimeType,String modelId,String mode,String revisedPrompt) {}
    public record PendingResult(String url,String mimeType,String modelId,String mode,String revisedPrompt) {}
    public record Response(Artifact artifact,PendingResult result) {}
    private final ModelConfigService models;private final RestClient client;
    @Autowired
    public CommerceDesignImageGateway(ModelConfigService models,
            @Value("${app.pi-runtime-url}")String url,@Value("${app.pi-runtime-token}")String token){
        this(models,RestClient.builder(),url,token);
    }
    public CommerceDesignImageGateway(ModelConfigService models,RestClient.Builder builder,
            @Value("${app.pi-runtime-url}")String url,@Value("${app.pi-runtime-token}")String token){
        this.models=models;this.client=builder.clone().baseUrl(url).defaultHeader("X-Internal-Token",token).build();
    }
    public Response generate(CommerceDesignDraft draft,List<Reference> references,String attemptId){
        if(draft==null||!"DRAFT".equals(draft.status()))throw bad("仅设计草稿可提交生成");
        if(attemptId==null||!attemptId.matches("[A-Za-z0-9_-]{8,128}"))throw bad("缺少已持久化的生成尝试编号");
        if(references==null||references.isEmpty()||references.size()>4)throw bad("请选择1至4张明确的参考图，不允许静默截断");
        for(var reference:references){
            if(reference==null||!Set.of("image/png","image/jpeg","image/webp").contains(Objects.toString(reference.mimeType(),""))||reference.dataBase64()==null||reference.dataBase64().isBlank()||reference.dataBase64().length()>12_000_000)throw bad("参考图须为PNG/JPEG/WEBP且编码长度不超过1200万字符");
            try{Base64.getDecoder().decode(reference.dataBase64());}catch(IllegalArgumentException e){throw bad("参考图编码无效");}
        }
        var model=models.imageConnection(draft.organizationId()).orElseThrow(()->new BusinessException(HttpStatus.CONFLICT,"当前公司尚未配置图片模型"));
        var body=new LinkedHashMap<String,Object>();
        body.put("model",Map.of("baseUrl",model.baseUrl(),"modelId",model.modelId(),"apiFormat",model.apiFormat(),"apiKey",model.apiKey()));
        body.put("prompt",prompt(draft));body.put("size","1024x1024");body.put("inputFidelity","high");body.put("references",references);body.put("progressRequestId",attemptId);
        Response response=client.post().uri("/v1/images/generate").contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(Response.class);
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
    private static BusinessException bad(String message){return new BusinessException(HttpStatus.BAD_REQUEST,message);}
}
