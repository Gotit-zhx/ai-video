package com.yingdao.aicommerce.finance;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import com.yingdao.aicommerce.shop.application.ShopCredentialCipher;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** User-authorized credential save and exact-ID bindings. Secrets enter through stdin, never argv or output. */
public final class FinanceQianyiSetup {
    static String platform(Object raw){
        String value=text(raw).toUpperCase(Locale.ROOT).replace("_","");
        return Set.of("TIKTOK","TIKTOKSHOP").contains(value)?"TIKTOK":value;
    }
    public static void main(String[] args)throws Exception{
        if(args.length!=1||!args[0].matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("Company ID required");
        byte[] bytes=System.in.readNBytes(8193);if(bytes.length>8192)throw new IllegalArgumentException("Credential input too large");
        try(var ds=new com.zaxxer.hikari.HikariDataSource()){
            ds.setJdbcUrl(System.getenv("DB_URL"));ds.setUsername(System.getenv("DB_USERNAME"));ds.setPassword(System.getenv("DB_PASSWORD"));
            ds.setMaximumPoolSize(2);ds.setMinimumIdle(1);
            var repo=new FinanceRepository(new org.springframework.jdbc.core.JdbcTemplate(ds),new tools.jackson.databind.ObjectMapper());
            var scope=companyScope(args[0]);var finance=new FinanceService(repo,null,null,null);
            var locals=finance.shops(scope);if(locals.isEmpty())throw new IllegalStateException("No active company shops");
            var environment=new org.springframework.core.env.StandardEnvironment();
            for(var source:new org.springframework.boot.env.YamlPropertySourceLoader().load("live-application",
                new org.springframework.core.io.ClassPathResource("application.yml")))environment.getPropertySources().addLast(source);
            var upstream=new FinanceQianyiClient(repo);var cipher=new ShopCredentialCipher(environment.getRequiredProperty("app.shop-auth.credential-key"));
            var service=new FinanceQianyi(repo,finance,upstream,cipher);
            var body=new LinkedHashMap<>(repo.read(new String(bytes,StandardCharsets.UTF_8)));
            Arrays.fill(bytes,(byte)0);
            String firstShop=text(locals.getFirst().get("id"));
            service.saveCredentials(scope,firstShop,body);
            var credentials=service.credentials(scope,firstShop,body,Map.of());
            body.put("appSecret","");
            var remote=new ArrayList<Map<String,Object>>();long total=0;
            for(int page=1;page<=50;page++){
                var result=upstream.query(credentials,"QUERY_SHOP_LIST",Map.of("page",page,"pageSize",200));
                if(result.items().isEmpty()&&(page-1L)*200<result.total())throw new IllegalStateException("Incomplete shop page");
                for(var item:result.items()){var copy=new LinkedHashMap<>(item);copy.put("_page",page);remote.add(copy);}
                total=result.total();if(page*200L>=total)break;
                if(page==50)throw new IllegalStateException("Shop discovery exceeded bounded limit");
            }
            int matched=0,pending=0;
            for(var local:locals){
                String id=text(local.get("external_shop_id")),shop=text(local.get("id"));
                var matches=remote.stream().filter(r->!id.isBlank()&&id.equals(text(r.get("onlineShopId")))&&platform(local.get("platform")).equals(platform(r.get("platform")))).toList();
                if(matches.size()!=1){pending++;System.out.println("UNBOUND "+first(local,"display_name","name")+" exact_matches="+matches.size());continue;}
                var selected=matches.getFirst();var old=service.connection(scope,shop);
                if(!old.isEmpty()){
                    if(text(selected.get("shopId")).equals(old.get("remote_shop_id"))){matched++;continue;}
                    pending++;System.out.println("PRESERVED existing binding for "+first(local,"display_name","name"));continue;
                }
                var form=new LinkedHashMap<>(body);
                form.put("remoteShopId",text(selected.get("shopId")));form.put("remotePage",selected.get("_page"));
                form.put("costCurrency","CNY");form.put("enabled",true);
                form.put("syncStart",LocalDate.now().minusMonths(3).withDayOfMonth(1).toString());
                service.save(scope,shop,form);matched++;
                System.out.println("BOUND "+first(local,"display_name","name")+" -> "+text(selected.get("name")));
            }
            System.out.println("Encrypted company credentials saved; remote_shops="+total+", bound="+matched+", unmatched="+pending+". Cost basis remains unverified.");
        }
    }
}
