package com.yingdao.aicommerce.product.application.provider;

import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.product.domain.*;
import com.yingdao.aicommerce.product.application.CommerceImageArchive;
import com.yingdao.aicommerce.saas.application.ModelConfigService;
import com.yingdao.aicommerce.shop.application.MarketplaceAccess;
import com.yingdao.aicommerce.shop.infrastructure.*;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;

/** Category-driven, evidence-backed filling of missing mandatory product properties. */
@Component
public class ShopeeAttributeResolver {
    private final ShopeeOfficialClient client;
    private final ModelConfigService models;
    private final MarketplaceImageDownloader downloader;
    private final CommerceImageArchive archive;
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final RestClient vision;
    private final Path cache;
    public ShopeeAttributeResolver(ShopeeOfficialClient client, ModelConfigService models,
            MarketplaceImageDownloader downloader, CommerceImageArchive archive, JdbcTemplate db, ObjectMapper json,
            @Value("${app.pi-runtime-url}") String url, @Value("${app.pi-runtime-token}") String token,
            @Value("${app.ai-video-task-storage:/data/ai-video-tasks}") String storage) {
        this.client=client;this.models=models;this.downloader=downloader;this.archive=archive;this.db=db;this.json=json;
        var factory=new SimpleClientHttpRequestFactory();factory.setConnectTimeout(10000);factory.setReadTimeout(200000);
        vision=RestClient.builder().baseUrl(url).defaultHeader("X-Internal-Token",token).requestFactory(factory).build();
        cache=Path.of(storage,"shopee-attributes-v1");
    }

    public List<?> prepare(ProductCapture capture,List<ProductCaptureImage> images,
            MarketplaceAccess.Connection connection,long categoryId,Map<String,Object> request) {
        if(!Objects.equals(capture.getOrganizationId(),connection.shop().getOrganizationId()))throw bad("商品与店铺归属不一致");
        var body=MarketplaceHttp.object(client.shopGet("/api/v2/product/get_attribute_tree",connection,
                Map.of("category_id_list",categoryId,"language","en")),"response");
        List<?> definitions=null;
        if(body.get("list") instanceof List<?> categories)for(Object c:categories)
            if(c instanceof Map<?,?> m && String.valueOf(categoryId).equals(String.valueOf(m.get("category_id")))
                    && m.get("attribute_tree") instanceof List<?> list)definitions=list;
        if(definitions==null)throw bad("Shopee 未返回目标类目属性");
        Object supplied=request.get("productAttributes");
        if(!(supplied instanceof List<?> l)||l.isEmpty())if(request.get("shopeePayload") instanceof Map<?,?> p)supplied=p.get("attribute_list");
        Map<String,Object> normalized;
        try { normalized=capture.getNormalizedData()==null?Map.of():json.readValue(capture.getNormalizedData(),Map.class); }
        catch(Exception e){throw bad("采集属性数据无法读取");}
        List<?> current=ShopeeMaterialDefaults.resolve(supplied instanceof List<?> l?l:List.of(),definitions);
        current=ShopeeAttributeFacts.resolve(current,definitions,normalized,capture.getTitle());
        List<Map<?,?>> missing=new ArrayList<>();
        for(Object d:definitions)if(d instanceof Map<?,?> m && Boolean.TRUE.equals(m.get("mandatory")) && !has(current,m.get("attribute_id")))missing.add(m);
        if(missing.isEmpty())return current;
        try {
            Map<String,Object> facts=new LinkedHashMap<>();
            facts.put("title",capture.getTitle());facts.put("description",capture.getDescription());
            for(String key:List.of("attributes","productAttributes","product_attributes","productProperties","product_properties","skus"))
                if(normalized.containsKey(key))facts.put(key,normalized.get(key));
            // Do not send source URLs, prices, or large SKU payloads to the model.
            if(facts.get("skus") instanceof List<?> skus)facts.put("skus",skus.stream().filter(Map.class::isInstance)
                    .map(Map.class::cast).map(m->m.get("attributes")).filter(Objects::nonNull).distinct().toList());
            List<ProductCaptureImage> candidates=images.stream().filter(i->List.of("MAIN","GALLERY","DETAIL","SIZE_CHART").contains(i.getRoleName()))
                    .sorted(Comparator.comparingInt(i->"MAIN".equals(i.getRoleName())?0:"GALLERY".equals(i.getRoleName())?1:2))
                    .limit(6).toList();
            String signature=json.writeValueAsString(Map.of("org",capture.getOrganizationId(),"capture",capture.getId(),
                    "category",categoryId,"facts",facts,"definitions",missing,"images",candidates.stream().map(i->i.getId()+":"+i.getOriginalUrl()).toList()));
            String key=ShopeeProductVideoUploader.digest("SHA-256",signature.getBytes(StandardCharsets.UTF_8));
            Files.createDirectories(cache);Path saved=cache.resolve(key+".json");
            Map<String,Object> result;
            if(Files.isRegularFile(saved,LinkOption.NOFOLLOW_LINKS))result=json.readValue(Files.readString(saved),Map.class);
            else {
                List<byte[]> originals=new ArrayList<>();
                for(var image:candidates){
                    originals.add(originalImage(capture,image));
                }
                if(originals.isEmpty())throw bad("自动识别属性需要原商品图片");
                var model=models.driverConnection(capture.getOrganizationId()).orElseThrow(()->bad("请配置属性识别视觉模型"));
                var payload=Map.of("model",model.driverRequestModel(),"context",Map.of("analysisType","SHOPEE_ATTRIBUTES_V1","facts",facts,"definitions",missing),
                        "image",Map.of("mimeType","image/jpeg","data",Base64.getEncoder().encodeToString(compactSheet(originals))));
                var response=analyze(payload);
                if(response==null||!(response.get("result") instanceof Map<?,?> m))throw bad("自动属性识别未返回有效结果");
                result=(Map<String,Object>)m;
                merge(current,missing,result); // Validate before caching, never persist an unusable guess.
                Path temp=Files.createTempFile(cache,"attributes-",".json");Files.writeString(temp,json.writeValueAsString(result));
                Files.move(temp,saved,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            }
            return merge(current,missing,result);
        }catch(BusinessException e){throw e;}catch(java.io.IOException e){throw bad("自动属性识别缓存或图片读取失败");}
    }

    byte[] originalImage(ProductCapture capture,ProductCaptureImage image) {
        if(!Objects.equals(image.getOrganizationId(),capture.getOrganizationId())||!Objects.equals(image.getCaptureId(),capture.getId()))throw bad("商品图片归属不一致");
        var archived=db.queryForList("SELECT source_key FROM product_watermark_checks WHERE organization_id=? AND capture_id=? AND image_id=? ORDER BY updated_at DESC LIMIT 1",capture.getOrganizationId(),capture.getId(),image.getId());
        if(!archived.isEmpty())return Base64.getDecoder().decode(archive.read(capture.getOrganizationId(),String.valueOf(archived.getFirst().get("source_key"))).dataBase64());
        String processed=image.getProcessedUrl();
        // Commerce drafts bind original and processed to the same immutable archive.
        // Normal captures must keep the original evidence, even when an edited image exists.
        if(processed!=null&&processed.startsWith(com.yingdao.aicommerce.product.application.CommercePublicationDrafts.PREFIX)
                &&(processed+"?original").equals(image.getOriginalUrl()))return downloader.downloadProductImage(processed,image).bytes();
        return downloader.download(image.getOriginalUrl()).bytes();
    }

    private static byte[] compactSheet(List<byte[]> originals) throws java.io.IOException {
        var sheet=javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(ShopeeSizeChartExtractor.contactSheet(originals)));
        var output=new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(sheet,"jpeg",output);
        return output.toByteArray();
    }

