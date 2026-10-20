package com.yingdao.aicommerce.finance;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.saas.application.OrganizationAccessPolicy;
import com.yingdao.aicommerce.shop.application.ShopAuthorizationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.*;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

@Service
public class FinanceService {
    final FinanceRepository repo; final OrganizationAccessPolicy access; final ShopAuthorizationService shops;final FinanceFx fx;
    public FinanceService(FinanceRepository repo,OrganizationAccessPolicy access,ShopAuthorizationService shops,FinanceFx fx){
        this.repo=repo;this.access=access;this.shops=shops;this.fx=fx;
    }
    public Scope authorize(PlatformPrincipal principal){return scope(principal,access.isPlatformAdmin(principal));}
    public List<Map<String,Object>> shops(Scope s){
        return repo.db.queryForList("""
            SELECT s.id,s.name,s.display_name,s.platform,s.region,s.external_shop_id,s.status authorization_status,
            COALESCE(f.enabled,FALSE) enabled, f.currency,f.timezone,
            (SELECT MAX(last_success_at) FROM finance_sync_states st WHERE st.organization_id=f.organization_id
              AND st.user_id=f.user_id AND st.shop_id=f.shop_id) last_success_at
            FROM shops s LEFT JOIN finance_shops f ON f.shop_id=s.id AND f.organization_id=? AND f.user_id=?
            WHERE s.organization_id=? AND s.status<>'DELETED' ORDER BY s.platform,s.name
            """,s.organizationId(),s.userId(),s.organizationId()).stream().map(row->{
                if(row.get("currency")==null)row.put("currency",currencyFor(text(row.get("region"))));
                if(row.get("timezone")==null)row.put("timezone",timezoneFor(text(row.get("region"))));return row;
            }).toList();
    }
    public Map<String,Object> ownedShop(Scope s,String id){
        return shops(s).stream().filter(r->id.equals(r.get("id"))).findFirst().orElseThrow(()->bad("没有找到本企业的店铺"));
    }
    @Transactional
    public void configure(Scope s,String id,boolean enabled,String currency,String timezone){
        Map<String,Object> shop=ownedShop(s,id);
        if(!Set.of("SHOPEE","TIKTOK_SHOP").contains(text(shop.get("platform"))))throw bad("仅支持 Shopee 与 TikTok Shop");
        if(currency==null||!currency.matches("[A-Z]{3}"))throw bad("请选择正确币种");
        try{ZoneId.of(timezone);}catch(Exception e){throw bad("请选择正确时区");}
        repo.db.update("""
            INSERT INTO finance_shops(organization_id,user_id,shop_id,enabled,currency,timezone) VALUES(?,?,?,?,?,?)
            ON DUPLICATE KEY UPDATE enabled=VALUES(enabled),currency=VALUES(currency),timezone=VALUES(timezone),updated_at=UTC_TIMESTAMP(6)
            """,s.organizationId(),s.userId(),id,enabled,currency,timezone);
        var channels=new ArrayList<>(List.of("ORDER","REFUND","SETTLEMENT"));
        if("TIKTOK_SHOP".equals(shop.get("platform")))channels.add("PENDING");
        if(enabled)for(String channel:channels)repo.db.update("""
            INSERT IGNORE INTO finance_sync_states(organization_id,user_id,shop_id,channel) VALUES(?,?,?,?)
            """,s.organizationId(),s.userId(),id,channel);
    }
    public List<Map<String,Object>> syncStates(Scope s){
        return repo.db.queryForList("""
            SELECT shop_id,channel,status,error_message,next_at,last_success_at,coverage_start
            FROM finance_sync_states WHERE organization_id=? AND user_id=? ORDER BY shop_id,channel
            """,s.organizationId(),s.userId());
    }
    public void sync(Scope s,String shopId){
        ownedShop(s,shopId);
        Integer enabled=repo.db.queryForObject("SELECT COUNT(*) FROM finance_shops WHERE organization_id=? AND user_id=? AND shop_id=? AND enabled=TRUE",
                Integer.class,s.organizationId(),s.userId(),shopId);
        if(enabled==null||enabled==0)throw bad("请先在财务店铺管理中开启同步");
        repo.db.update("""
            UPDATE finance_sync_states SET next_at=UTC_TIMESTAMP(6),error_message=NULL,failures=0,
            status=CASE WHEN lease_until>UTC_TIMESTAMP(6) THEN status ELSE 'READY' END
            WHERE organization_id=? AND user_id=? AND shop_id=?
            """,s.organizationId(),s.userId(),shopId);
    }
    public Map<String,Object> dashboard(Scope s,Filter requested,boolean cny,String snapshotId){
        Filter all=new Filter(requested.start(),requested.end(),requested.shopId(),requested.platform(),"",requested.granularity());
        List<Map<String,Object>> currencies=repo.grouped(s,all,"currency,kind");
        List<String> codes=currencies.stream().map(r->text(r.get("currency"))).distinct().sorted().toList();
        String currency=requested.currency().isBlank()?(cny||codes.isEmpty()?"":codes.getFirst()):requested.currency();
        Filter f=new Filter(requested.start(),requested.end(),requested.shopId(),requested.platform(),currency,requested.granularity());
        List<Map<String,Object>> totals=repo.grouped(s,f,"kind");
        FinanceFx.Quote quote=cny?fx.quote(snapshotId):null;
        if(cny)totals=FinanceFx.groups(totals,quote,"kind");
        LinkedHashMap<String,Map<String,Object>> points=new LinkedHashMap<>();
        for(LocalDate d=f.start();!d.isAfter(f.end());d=d.plusDays(1)){
            String key=bucket(d,f.granularity());points.computeIfAbsent(key,k->{var m=new LinkedHashMap<String,Object>();m.put("period",k);return m;});
        }
        var daily=repo.grouped(s,f,"record_date,kind");
        if(cny)daily=FinanceFx.groups(daily,quote,"record_date","kind");
        for(var row:daily){
            String key=bucket(LocalDate.parse(text(row.get("record_date"))),f.granularity());
            var point=points.get(key);String kind=text(row.get("kind"));if(point==null)continue;
            BigDecimal value=decimal(row.get("amount"));if(value!=null)point.merge(kind,value,(a,b)->((BigDecimal)a).add((BigDecimal)b));
            point.merge(kind+"_count",number(row.get("records")),(a,b)->(Long)a+(Long)b);
        }
        var out=new LinkedHashMap<String,Object>();
        out.put("filters",Map.of("start",f.start(),"end",f.end(),"shopId",f.shopId(),"platform",f.platform(),"currency",currency,"granularity",f.granularity(),"displayCurrency",cny?"CNY":currency));
        if(cny)out.put("fx",quote.metadata());
        out.put("currencies",codes);out.put("totals",totals);out.put("trend",new ArrayList<>(points.values()));
        out.put("shops",shops(s));
        var ranking=repo.grouped(s,f,"shop_id,kind");var fees=repo.grouped(s,f,"category,kind");var statuses=repo.grouped(s,f,"status,kind");
        out.put("ranking",cny?FinanceFx.groups(ranking,quote,"shop_id","kind"):ranking);
        out.put("fees",(cny?FinanceFx.groups(fees,quote,"category","kind"):fees).stream().filter(r->"FEE".equals(r.get("kind"))).toList());
        out.put("statuses",cny?FinanceFx.groups(statuses,quote,"status","kind"):statuses);out.put("sync",syncStates(s));
        out.put("updatedAt",Instant.now().toString());
        out.put("basis","订单按创建日期，结算与费用按平台结算日期；广告按广告账户日期。原币分别统计，已结算不代表银行到账。");
        return out;
    }
}
