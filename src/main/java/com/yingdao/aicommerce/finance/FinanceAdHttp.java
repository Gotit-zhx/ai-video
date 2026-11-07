package com.yingdao.aicommerce.finance;

import com.yingdao.aicommerce.shop.infrastructure.MarketplaceHttp;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Properties;

/** Optional private CONNECT tunnel used only by the advertising API clients. */
@Component
public final class FinanceAdHttp {
    private final MarketplaceHttp transport;
    private final HttpClient tunnelClient;

    @Autowired
    public FinanceAdHttp(MarketplaceHttp direct, ObjectMapper mapper,
            @Value("${app.finance.ads.proxy-host:${APP_TIKTOK_ADS_PROXY_HOST:}}") String host,
            @Value("${app.finance.ads.proxy-port:${APP_TIKTOK_ADS_PROXY_PORT:0}}") int port,
            @Value("${app.finance.ads.credentials-file:${APP_TIKTOK_ADS_CREDENTIALS_FILE:}}") String file) {
        this(direct,mapper,settings(host,port,file));
    }

    public FinanceAdHttp(MarketplaceHttp direct,ObjectMapper mapper,String host,int port){
        this(direct,mapper,new Connection(host==null?"":host.trim(),port));
    }

    private FinanceAdHttp(MarketplaceHttp direct,ObjectMapper mapper,Connection settings){
        if(settings.host().isEmpty()&&settings.port()==0){tunnelClient=null;transport=direct;}
        else{tunnelClient=clientBuilder(settings.host(),settings.port()).build();transport=new MarketplaceHttp(mapper,tunnelClient);}
    }

    private record Connection(String host,int port){}

    private static Connection settings(String host,int port,String file){
        host=host==null?"":host.trim();file=file==null?"":file.trim();
        if(!host.isEmpty()||port!=0||file.isEmpty())return new Connection(host,port);
        var properties=new Properties();
        try(var reader=Files.newBufferedReader(Path.of(file),StandardCharsets.UTF_8)){
            properties.load(reader);
            return new Connection(properties.getProperty("proxy-host","").trim(),
                    Integer.parseInt(properties.getProperty("proxy-port","0").trim()));
        }catch(IOException|IllegalArgumentException failure){
            // The file also holds application credentials; never propagate its values or parser errors.
            throw new IllegalArgumentException("广告接口连接配置读取失败，请核对服务端广告配置文件");
        }
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
