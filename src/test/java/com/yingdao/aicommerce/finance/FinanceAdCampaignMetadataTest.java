package com.yingdao.aicommerce.finance;

import org.junit.jupiter.api.Test;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;
import static org.junit.jupiter.api.Assertions.*;

class FinanceAdCampaignMetadataTest {
    @Test void campaignQueryUsesExactIdentityAndExplicitDeletedStatus(){
        var query=FinanceAdCampaignMetadata.query("CAMPAIGN","100001","800001",1);
        assertEquals("100001",query.get("advertiser_id"));
        assertEquals("{\"campaign_ids\":[\"800001\"],\"primary_status\":\"STATUS_ALL\"}",query.get("filtering"));
        assertTrue(FinanceAdCampaignMetadata.query("CAMPAIGN_DELETED","100001","800001",1).get("filtering").toString().contains("STATUS_DELETE"));
        assertThrows(RuntimeException.class,()->FinanceAdCampaignMetadata.query("CAMPAIGN","invalid","800001",1));
    }
    @Test void gmvMetadataWhitelistsTheOfficialTargetAndNeverCopiesBudgetAsSpend(){
        var data=Map.<String,Object>of("campaign_id","800001","campaign_name","Official name","store_id","700001","budget","900","identity_list",List.of(Map.of("store_id","700002")),"access_token","private");
        var result=FinanceAdCampaignMetadata.gmv(data,"800001");
        assertEquals("Official name",result.get("campaign_name"));
        assertEquals(List.of("700001"),result.get("official_store_ids"));
        assertFalse(result.containsKey("budget"));assertFalse(result.containsKey("access_token"));
        assertFalse(result.containsKey("amount"));assertFalse(result.containsKey("credit_amount"));
        assertThrows(RuntimeException.class,()->FinanceAdCampaignMetadata.gmv(data,"800002"));
    }
    @Test void campaignMetadataKeepsObjectiveSeparateFromTheFinancialReportType(){
        var result=FinanceAdCampaignMetadata.campaign(Map.of("list",List.of(Map.of("campaign_id","800001","campaign_name","Name","objective_type","PRODUCT_SALES","campaign_type","REGULAR_CAMPAIGN","campaign_automation_type","MANUAL"))),"800001");
        assertEquals("Name",result.get("campaign_name"));
        assertEquals("PRODUCT_SALES",result.get("campaign_objective"));
        assertEquals("REGULAR_CAMPAIGN",result.get("campaign_structure"));
        assertFalse(result.containsKey("report_type"));
        assertThrows(RuntimeException.class,()->FinanceAdCampaignMetadata.campaign(Map.of("list",List.of(Map.of("campaign_id","800002"))),"800001"));
    }
    @Test void adgroupTargetsIncludeAllExplicitShopsWithoutPromotingIdentityAssetsToTargets(){
        var result=FinanceAdCampaignMetadata.adgroups(Map.of("list",List.of(
                Map.of("advertiser_id","100001","campaign_id","800001","adgroup_id","900001","store_id","700001"),
                Map.of("advertiser_id","100001","campaign_id","800001","adgroup_id","900002","store_id","700002"),
                Map.of("advertiser_id","100001","campaign_id","800001","adgroup_id","900003","identity_authorized_shop_id","700003"))),"100001","800001");
        assertEquals(List.of("700001","700002"),result.get("official_store_ids"));
        assertEquals(true,result.get("has_unknown_targets"));
        assertThrows(RuntimeException.class,()->FinanceAdCampaignMetadata.adgroups(Map.of("list",List.of(Map.of("advertiser_id","100002","campaign_id","800001","adgroup_id","900001"))),"100001","800001"));
    }
    @Test void metadataEnrichmentLeavesHistoricalAmountsAndOwnershipUnchanged(){
        var source=new LinkedHashMap<String,Object>();source.put("campaign_id","800001");source.put("campaign_name","");source.put("shop_id","UNASSIGNED");source.put("currency","USD");source.put("amount",decimal("12.34"));source.put("credit_amount",null);
        var metadata=Map.<String,Object>of("campaign_name","API name","official_store_ids",List.of("700001"),"metadata_endpoint","/campaign/gmv_max/info/");
        var enriched=FinanceAdCampaignMetadata.decorate(source,metadata);
        assertEquals("API name",enriched.get("campaign_name"));assertEquals("UNASSIGNED",enriched.get("shop_id"));
        assertEquals(decimal("12.34"),enriched.get("amount"));assertNull(enriched.get("credit_amount"));
        assertEquals("",source.get("campaign_name"));assertFalse(source.containsKey("official_store_ids"));
        source.put("campaign_name","Original financial name");
        assertEquals("Original financial name",FinanceAdCampaignMetadata.decorate(source,metadata).get("campaign_name"));
    }
}