    private Map analyze(Object payload) {
        for(int attempt=0;attempt<3;attempt++) {
            try {
                return vision.post().uri("/v1/product/analyze").contentType(MediaType.APPLICATION_JSON)
                        .body(payload).retrieve().body(Map.class);
            } catch(org.springframework.web.client.RestClientResponseException e) {
                if(attempt==2 || !(e.getStatusCode().is5xxServerError() || e.getStatusCode().value()==429))throw e;
            } catch(org.springframework.web.client.ResourceAccessException e) {
                if(attempt==2)throw e;
            }
            try {Thread.sleep(1500L*(attempt+1));}
            catch(InterruptedException e){Thread.currentThread().interrupt();throw bad("自动属性识别已中断");}
        }
        throw bad("自动属性识别暂时不可用");
    }

    static boolean has(List<?> values,Object id) {
        return values.stream().anyMatch(v->v instanceof Map<?,?> m && String.valueOf(id).equals(String.valueOf(m.get("attribute_id")))
                && m.get("attribute_value_list") instanceof List<?> l && !l.isEmpty());
    }
    static List<?> merge(List<?> existing,List<? extends Map<?,?>> definitions,Map<String,Object> result) {
        List<Object> merged=new ArrayList<>(existing);List<String> unresolved=new ArrayList<>();
        List<?> answers=result.get("attributes") instanceof List<?> l?l:List.of();
        for(Map<?,?> definition:definitions) {
            Object id=definition.get("attribute_id");
            if(has(existing,id))continue;
            var matches=answers.stream().filter(Map.class::isInstance).map(v->(Map<?,?>)v)
                    .filter(m->String.valueOf(id).equals(String.valueOf(m.get("attribute_id")))).toList();
            if(matches.size()!=1){unresolved.add(String.valueOf(definition.get("name")));continue;}
            var answer=matches.getFirst();
            if(!Boolean.TRUE.equals(answer.get("supported")) || !(answer.get("evidence") instanceof String evidence)||evidence.isBlank()
                    || !(answer.get("value_ids") instanceof List<?> ids)||ids.isEmpty()){
                unresolved.add(String.valueOf(definition.get("name")));continue;
            }
            List<Map<String,Object>> values=new ArrayList<>();Set<String> unique=new HashSet<>();
            List<?> options=definition.get("attribute_value_list") instanceof List<?> l?l:List.of();
            for(Object valueId:ids){
                var option=options.stream().filter(Map.class::isInstance).map(v->(Map<?,?>)v)
                        .filter(v->String.valueOf(valueId).equals(String.valueOf(v.get("value_id")))).findFirst();
                if(option.isEmpty()||!unique.add(String.valueOf(valueId)))throw bad("自动识别返回非官方或重复属性选项："+definition.get("name"));
                values.add(Map.of("value_id",option.get().get("value_id"),"original_value_name",option.get().get("name")));
            }
            Map<?,?> info=definition.get("attribute_info") instanceof Map<?,?> m?m:Map.of();
            int max=info.get("max_value_count") instanceof Number n?n.intValue():1;
            if(values.size()>max)throw bad("自动识别属性超出官方可选数量："+definition.get("name"));
            merged.removeIf(v->v instanceof Map<?,?> m && String.valueOf(id).equals(String.valueOf(m.get("attribute_id"))));
            merged.add(Map.of("attribute_id",id,"attribute_value_list",values));
        }
        if(!unresolved.isEmpty())throw bad("商品资料不足以确认必填属性："+String.join("、",unresolved)+"；请补充资料后重试");
        return merged;
    }
    private static BusinessException bad(String message){return new BusinessException(HttpStatus.CONFLICT,message);}
}
