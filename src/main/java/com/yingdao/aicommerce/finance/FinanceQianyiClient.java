package com.yingdao.aicommerce.finance;

import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import java.nio.ByteBuffer;
import java.io.ByteArrayOutputStream;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Read-only QERP API. Credentials, signatures and complete upstream bodies are never logged. */
@Component
public class FinanceQianyiClient {
    static final Map<String,String> HOSTS=Map.of("CN","www.qianyierp.com","ASIA","asia.qianyierp.com","TEST","gerp-test1.800best.com");
    static final Map<String,String> SERVICES=Map.of("QUERY_SHOP_LIST","shop","QUERY_RETURN_ORDER_LIST","returnOrder","QUERY_INVENTORY_LOG_LIST","inventory",
        "QUERY_FIRST_LRG_TRACKING_PACKAGE","firstLeg");
    static final int MAX_RESPONSE_BYTES=2*1024*1024;
    final FinanceRepository repo;
    final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NEVER).build();
    long nextRequest;
    long requestIntervalMillis=1000;
    public FinanceQianyiClient(FinanceRepository repo){this.repo=repo;}
    public record Credentials(String environment,String appId,String secret){
        @Override public String toString(){return "QianyiCredentials[redacted]";}
    }
    public record Page(List<Map<String,Object>> items,long total){}
    static String sign(String appId,String bizParam,String service,long timestamp,String secret){
        return org.springframework.util.DigestUtils.md5DigestAsHex(("appId="+appId+"bizParam="+bizParam+
            "serviceType="+service+"timestamp="+timestamp+secret).getBytes(StandardCharsets.UTF_8));
    }
    void pace()throws InterruptedException{
        for(;;){
            long wait;
            synchronized(this){
                wait=nextRequest-System.nanoTime();
                if(wait<=0){
                    nextRequest=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(requestIntervalMillis);
                    return;
                }
            }
            // Do not hold a monitor during a global upstream cooldown. A newly
            // received throttle response can extend it before the next request.
            TimeUnit.NANOSECONDS.sleep(Math.min(wait,TimeUnit.SECONDS.toNanos(1)));
        }
    }
    synchronized void throttled(){
        requestIntervalMillis=Math.min(3000,requestIntervalMillis+500);
        nextRequest=Math.max(nextRequest,System.nanoTime()+TimeUnit.SECONDS.toNanos(60));
    }
    public Page query(Credentials credentials,String service,Map<String,Object> parameters){
        CompletableFuture<HttpResponse<byte[]>> response=null;
        try{
            pace();
            // Build timestamp/sign only after any throttle wait.
            var request=request(credentials,service,parameters,System.currentTimeMillis());
            response=http.sendAsync(request,info->new BoundedBody());
            var received=response.get(20,TimeUnit.SECONDS);
            if(received.statusCode()!=200)throw bad("千易接口 HTTP "+received.statusCode()+"，后台将自动重试");
            return decode(new String(received.body(),StandardCharsets.UTF_8));
        }catch(InterruptedException interrupted){
            if(response!=null)response.cancel(true);
            Thread.currentThread().interrupt();throw bad("千易同步已暂停，进度已保留");
        }catch(TimeoutException timeout){
            if(response!=null)response.cancel(true);
            throw bad("千易接口请求超时，后台将从原进度重试");
        }catch(ExecutionException failure){
            if(failure.getCause() instanceof ResponseTooLarge)throw bad("千易单页数据超过限制，请缩小查询范围");
            throw bad("千易接口连接暂未完成，后台将自动重试");
        }
    }
    HttpRequest request(Credentials credentials,String service,Map<String,Object> parameters,long now){
        String host=HOSTS.get(credentials.environment()),path=SERVICES.get(service);
        if(host==null||path==null)throw bad("千易接口配置不正确");
        String json=repo.write(parameters),boundary="qianyi-"+UUID.randomUUID(),timestamp=Long.toString(now);
        var fields=new LinkedHashMap<String,String>();fields.put("appId",credentials.appId());fields.put("serviceType",service);
        fields.put("bizParam",json);fields.put("timestamp",timestamp);
        fields.put("sign",sign(credentials.appId(),json,service,Long.parseLong(timestamp),credentials.secret()));
        var body=new StringBuilder();
        fields.forEach((key,value)->body.append("--").append(boundary).append("\r\nContent-Disposition: form-data; name=\"")
            .append(key).append("\"\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n").append(value).append("\r\n"));
        body.append("--").append(boundary).append("--\r\n");
        return HttpRequest.newBuilder(URI.create("https://"+host+"/api/v1/"+path)).timeout(Duration.ofSeconds(20))
            .header("Content-Type","multipart/form-data; boundary="+boundary).header("Accept","application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString(),StandardCharsets.UTF_8)).build();
    }
    static final class ResponseTooLarge extends RuntimeException {}
    static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        final CompletableFuture<byte[]> result=new CompletableFuture<>();
        final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody(){return result;}
        public void onSubscribe(Flow.Subscription value){subscription=value;value.request(1);}
        public void onNext(List<ByteBuffer> buffers){
            for(var buffer:buffers){
                if(buffer.remaining()>MAX_RESPONSE_BYTES-bytes.size()){
                    subscription.cancel();result.completeExceptionally(new ResponseTooLarge());return;
                }
                byte[] chunk=new byte[buffer.remaining()];buffer.get(chunk);bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        public void onError(Throwable failure){result.completeExceptionally(failure);}
        public void onComplete(){result.complete(bytes.toByteArray());}
    }
    Page decode(String body){
        Map<String,Object> envelope;
        try{envelope=repo.read(body);}catch(RuntimeException invalid){throw bad("千易返回了无法识别的数据");}
        if(!"success".equals(envelope.get("state")))throw apiError(envelope);
        Object content=envelope.get("bizContent");
        Map<String,Object> data;
        try{data=content instanceof String value?repo.read(value):content instanceof Map<?,?>?map(content):envelope;}
        catch(RuntimeException invalid){throw bad("千易业务响应格式不正确，未推进同步进度");}
        if(data.containsKey("state")&&!"success".equals(data.get("state"))||Boolean.TRUE.equals(data.get("notSuccess"))
            ||data.containsKey("errorCode")&&!Set.of("","0").contains(text(data.get("errorCode"))))throw apiError(data);
        if(!(data.get("result") instanceof List<?>)||!data.containsKey("total"))throw bad("千易查询响应缺少列表或总数，未推进同步进度");
        var raw=(List<?>)data.get("result");
        if(raw.stream().anyMatch(item->!(item instanceof Map<?,?>)))throw bad("千易查询包含无法识别的记录，未推进同步进度");
        var items=rows(raw);long total;
        try{total=new java.math.BigDecimal(text(data.get("total"))).longValueExact();}
        catch(RuntimeException invalid){throw bad("千易查询总数格式异常，未推进同步进度");}
        if(items.size()>200||total<items.size())throw bad("千易查询分页数量异常，未推进同步进度");
        return new Page(items,total);
    }
    RuntimeException apiError(Map<String,Object> data){
        String code=text(data.get("errorCode")).replaceAll("[^A-Za-z0-9_.-]","");
        if("REQUEST_IS_THROTTLED".equals(code)){
            throttled();
            return bad("千易接口触发限流，已自动降低请求频率并等待重试，同步进度保留");
        }
        if("SERVICE_TYPE_NOT_ALLOW".equals(code))return bad("千易应用未开通该接口权限（SERVICE_TYPE_NOT_ALLOW），请在千易「设置 → 三方应用对接」开通店铺查询、售后单查询和库存流水查询");
        if("APP_NOT_EXIST".equals(code))return bad("当前千易环境找不到该应用（APP_NOT_EXIST），请核对国内/海外环境与 App ID");
        return bad("千易接口返回错误"+(code.isBlank()?"":"（"+code.substring(0,Math.min(80,code.length()))+"）")+"，请检查鉴权及接口权限");
    }
}
