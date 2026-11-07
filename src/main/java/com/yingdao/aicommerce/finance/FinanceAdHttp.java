package com.yingdao.aicommerce.finance;

import com.yingdao.aicommerce.shop.infrastructure.MarketplaceHttp;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

/** Optional private CONNECT tunnel used only by the advertising API clients. */
@Component
public final class FinanceAdHttp {
    private final MarketplaceHttp transport;
    private final HttpClient tunnelClient;

    public FinanceAdHttp(MarketplaceHttp direct, ObjectMapper mapper,
            @Value("${app.finance.ads.proxy-host:${APP_TIKTOK_ADS_PROXY_HOST:}}") String host,
            @Value("${app.finance.ads.proxy-port:${APP_TIKTOK_ADS_PROXY_PORT:0}}") int port) {
        host=host==null?"":host.trim();
        if(host.isEmpty()&&port==0){tunnelClient=null;transport=direct;}
        else{tunnelClient=clientBuilder(host,port).build();transport=new MarketplaceHttp(mapper,tunnelClient);}
    }

    MarketplaceHttp transport(){return transport;}
    @PreDestroy public void stop(){if(tunnelClient!=null)tunnelClient.shutdown();}

    static HttpClient.Builder clientBuilder(String host,int port){
        if(host==null||host.isBlank()||host.length()>253||!host.matches("[A-Za-z0-9._:-]+")||port<1||port>65535)
            throw new IllegalArgumentException("广告接口连接配置无效，请设置私有 CONNECT 服务的主机名和端口");
        var proxy=new Proxy(Proxy.Type.HTTP,new InetSocketAddress(host,port));
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(new ProxySelector(){
                    @Override public List<Proxy> select(URI uri){
                        if(uri==null||!"https".equalsIgnoreCase(uri.getScheme())
                                ||!"business-api.tiktok.com".equalsIgnoreCase(uri.getHost())
                                ||(uri.getPort()!=-1&&uri.getPort()!=443)||uri.getUserInfo()!=null)
                            throw new IllegalArgumentException("广告连接仅允许官方 HTTPS 广告接口");
                        return List.of(proxy);
                    }
                    @Override public void connectFailed(URI uri,SocketAddress address,IOException failure){
                        // Preserve the transport error; never fall back to an unverified route or log credentials.
                    }
                });
    }
}
