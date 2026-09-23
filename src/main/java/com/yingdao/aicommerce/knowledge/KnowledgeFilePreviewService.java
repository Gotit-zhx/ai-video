package com.yingdao.aicommerce.knowledge;

import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.storage.OssStorageService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** Private internal conversion only. Originals and successful derived PDFs remain in Dayan OSS. */
@Service
public class KnowledgeFilePreviewService {
    private static final String CONVERTER_VERSION="libreoffice-v1";
    private static final Duration CACHE_TTL=Duration.ofDays(30);
    private final ObjectProvider<OssStorageService> storage;
    private final ObjectProvider<StringRedisTemplate> redis;
    private final String endpoint,token,bucket;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final Set<String> inFlight=ConcurrentHashMap.newKeySet();
    public KnowledgeFilePreviewService(ObjectProvider<OssStorageService> storage,ObjectProvider<StringRedisTemplate> redis,
            @Value("${APP_KNOWLEDGE_PREVIEW_URL:http://heyi-document-preview:8092}") String endpoint,
            @Value("${APP_KNOWLEDGE_PREVIEW_TOKEN:}") String token,@Value("${APP_DAYAN_OSS_BUCKET:}") String bucket) {
        this.storage=storage;this.redis=redis;this.endpoint=endpoint;this.token=token;this.bucket=bucket;
    }
    public OssStorageService.Download convert(String organizationId,String originalLocation,String sourceSha,String extension) {
        if(!Set.of("ppt","pptx").contains(extension))throw KnowledgeService.bad("此格式暂不支持转换预览");
        if(!organizationId.matches("[A-Za-z0-9-]{1,80}")||!sourceSha.matches("[a-f0-9]{64}"))throw KnowledgeService.bad("文件预览标识无效");
        var oss=storage.getIfAvailable();if(oss==null||bucket.isBlank())throw unavailable("附件存储暂时不可用，请稍后重试预览");
        if(!originalLocation.startsWith("oss://"+bucket+"/")||!oss.isOssLocation(originalLocation))throw unavailable("原文件存储地址无效，无法预览");
        String cacheKey="heyi:knowledge:preview:"+CONVERTER_VERSION+":"+organizationId+":"+sourceSha;
        String category="knowledge-preview/"+organizationId+"/"+sourceSha+"/"+CONVERTER_VERSION;
        String prefix="oss://"+bucket+"/heyi/"+category+"/";
        var cached=cached(cacheKey,prefix,oss);if(cached!=null)return cached;
        if(token.isBlank())throw unavailable("PowerPoint 在线预览尚未启用，请先下载原文件");
        if(!inFlight.add(cacheKey))throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS,"此文件正在生成预览，请稍后重试");
        try {
            cached=cached(cacheKey,prefix,oss);if(cached!=null)return cached;
            byte[] source=oss.readAllBytes(originalLocation,KnowledgeFileService.MAX_BYTES);
            if(!KnowledgeFileService.digest(source).equals(sourceSha))throw unavailable("原文件校验失败，无法生成预览");
            URI base=URI.create(endpoint);
            if(!Set.of("http","https").contains(base.getScheme())||base.getHost()==null||base.getUserInfo()!=null||base.getQuery()!=null||base.getFragment()!=null)
                throw unavailable("预览转换服务配置无效，请联系管理员");
            URI url=URI.create(endpoint.replaceAll("/+$","")+"/convert?format="+extension);
            var request=HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(100)).header("Authorization","Bearer "+token)
                    .header("Content-Type","application/octet-stream").header("Accept","application/pdf").POST(HttpRequest.BodyPublishers.ofByteArray(source)).build();
            var pending=http.sendAsync(request,info->new LimitedBody());HttpResponse<byte[]> response;
            try {response=pending.get(100,TimeUnit.SECONDS);}catch(Exception failure){pending.cancel(true);if(failure instanceof InterruptedException)Thread.currentThread().interrupt();throw unavailable("预览生成连接中断或超时，请稍后重试；原文件已保留");}
            if(response.statusCode()==429)throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS,"预览服务正在处理其他文档，请稍后重试");
            if(response.statusCode()!=200)throw unavailable("文档暂时无法转换预览，请稍后重试或下载原文件");
            byte[] pdf=response.body();
            if(!response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT).split(";",2)[0].trim().equals("application/pdf")||!validPdf(pdf))
                throw unavailable("预览服务返回内容无效，请稍后重试或下载原文件");
            var saved=oss.store(pdf,"application/pdf",category,"preview.pdf");
            if(!saved.location().startsWith(prefix)||!oss.isOssLocation(saved.location())||saved.size()!=pdf.length)
                throw unavailable("预览文件保存失败，请稍后重试；原文件不受影响");
            var result=oss.download(saved.location());if(result.size()!=pdf.length)throw unavailable("预览文件校验失败，请稍后重试");
            try {var cache=redis.getIfAvailable();if(cache!=null)cache.opsForValue().set(cacheKey,saved.location(),CACHE_TTL);}catch(RuntimeException ignored){/* A cache outage must not hide an already verified private preview. */}
            return result;
        } catch(BusinessException failure){throw failure;}
        catch(Exception failure){throw unavailable("暂时无法生成预览，请稍后重试或下载原文件");}
        finally {inFlight.remove(cacheKey);}
    }
    private OssStorageService.Download cached(String key,String requiredPrefix,OssStorageService oss) {
        try {
            var cache=redis.getIfAvailable();if(cache==null)return null;String location=cache.opsForValue().get(key);
            if(location==null||!location.startsWith(requiredPrefix)||!oss.isOssLocation(location))return null;
            var object=oss.download(location);if(object.size()>0&&object.size()<=KnowledgeFileService.MAX_BYTES&&"application/pdf".equalsIgnoreCase(object.contentType()))return object;
        } catch(RuntimeException ignored) { /* The source remains authoritative if an expired cache object is unavailable. */ }
        return null;
    }
    static boolean validPdf(byte[] bytes){return bytes!=null&&bytes.length>=5&&bytes.length<=KnowledgeFileService.MAX_BYTES&&new String(bytes,0,5,StandardCharsets.US_ASCII).equals("%PDF-");}
    private static BusinessException unavailable(String message){return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,message);}
    /** Cancels on oversized responses; never buffers an unbounded converter response. */
    static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody(){return result;}
        @Override public void onSubscribe(Flow.Subscription subscription){this.subscription=subscription;subscription.request(1);}
        @Override public void onNext(List<ByteBuffer> buffers){
            try {for(var buffer:buffers){if((long)bytes.size()+buffer.remaining()>KnowledgeFileService.MAX_BYTES)throw new IllegalStateException("Preview response too large");byte[] chunk=new byte[buffer.remaining()];buffer.get(chunk);bytes.writeBytes(chunk);}subscription.request(1);}
            catch(RuntimeException failure){subscription.cancel();result.completeExceptionally(failure);}
        }
        @Override public void onError(Throwable failure){result.completeExceptionally(failure);}
        @Override public void onComplete(){result.complete(bytes.toByteArray());}
    }
}
