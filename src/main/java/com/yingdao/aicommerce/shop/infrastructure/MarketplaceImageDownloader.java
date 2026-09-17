package com.yingdao.aicommerce.shop.infrastructure;
import com.yingdao.aicommerce.product.application.SafeProductImageUrl;
import org.springframework.stereotype.Component;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.core5.util.Timeout;
import jakarta.annotation.PreDestroy;
import java.net.*;
import java.util.Locale;
import java.io.IOException;

@Component
public class MarketplaceImageDownloader {
    private static final int MAX_BYTES=10*1024*1024;
    private final SafeProductImageUrl validator;
    private final CloseableHttpClient client;
    private final PublicImageDnsResolver dnsResolver;
    private com.yingdao.aicommerce.product.application.ProductWatermarkAssets watermarkAssets;
    @org.springframework.beans.factory.annotation.Autowired
    public void setWatermarkAssets(com.yingdao.aicommerce.product.application.ProductWatermarkAssets assets){this.watermarkAssets=assets;}
    @org.springframework.beans.factory.annotation.Autowired
    private com.yingdao.aicommerce.product.application.CommerceImageArchive commerceArchive;
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.jdbc.core.JdbcTemplate commerceDb;
    public ImageBinary downloadProductImage(String url,com.yingdao.aicommerce.product.domain.ProductCaptureImage image){
        String commercePrefix=com.yingdao.aicommerce.product.application.CommercePublicationDrafts.PREFIX;
        if(url.startsWith(commercePrefix)){
            if(image==null||!url.equals(image.getProcessedUrl())||!(url+"?original").equals(image.getOriginalUrl())||commerceDb.queryForObject("SELECT COUNT(*) FROM commerce_batch_publications WHERE organization_id=? AND capture_id=?",Integer.class,image.getOrganizationId(),image.getCaptureId())==0)throw new MarketplaceApiException("发布图片不属于已固定的裂变商品");
            var ref=commerceArchive.read(image.getOrganizationId(),url.substring(commercePrefix.length()));
            return new ImageBinary(java.util.Base64.getDecoder().decode(ref.dataBase64()),ref.mimeType(),".png");
        }
        String prefix=com.yingdao.aicommerce.product.application.ProductWatermarkAssets.PREFIX;
        if(!url.startsWith(prefix))return download(url);
        if(watermarkAssets==null)throw new MarketplaceApiException("水印图片归档不可用");
        var ref=watermarkAssets.read(image.getOrganizationId(),image.getCaptureId(),image.getId(),url.substring(prefix.length()));
        return new ImageBinary(java.util.Base64.getDecoder().decode(ref.dataBase64()),ref.mimeType(),ref.mimeType().equals("image/png")?".png":ref.mimeType().equals("image/jpeg")?".jpg":".webp");
    }
    public MarketplaceImageDownloader(SafeProductImageUrl validator){
        this(validator,false);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public MarketplaceImageDownloader(SafeProductImageUrl validator,
            @org.springframework.beans.factory.annotation.Value("${app.product-capture.image-dns.google-fallback:false}")boolean googleDnsFallback){
        this.validator=validator;
        this.dnsResolver=new PublicImageDnsResolver(googleDnsFallback);
        var manager=PoolingHttpClientConnectionManagerBuilder.create().setDnsResolver(dnsResolver)
                .setMaxConnTotal(128).setMaxConnPerRoute(32)
                .setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(Timeout.ofSeconds(15)).setSocketTimeout(Timeout.ofSeconds(45)).build()).build();
        client=HttpClients.custom().setConnectionManager(manager).disableAutomaticRetries().disableRedirectHandling().disableCookieManagement()
                .setDefaultRequestConfig(RequestConfig.custom().setConnectionRequestTimeout(Timeout.ofSeconds(60)).setResponseTimeout(Timeout.ofSeconds(45)).build()).build();
    }
    public ImageBinary download(String url){
        var uri=URI.create(validator.validate(url));
        try{
        if(uri.getHost().matches("[0-9.]+")||uri.getHost().contains(":"))publicAddresses(new InetAddress[]{InetAddress.getByName(uri.getHost())});
        return client.execute(new HttpGet(uri),response->{
            if(response.getCode()<200||response.getCode()>=300)throw new MarketplaceApiException("读取商品图失败，HTTP "+response.getCode());
            var entity=response.getEntity();if(entity==null)throw new MarketplaceApiException("商品图响应为空");
            String mime=entity.getContentType()==null?"":entity.getContentType().split(";",2)[0].trim().toLowerCase(Locale.ROOT);
            if(!java.util.Set.of("image/png","image/jpeg","image/webp").contains(mime))throw new MarketplaceApiException("响应不是受支持的商品图片");
            if(entity.getContentLength()>MAX_BYTES)throw new MarketplaceApiException("商品图不能超过10MiB");
            try(var stream=entity.getContent()){
                byte[] bytes=stream.readNBytes(MAX_BYTES+1);if(bytes.length==0||bytes.length>MAX_BYTES)throw new MarketplaceApiException("商品图为空或超过10MiB");
                return new ImageBinary(bytes,mime,mime.equals("image/png")?".png":mime.equals("image/webp")?".webp":".jpg");
            }
        });}catch(MarketplaceApiException e){throw e;}catch(Exception e){throw new MarketplaceApiException(failureMessage(uri,e),e);}
    }
    static String failureMessage(URI uri,Throwable failure){
        String reason="下载传输失败";
        for(Throwable cause=failure;cause!=null;cause=cause.getCause()){
            if(cause instanceof UnknownHostException){reason="DNS 解析失败或地址未通过公网校验";break;}
            if(cause.getClass().getSimpleName().equals("ConnectionRequestTimeoutException")){reason="等待下载连接超时";break;}
            if(cause.getClass().getSimpleName().equals("ConnectTimeoutException")){reason="连接图片服务器超时";break;}
            if(cause instanceof SocketTimeoutException){reason="读取图片响应超时";break;}
            if(cause instanceof javax.net.ssl.SSLException){reason="图片服务器 TLS 校验或连接失败";break;}
            if(cause instanceof ConnectException){reason="无法连接图片服务器";break;}
            if(cause instanceof SocketException){reason="图片下载连接中断";break;}
        }
        return "读取商品图失败："+reason+"（"+uri.getHost()+"）";
    }
    static InetAddress[] publicAddresses(InetAddress[] addresses)throws UnknownHostException{
        if(addresses.length==0)throw new UnknownHostException("无可用公网地址");
        for(var address:addresses){
            byte[] b=address.getAddress();boolean denied=address.isAnyLocalAddress()||address.isLoopbackAddress()||address.isLinkLocalAddress()||address.isSiteLocalAddress()||address.isMulticastAddress();
            if(b.length==4){int a=b[0]&255,c=b[1]&255,d=b[2]&255;
                denied|=a==0||a>=224||(a==100&&c>=64&&c<=127)||(a==192&&c==0)||(a==198&&(c==18||c==19))||(a==198&&c==51&&d==100)||(a==203&&c==0&&d==113);
            }else{denied|=(b[0]&0xe0)!=0x20 || (b[0]==0x20&&b[1]==1&&(b[2]&255)==0x0d&&(b[3]&255)==0xb8);}
            if(denied)throw new UnknownHostException("商品图片解析到非公网地址");
        }
        return addresses;
    }
    @PreDestroy public void close()throws IOException{try{client.close();}finally{dnsResolver.close();}}
    public record ImageBinary(byte[] bytes,String contentType,String extension){}
}
