package com.yingdao.aicommerce.product.application.provider;

import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.product.domain.*;
import com.yingdao.aicommerce.saas.application.ModelConfigService;
import com.yingdao.aicommerce.shop.application.MarketplaceAccess;
import com.yingdao.aicommerce.shop.infrastructure.*;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.util.List;

/** Extracts an existing source chart; never fabricates measurements or uses AI-edited chart pixels. */
@Component
public class ShopeeSizeChartExtractor {
    private final ModelConfigService models;
    private final MarketplaceImageDownloader downloader;
    private final ShopeeOfficialClient client;
    private final ObjectMapper json;
    private final RestClient vision;
    private final com.yingdao.aicommerce.product.application.CommerceImageArchive archive;
    private final Path cache;
    private final org.springframework.jdbc.core.JdbcTemplate db;
    public ShopeeSizeChartExtractor(ModelConfigService models, MarketplaceImageDownloader downloader,
            ShopeeOfficialClient client, ObjectMapper json, org.springframework.jdbc.core.JdbcTemplate db, com.yingdao.aicommerce.product.application.CommerceImageArchive archive,
            @Value("${app.pi-runtime-url}") String url, @Value("${app.pi-runtime-token}") String token,
            @Value("${app.ai-video-task-storage:/data/ai-video-tasks}") String storage) {
        this.archive=archive;this.db=db;this.models=models;this.downloader=downloader;this.client=client;this.json=json;
        var f=new SimpleClientHttpRequestFactory();f.setConnectTimeout(10000);f.setReadTimeout(120000);
        vision=RestClient.builder().baseUrl(url).defaultHeader("X-Internal-Token",token).requestFactory(f).build();
        cache=Path.of(storage,"size-chart-extractions-v1");
    }
    public Map<String,Object> prepare(ProductCapture capture,List<ProductCaptureImage> images,MarketplaceAccess.Connection connection,long category) {
        return prepare(capture, images, connection, category, Map.of());
    }
    public Map<String,Object> prepare(ProductCapture capture,List<ProductCaptureImage> images,MarketplaceAccess.Connection connection,long category,Map<String,Object> request) {
        if(!Objects.equals(capture.getOrganizationId(),connection.shop().getOrganizationId()))throw bad("尺码表与店铺不属于同一企业");
        var response=MarketplaceHttp.object(client.shopGet(ShopeeProductTitle.LIMITS,connection,Map.of("category_id",category)),"response");
        var rule=MarketplaceHttp.object(response,"size_chart_limit");
        if(!Boolean.TRUE.equals(rule.get("size_chart_mandatory")))return Map.of();
        if(Boolean.FALSE.equals(rule.get("support_image_size_chart")))throw bad("目标类目不支持图片尺码表，需要选择官方尺码模板");
        try {
            String templateKey = com.yingdao.aicommerce.product.application.ProductListingTemplateService.sizeChartImageKey(request);
            if (!templateKey.isBlank()) {
                var template = archive.read(capture.getOrganizationId(), templateKey);
                // The configured, tenant-owned chart is already selected by the operator.
                // Publish it directly: model OCR is not required to use an explicit template.
                return upload(connection, Base64.getDecoder().decode(template.dataBase64()), template.mimeType());
            }
            String sourceDescription = com.yingdao.aicommerce.product.application.ProductListingTemplateService.sourceDescription(capture, json);
            var candidates=images.stream().filter(i->List.of("SIZE_CHART","DETAIL","MAIN","GALLERY").contains(i.getRoleName()))
                    .sorted(Comparator.comparingInt(i->"SIZE_CHART".equals(i.getRoleName())?0:"DETAIL".equals(i.getRoleName())?1:2)).limit(24).toList();
            for(var i:candidates)if(!Objects.equals(i.getOrganizationId(),capture.getOrganizationId())||!Objects.equals(i.getCaptureId(),capture.getId()))throw bad("尺码图归属不一致");
            String fingerprint=ShopeeProductVideoUploader.digest("SHA-256",(capture.getOrganizationId()+":"+capture.getId()+":"+sourceDescription+":"+candidates.stream().map(i->i.getId()+":"+i.getOriginalUrl()).toList()).getBytes(StandardCharsets.UTF_8));
            Files.createDirectories(cache);Path record=cache.resolve(fingerprint+".json"),imageFile=cache.resolve(fingerprint+".png");
            byte[] chart;Map<String,Object> extracted;
            if(Files.isRegularFile(record,LinkOption.NOFOLLOW_LINKS)&&Files.isRegularFile(imageFile,LinkOption.NOFOLLOW_LINKS)){
                extracted=json.readValue(Files.readString(record),Map.class);chart=Files.readAllBytes(imageFile);
                if(!ShopeeProductVideoUploader.digest("SHA-256",chart).equals(extracted.get("chartSha256")))throw bad("尺码图缓存校验失败");
            }else{
                List<String> lines=TikTokProductSizeChart.sourceMeasurementLines(sourceDescription);
                if(!lines.isEmpty()){
                    chart=TikTokProductSizeChart.renderSourceMeasurements(lines).bytes();extracted=new LinkedHashMap<>(Map.of("sourceType","SOURCE_TEXT","lines",lines));
                }else{
                    if(candidates.isEmpty())throw bad("原商品没有可提取尺码表的详情图片");
                    List<byte[]> sources=new ArrayList<>();
                    for(var i:candidates){
                        sources.add(originalImage(capture,i));
                    }
                    int selected=-1;Map<String,Object> earlier=null;
                    var detections=db.queryForList("SELECT image_id,detection_json FROM product_watermark_checks WHERE organization_id=? AND capture_id=? AND status='READY' AND JSON_UNQUOTE(JSON_EXTRACT(detection_json,'$.imageType'))='SIZE_CHART' ORDER BY updated_at DESC",capture.getOrganizationId(),capture.getId());
                    for(var detection:detections){for(int i=0;i<candidates.size();i++)if(candidates.get(i).getId().equals(detection.get("image_id"))){selected=i;var saved=json.readValue(String.valueOf(detection.get("detection_json")),Map.class);if(saved.get("sizeChart") instanceof Map<?,?> m)earlier=(Map<String,Object>)m;break;}if(selected>=0)break;}

                    for(int i=0;selected<0 && i<candidates.size();i++)if("SIZE_CHART".equals(candidates.get(i).getRoleName())||TikTokProductSizeChart.explicitSizeChartLabel(candidates.get(i).getAltText())){selected=i;break;}
                    if(selected<0){var detected=analyze(capture,contactSheet(sources),true);selected=selection(detected,sources.size());}
                    byte[] original=png(ImageIO.read(new ByteArrayInputStream(sources.get(selected))));
                    extracted=normalizeMeasurements(earlier==null?analyze(capture,original,false):earlier);validate(extracted);
                    extracted.put("sourceType","SOURCE_IMAGE");extracted.put("sourceImageId",candidates.get(selected).getId());
                    // Publish original pixels: OCR values are evidence, not a license to redraw uncertain measurements.
                    chart=original;
                }
                extracted.put("chartSha256",ShopeeProductVideoUploader.digest("SHA-256",chart));
                Path temp=Files.createTempFile(cache,"chart-",".png");Files.write(temp,chart);Files.move(temp,imageFile,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
                Path metadata=Files.createTempFile(cache,"chart-",".json");Files.writeString(metadata,json.writeValueAsString(extracted));Files.move(metadata,record,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
            }
            return upload(connection, chart, "image/png");
        }catch(BusinessException e){throw e;}catch(IOException e){throw bad("尺码表提取或缓存失败，请稍后重试");}
    }
    private Map<String,Object> upload(MarketplaceAccess.Connection connection, byte[] chart, String mimeType) {
            var upload=client.shopMultipart("/api/v2/media_space/upload_image",connection,
                    "size-chart"+("image/jpeg".equals(mimeType)?".jpg":".png"),mimeType,chart,Map.of());
            var data=MarketplaceHttp.object(upload,"response");var info=MarketplaceHttp.object(data,"image_info");
            Object id=info.getOrDefault("image_id",data.get("image_id"));
            if(id==null||String.valueOf(id).isBlank())throw bad("Shopee 尺码图上传未返回图片编号");
            return Map.of("size_chart",String.valueOf(id));
    }
    byte[] originalImage(ProductCapture capture,ProductCaptureImage image) {
        if(!Objects.equals(image.getOrganizationId(),capture.getOrganizationId())||!Objects.equals(image.getCaptureId(),capture.getId()))throw bad("尺码图归属不一致");
        var archived=db.queryForList("SELECT source_key FROM product_watermark_checks WHERE organization_id=? AND capture_id=? AND image_id=? ORDER BY updated_at DESC LIMIT 1",capture.getOrganizationId(),capture.getId(),image.getId());
        if(!archived.isEmpty())return Base64.getDecoder().decode(archive.read(capture.getOrganizationId(),String.valueOf(archived.getFirst().get("source_key"))).dataBase64());
        String processed=image.getProcessedUrl();
        // Only a fixed commerce draft's original alias can use its archive image.
        // Never extract measurements from a normal capture's AI-edited result.
        if(processed!=null&&processed.startsWith(com.yingdao.aicommerce.product.application.CommercePublicationDrafts.PREFIX)
                &&(processed+"?original").equals(image.getOriginalUrl()))return downloader.downloadProductImage(processed,image).bytes();
        return downloader.download(image.getOriginalUrl()).bytes();
    }
    private Map<String,Object> analyze(ProductCapture capture,byte[] image,boolean sheet){
        var model=models.driverConnection(capture.getOrganizationId()).orElseThrow(()->bad("请配置可识别尺码表的视觉驱动模型"));
        var body=Map.of("model",model.driverRequestModel(),"context",Map.of("analysisType","SIZE_CHART_EXTRACT_V1","contactSheet",sheet),"image",Map.of("mimeType","image/png","data",Base64.getEncoder().encodeToString(image)));
        var response=vision.post().uri("/v1/product/analyze").contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(Map.class);
        if(response==null||!(response.get("result") instanceof Map<?,?>))throw bad("尺码识别未返回有效结果");return (Map<String,Object>)response.get("result");
    }
    static int selection(Map<String,Object> data,int count){
        if(!Boolean.TRUE.equals(data.get("found"))||!(data.get("index") instanceof Number n)||n.doubleValue()!=n.intValue()||n.intValue()<0||n.intValue()>=count)throw bad("原商品未识别到清晰尺码表，请在定制模板配置中上传通用尺码图后继续");return ((Number)data.get("index")).intValue();
    }
    static Map<String,Object> normalizeMeasurements(Map<String,Object> raw) {
        var result=new LinkedHashMap<String,Object>(raw);
        java.math.BigDecimal factor=switch(String.valueOf(raw.get("sourceUnit")).toLowerCase(Locale.ROOT)){
            case "cm" -> java.math.BigDecimal.ONE;
            case "mm" -> new java.math.BigDecimal("0.1");
            case "in","inch","inches" -> new java.math.BigDecimal("2.54");
            default -> throw bad("尺码图缺少明确尺寸单位，不能自动换算");
        };
        if(!(raw.get("rows") instanceof List<?> rows))throw bad("尺码图未返回尺寸行");
        var converted=new ArrayList<Map<String,Object>>();
        for(Object value:rows){if(!(value instanceof Map<?,?> row))throw bad("尺码行格式不正确");var target=new LinkedHashMap<String,Object>();target.put("size",row.get("size"));for(String field:List.of("chest","length","sleeve")){Object number=row.get(field);if(number==null)continue;if(!(number instanceof Number))throw bad("尺码值必须为可确认的数字");try{target.put(field+"Cm",new java.math.BigDecimal(number.toString()).multiply(factor).setScale(2,java.math.RoundingMode.HALF_UP));}catch(NumberFormatException e){throw bad("尺码测量值异常");}}converted.add(target);}
        result.put("rows",converted);result.put("originalRows",rows);result.put("normalizedUnit","cm");return result;
    }
    static void validate(Map<String,Object> data){
        if(!Boolean.TRUE.equals(data.get("found"))||!Boolean.TRUE.equals(data.get("readable"))||!(data.get("rows") instanceof List<?> rows)||rows.size()<2||rows.size()>30)throw bad("尺码图数值不清晰或不完整，请提供清晰原图");
        Set<String> sizes=new HashSet<>();
        for(Object raw:rows){if(!(raw instanceof Map<?,?> row)||!(row.get("size") instanceof String size)||size.isBlank()||!sizes.add(size.toUpperCase(Locale.ROOT)))throw bad("尺码表行无效或重复");int fields=0;for(String field:List.of("chestCm","lengthCm","sleeveCm")){Object value=row.get(field);if(value==null)continue;if(!(value instanceof Number n)||!Double.isFinite(n.doubleValue())||n.doubleValue()<=0||n.doubleValue()>400)throw bad("尺码测量值异常");fields++;}if(fields<2)throw bad("尺码表缺少完整测量数据");}
    }
    static byte[] contactSheet(List<byte[]> images)throws IOException{
        int cell=400,rows=(images.size()+3)/4;var out=new BufferedImage(1600,rows*440,BufferedImage.TYPE_INT_RGB);var g=out.createGraphics();try{g.setColor(Color.WHITE);g.fillRect(0,0,out.getWidth(),out.getHeight());g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,24));for(int i=0;i<images.size();i++){var image=ImageIO.read(new ByteArrayInputStream(images.get(i)));if(image==null)throw bad("原商品详情图无法读取");int x=i%4*cell,y=i/4*440;g.setColor(Color.BLACK);g.drawString("INDEX "+i,x+12,y+28);double scale=Math.min(400.0/image.getWidth(),400.0/image.getHeight());g.drawImage(image,x,y+40,(int)(image.getWidth()*scale),(int)(image.getHeight()*scale),null);}}finally{g.dispose();}return png(out);
    }
    private static byte[] png(BufferedImage image)throws IOException{if(image==null)throw bad("尺码图片无法解码");var out=new ByteArrayOutputStream();ImageIO.write(image,"png",out);if(out.size()>10_000_000)throw bad("尺码图片超过 10 MB，请压缩原图");return out.toByteArray();}
    private static BusinessException bad(String message){return new BusinessException(HttpStatus.CONFLICT,message);}
}
