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
    private final MarketplaceHttp http;private final String file;
    public FinanceShopeeAdsSettings(MarketplaceHttp http,@Value("${app.finance.shopee-ads.credentials-file:${APP_SHOPEE_ADS_CREDENTIALS_FILE:}}") String file){this.http=http;this.file=text(file).trim();}
    Application current(){
        if(file.isBlank())return null;
        var properties=new Properties();
        try(var reader=Files.newBufferedReader(Path.of(file),StandardCharsets.UTF_8)){properties.load(reader);}
        catch(Exception invalid){throw bad("Shopee Ads 独立服务端配置暂不可用");}
        if(!"true".equals(properties.getProperty("live-enabled","false")))return null;
        String id=properties.getProperty("partner-id","").trim(),key=properties.getProperty("partner-key","").trim();
        if(!id.matches("\\d{5,30}")||key.length()<10||key.length()>4096)throw bad("Shopee Ads 正式应用配置不完整");
        return new Application(id,hash("shopee-ads\0"+id+"\0"+key),new ShopeeOfficialClient(http,true,id,key,"https://open.shopee.com","https://partner.shopeemobile.com","SHOP"));
    }
    record Application(String partnerId,String revision,ShopeeOfficialClient client){@Override public String toString(){return "Shopee Ads application [credentials redacted]";}}
}
