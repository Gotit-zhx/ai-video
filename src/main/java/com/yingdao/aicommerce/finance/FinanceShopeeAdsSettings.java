package com.yingdao.aicommerce.finance;

import com.yingdao.aicommerce.shop.infrastructure.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Properties;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** A separate, approved live Ads Service application. ERP secrets/tokens are never used. */
@Component
public class FinanceShopeeAdsSettings {
    static final String DEFAULT_FILE="/run/heyi-finance-ads/shopee-ads.properties";
    private final MarketplaceHttp http;private final String file;
    public FinanceShopeeAdsSettings(MarketplaceHttp http,@Value("${app.finance.shopee-ads.credentials-file:${APP_SHOPEE_ADS_CREDENTIALS_FILE:}}") String file){this.http=http;this.file=text(file).trim();}
    Application current(){
        Path path=file.isBlank()?Path.of(DEFAULT_FILE):Path.of(file);
        if(file.isBlank()&&!Files.isRegularFile(path))return null;
        var properties=new Properties();
        try(var reader=Files.newBufferedReader(path,StandardCharsets.UTF_8)){properties.load(reader);}
        catch(Exception invalid){throw bad("Shopee Ads 独立服务端配置暂不可用");}
        if(!"true".equals(properties.getProperty("live-enabled","false")))return null;
        String id=properties.getProperty("partner-id","").trim(),key=properties.getProperty("partner-key","").trim();
        if(!id.matches("\\d{5,30}")||key.length()<10||key.length()>4096)throw bad("Shopee Ads 正式应用配置不完整");
        String auth=endpoint(properties.getProperty("auth-base-url","https://open.shopee.com"),true),api=endpoint(properties.getProperty("api-base-url","https://partner.shopeemobile.com"),false);
        return new Application(id,hash("shopee-ads\0"+id+"\0"+key+"\0"+auth+"\0"+api),new ShopeeOfficialClient(http,true,id,key,auth,api,"SHOP"));
    }
    private static String endpoint(String value,boolean authorization){
        java.net.URI uri;
        try{uri=java.net.URI.create(value.trim());}catch(RuntimeException invalid){throw bad("Shopee Ads 官方接口地址配置无效");}
        var hosts=authorization?java.util.Set.of("open.shopee.com","open.shopee.cn"):java.util.Set.of("partner.shopeemobile.com","openplatform.shopee.cn");
        if(!"https".equals(uri.getScheme())||!hosts.contains(uri.getHost())||uri.getPort()!=-1||uri.getUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null||uri.getPath()!=null&&!uri.getPath().isEmpty()&&!uri.getPath().equals("/"))throw bad("Shopee Ads 仅允许配置已支持的官方 HTTPS 地址");
        return uri.getScheme()+"://"+uri.getHost();
    }
    record Application(String partnerId,String revision,ShopeeOfficialClient client){@Override public String toString(){return "Shopee Ads application [credentials redacted]";}}
}
