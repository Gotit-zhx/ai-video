package com.yingdao.aicommerce.finance;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Platform advertising application; never exposed through the financial UI or stored in OAuth state. */
@Component
public class FinanceAdApplicationSettings {
    private final String appId,appSecret,credentialsFile;

    public FinanceAdApplicationSettings(
            @Value("${app.finance.ads.app-id:${APP_TIKTOK_ADS_APP_ID:}}") String appId,
            @Value("${app.finance.ads.app-secret:${APP_TIKTOK_ADS_APP_SECRET:}}") String appSecret,
            @Value("${app.finance.ads.credentials-file:${APP_TIKTOK_ADS_CREDENTIALS_FILE:}}") String credentialsFile){
        this.appId=text(appId).trim();this.appSecret=text(appSecret).trim();this.credentialsFile=text(credentialsFile).trim();
    }

    Credentials current(){
        String id=appId,secret=appSecret;
        if(!credentialsFile.isBlank()){
            var properties=new Properties();
            try(var reader=Files.newBufferedReader(Path.of(credentialsFile),StandardCharsets.UTF_8)){
                properties.load(reader);
            }catch(IOException|IllegalArgumentException e){
                throw bad("广告服务端配置暂时不可用，请联系管理员");
            }
            id=properties.getProperty("app-id","").trim();
            secret=properties.getProperty("app-secret","").trim();
            if(id.isBlank()&&secret.isBlank())throw bad("广告服务端配置尚未完成，请联系管理员");
        }
        if(id.isBlank()&&secret.isBlank())return null;
        if(!id.matches("\\d{5,30}")||secret.length()<10||secret.length()>4096)
            throw bad("广告服务端配置不完整，请联系管理员");
        return new Credentials(id,secret,FinanceShopAuthorization.hash("finance-ad-application\u0000"+id+"\u0000"+secret));
    }

    record Credentials(String appId,String secret,String revision){
        @Override public String toString(){return "Advertising application [credentials redacted]";}
    }
}
