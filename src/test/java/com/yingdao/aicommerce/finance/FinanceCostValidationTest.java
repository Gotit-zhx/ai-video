package com.yingdao.aicommerce.finance;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FinanceCostValidationTest {
    private Map<String,Object> row(Object sku,String month,String price) {
        return new LinkedHashMap<>(Map.of("sku",sku,"warehouse","菲律宾","prices",Map.of(month,new BigDecimal(price)),"row",7));
    }
    private Map<String,Object> current(Object sku,String month,String price) {
        var row=row(sku,month,price);row.put("unit","件");row.put("identifierType","SKU");return row;
    }
    private Map<String,Object> metadata() {
        return new LinkedHashMap<>(Map.of("sourceKey","verified-source","sourceVerified",true,"unit","件","identifierType","SKU","nowMonth","2026-11"));
    }
    private Map<String,Object> analyze(List<Map<String,Object>> incoming,List<Map<String,Object>> current,Map<String,Object> metadata,Map<String,Object> policy) {
        return FinanceCostValidation.analyze(incoming,current,metadata,policy);
    }
    private Map<String,Object> analyze(List<Map<String,Object>> incoming,List<Map<String,Object>> current,Map<String,Object> metadata) {
        return analyze(incoming,current,metadata,Map.of());
    }
    @SuppressWarnings("unchecked") private List<Map<String,Object>> rows(Object value) {return (List<Map<String,Object>>)value;}
    private void issue(Map<String,Object> result,String code,String severity) {
        var found=rows(result.get("issues")).stream().filter(x->code.equals(x.get("code"))).findFirst().orElse(null);
        assertNotNull(found,"Expected issue "+code);
        assertEquals(severity,found.get("severity"));
        for(String key:List.of("message","source","action","sku","warehouse","month","row"))assertTrue(found.containsKey(key),key);
    }
    @Test void unknownUnitsCannotBecomeActive() {
        var result=analyze(List.of(row("A","2026-11","2")),List.of(),Map.of("nowMonth","2026-11"));
        assertEquals(true,result.get("requiresReview"));issue(result,"UNIT_UNKNOWN","REVIEW");
    }
    @Test void damagedNumericAndScientificIdentifiersAreBlockedWithoutRepair() {
        for(Object sku:List.of("1.23456789012345E+18",new BigDecimal("1E+18"),1234567890123456L,1.23E18,"=12345","[FORMULA]")) {
            var result=analyze(List.of(row(sku,"2026-11","2")),List.of(),metadata());
            assertEquals(true,result.get("blocked"),sku.toString());issue(result,"IDENTIFIER_INVALID","BLOCK");
        }
    }
    @Test void longTextIdentifiersAndLeadingZerosRemainExact() {
        for(String sku:List.of("1234567890123456789012345","00123456789012345678","AbC-001")) {
            var result=analyze(List.of(row(sku,"2026-11","2")),List.of(),metadata());
            assertEquals(false,result.get("blocked"));assertEquals(sku,rows(result.get("normalizedCosts")).getFirst().get("sku"));
        }
    }
    @Test void hashIgnoresOrderFilenameRowAndNumericScaleAndDeduplicatesEqualCells() {
        var a=row("A","2026-11","2.00");var b=row("B","2026-11","3");
        var first=analyze(List.of(a,b),List.of(),metadata());
        var meta=metadata();meta.put("fileName","renamed.xlsx");a=row("A","2026-11","2.0000");a.put("row",99);
        var second=analyze(List.of(b,a,a),List.of(),meta);
        assertEquals(first.get("semanticHash"),second.get("semanticHash"));assertEquals(2,second.get("changedCells"));
        assertEquals(2,rows(second.get("normalizedCosts")).size());
    }
    @Test void hashPreservesIdentifierCaseDigitsTypeUnitWarehouseAndMonth() {
        var original=row("001Ab","2026-11","2");var hash=analyze(List.of(original),List.of(),metadata()).get("semanticHash");
        for(String sku:List.of("1Ab","001ab"))assertNotEquals(hash,analyze(List.of(row(sku,"2026-11","2")),List.of(),metadata()).get("semanticHash"));
        for(var change:Map.of("identifierType","SPU","unit","包","warehouse","泰国").entrySet()) {
            var changed=new LinkedHashMap<>(original);changed.put(change.getKey(),change.getValue());
            assertNotEquals(hash,analyze(List.of(changed),List.of(),metadata()).get("semanticHash"));
        }
        assertNotEquals(hash,analyze(List.of(row("001Ab","2026-12","2")),List.of(),metadata()).get("semanticHash"));
    }
    @Test void conflictingDuplicateIsBlockedAndHashIndependentOfOrder() {
        var a=row("A","2026-11","2");var b=row("A","2026-11","3");b.put("row",8);
        var first=analyze(List.of(a,b),List.of(),metadata());var second=analyze(List.of(b,a),List.of(),metadata());
        assertEquals(true,first.get("blocked"));assertEquals("BLOCKED",first.get("risk"));issue(first,"DUPLICATE_PRICE_CONFLICT","BLOCK");
        assertEquals(first.get("semanticHash"),second.get("semanticHash"));assertTrue(rows(first.get("normalizedCosts")).isEmpty());
    }
    @Test void zeroIsPreservedButBlockedAndNegativeOrFormulaPriceIsBlocked() {
        var result=analyze(List.of(row("A","2026-11","0")),List.of(),metadata());
        assertEquals(true,result.get("blocked"));issue(result,"ZERO_PRICE","BLOCK");
        assertEquals(BigDecimal.ZERO,((Map<?,?>)rows(result.get("normalizedCosts")).getFirst().get("prices")).get("2026-11"));
        assertEquals(true,analyze(List.of(row("A","2026-11","-1")),List.of(),metadata()).get("blocked"));
        var formula=row("A","2026-11","2");formula.put("prices",Map.of("2026-11","=1+1"));
        issue(analyze(List.of(formula),List.of(),metadata()),"PRICE_INVALID","BLOCK");
    }
    @Test void typedSpuNeedsUniformEvidenceAndMixedNeedsExplicitRowIdentity() {
        var meta=metadata();meta.put("identifierType","SPU");
        var current=current("123456","2026-10","2");current.put("identifierType","SPU");current.put("unit","件");
        var result=analyze(List.of(row("123456","2026-11","2")),List.of(current),meta);
        issue(result,"SPU_UNIFORM_UNCONFIRMED","REVIEW");
        meta.put("spuUniform",true);assertEquals(false,analyze(List.of(row("123456","2026-11","2")),List.of(current),meta).get("requiresReview"));
        meta.put("identifierType","MIXED");issue(analyze(List.of(row("123456","2026-11","2")),List.of(),meta),"IDENTIFIER_TYPE_UNKNOWN","REVIEW");
        var typed=row("123456","2026-11","2");typed.put("identifierType","SPU");
        assertEquals(false,analyze(List.of(typed),List.of(current),meta).get("requiresReview"));
    }
    @Test void differingExistingPriceNeedsRevisionEvenWhenSmall() {
        var result=analyze(List.of(row("A","2026-11","10.01")),List.of(current("A","2026-11","10")),metadata());
        issue(result,"PRICE_REVISION_REQUIRED","REVIEW");assertEquals(true,result.get("requiresReview"));
        var meta=metadata();meta.put("revision",true);
        var revision=analyze(List.of(row("A","2026-11","10.01")),List.of(current("A","2026-11","10")),meta);
        assertEquals(true,revision.get("requiresReview"));issue(revision,"PRICE_OVERRIDE","REVIEW");
        assertEquals("REVIEW",revision.get("risk"));
    }
    @Test void verifiedKnownIdentitySupplementIsSafeButNewOrUnverifiedSourceNeedsReview() {
        var current=List.of(current("A","2026-10","2"));var incoming=List.of(row("A","2026-11","2"));
        var result=analyze(incoming,current,metadata());assertEquals(false,result.get("requiresReview"));assertEquals("LOW",result.get("risk"));
        assertEquals(1,result.get("changedCells"));assertEquals(0,result.get("unchangedCells"));
        issue(analyze(incoming,List.of(),metadata()),"NEW_IDENTIFIER","REVIEW");
        var meta=metadata();meta.put("sourceVerified",false);issue(analyze(incoming,current,meta),"SOURCE_UNVERIFIED","REVIEW");
        meta.put("sourceVerified","true");assertEquals(true,analyze(incoming,current,meta).get("requiresReview"));
    }
    @Test void equalCurrentCellsAreUnchangedAndMissingRowsDoNotDelete() {
        var result=analyze(List.of(row("A","2026-11","2.000")),List.of(current("A","2026-11","2"),current("B","2026-11","4")),metadata());
        assertEquals(0,result.get("changedCells"));assertEquals(1,result.get("unchangedCells"));assertEquals(false,result.get("requiresReview"));
        assertEquals(1,rows(result.get("normalizedCosts")).size());
    }
    @Test void futureAndHistoricalMonthsNeedReviewAndOutsideDeclaredPeriodIsBlocked() {
        for(var example:Map.of("2026-12","FUTURE_MONTH","2026-10","HISTORICAL_MONTH").entrySet())
            issue(analyze(List.of(row("A",example.getKey(),"2")),List.of(),metadata()),example.getValue(),"REVIEW");
        var meta=metadata();meta.put("effectiveFrom","2026-11");meta.put("effectiveTo","2026-12");
        issue(analyze(List.of(row("A","2026-10","2")),List.of(),meta),"MONTH_OUT_OF_SCOPE","BLOCK");
        meta.put("effectiveTo","2026-10");issue(analyze(List.of(row("A","2026-11","2")),List.of(),meta),"PERIOD_INVALID","BLOCK");
        issue(analyze(List.of(row("A","2026-13","2")),List.of(),metadata()),"MONTH_INVALID","BLOCK");
    }
    @Test void thresholdRequiresBothAbsoluteAndPercentageChangesAndUsesStrictExceeds() {
        var meta=metadata();meta.put("revision",true);
        for(String price:List.of("11","12","8"))assertEquals(false,analyze(List.of(row("A","2026-11",price)),List.of(current("A","2026-10","10")),meta).get("requiresReview"));
        var result=analyze(List.of(row("A","2026-11","12.01")),List.of(current("A","2026-10","10")),meta);
        issue(result,"PRICE_CHANGE_HIGH","REVIEW");assertEquals("HIGH",result.get("risk"));
        assertEquals(false,analyze(List.of(row("A","2026-11","1.4")),List.of(current("A","2026-10","1")),meta).get("requiresReview"));
    }
    @Test void supplementCannotEvadePriceJumpProtectionByUsingANewMonth() {
        var result=analyze(List.of(row("A","2026-11","100")),List.of(current("A","2026-10","2")),metadata());
        issue(result,"PRICE_CHANGE_HIGH","REVIEW");assertEquals("HIGH",result.get("risk"));
    }
    @Test void everyPolicyThresholdRejectsNegativeAndMalformedValues() {
        for(String key:List.of("priceChangePercent","priceChangeCny","impactChangeCny","impactChangePercent"))for(Object value:List.of(-1,"bogus",Double.NaN))
            issue(analyze(List.of(row("A","2026-11","2")),List.of(),metadata(),Map.of(key,value)),"POLICY_INVALID","BLOCK");
        var meta=metadata();meta.put("revision",true);
        issue(analyze(List.of(row("A","2026-11","10.01")),List.of(current("A","2026-11","10")),meta,Map.of("priceChangePercent",0,"priceChangeCny",0)),"PRICE_CHANGE_HIGH","REVIEW");
    }
    @Test void unitOrWarehouseChangeNeverBorrowsHistoryFromAnotherIdentity() {
        for(var entry:Map.of("unit","包","warehouse","泰国").entrySet()) {
            var changed=row("A","2026-11","2");changed.put(entry.getKey(),entry.getValue());
            issue(analyze(List.of(changed),List.of(current("A","2026-10","2")),metadata()),"IDENTITY_CHANGED","REVIEW");
        }
    }
    @Test void onlyVerifiedWarehouseAliasCanResolveStableWarehouseIdentity() {
        var meta=metadata();meta.put("warehouseAliases",Map.of("菲律宾","warehouse-ph","旧菲律宾仓","warehouse-ph"));
        var current=current("A","2026-10","2");current.put("warehouse","旧菲律宾仓");
        var result=analyze(List.of(row("A","2026-11","2")),List.of(current),meta);
        assertEquals(false,result.get("requiresReview"));assertEquals("warehouse-ph",rows(result.get("normalizedCosts")).getFirst().get("warehouseId"));
        meta.put("sourceVerified",false);issue(analyze(List.of(row("A","2026-11","2")),List.of(current),meta),"IDENTITY_CHANGED","REVIEW");
    }
    @Test void missingInputsAndNonCnyNeverBecomeSafeByDefault() {
        issue(analyze(List.of(row("A","2026-11","2")),List.of(),Map.of()),"NOW_MONTH_UNKNOWN","REVIEW");
        var meta=metadata();meta.put("currency","USD");issue(analyze(List.of(row("A","2026-11","2")),List.of(),meta),"CURRENCY_UNSUPPORTED","BLOCK");
        issue(analyze(List.of(),List.of(),metadata()),"EMPTY_COSTS","BLOCK");
        var bad=row("A","2026-11","2");bad.put("warehouse","");issue(analyze(List.of(bad),List.of(),metadata()),"WAREHOUSE_UNKNOWN","BLOCK");
    }
    @Test void conflictingHistoricalPricesCannotBeChosenArbitrarilyForSupplement() {
        var result=analyze(List.of(row("A","2026-11","2")),List.of(current("A","2026-10","2"),current("A","2026-10","2.01")),metadata());
        issue(result,"CURRENT_PRICE_CONFLICT","BLOCK");assertEquals(true,result.get("blocked"));
    }
    @Test void damagedNumericIdCannotDeduplicateWithValidTextId() {
        var broken=analyze(List.of(row(new BigDecimal("1E+18"),"2026-11","2")),List.of(),metadata());
        var intact=analyze(List.of(row("1000000000000000000","2026-11","2")),List.of(),metadata());
        assertNotEquals(broken.get("semanticHash"),intact.get("semanticHash"));
    }
    @Test void verifiedProfileCannotRetroactivelyVerifyLegacyUnitsOrIdentityType() {
        for(String missing:List.of("unit","identifierType")) {
            var legacy=current("A","2026-10","2");legacy.remove(missing);
            var result=analyze(List.of(row("A","2026-11","2")),List.of(legacy),metadata());
            assertEquals(true,result.get("requiresReview"));issue(result,"LEGACY_UNVERIFIED","REVIEW");
        }
    }
    @Test void rejectedNumericIdentifierRetainsOriginalEvidenceInIssue() {
        var result=analyze(List.of(row(new BigDecimal("1E+18"),"2026-11","2")),List.of(),metadata());
        assertEquals("1E+18",rows(result.get("issues")).stream().filter(i->"IDENTIFIER_INVALID".equals(i.get("code"))).findFirst().orElseThrow().get("sku"));
    }
    @Test void futureOnlyPriceCannotSupplyASafeSupplementBaseline() {
        var result=analyze(List.of(row("A","2026-11","2")),List.of(current("A","2026-12","2")),metadata());
        assertEquals(true,result.get("requiresReview"));assertNotEquals("LOW",result.get("risk"));
        issue(result,"PRICE_BASELINE_MISSING","REVIEW");
    }
    @Test void outOfRangeExponentsAreBlockedAndCanonicalEncodingStaysBounded() throws Exception {
        var encoder=FinanceCostValidation.class.getDeclaredMethod("canonical",Object.class);encoder.setAccessible(true);
        for(String value:List.of("1E+512","1E-512")) {
            var result=analyze(List.of(row("A","2026-11",value)),List.of(),metadata());
            assertEquals(true,result.get("blocked"));issue(result,"PRICE_INVALID","BLOCK");
            String encoded=(String)encoder.invoke(null,new BigDecimal(value));
            assertTrue(encoded.length()<32,"Exponent must not expand into hundreds of zeroes");
        }
    }
    @Test void reorderedAliasesHaveIdenticalNormalizedWarehousePricesAndEarliestRow() {
        var meta=metadata();meta.put("warehouseAliases",Map.of("菲律宾","ph","旧菲律宾仓","ph"));
        var first=row("A","2026-11","2");
        var alias=row("A","2026-11","2.00");alias.put("warehouse","旧菲律宾仓");alias.put("row",8);
        var historical=row("A","2026-10","1");historical.put("row",2);
        var forward=analyze(List.of(first,alias,historical),List.of(),meta);
        var reverse=analyze(List.of(historical,alias,first),List.of(),meta);
        assertEquals(forward.get("semanticHash"),reverse.get("semanticHash"));
        assertEquals(forward.get("normalizedCosts"),reverse.get("normalizedCosts"));
        var normalized=rows(forward.get("normalizedCosts"));assertEquals(1,normalized.size());
        assertEquals("旧菲律宾仓",normalized.getFirst().get("warehouse"));assertEquals(2,normalized.getFirst().get("row"));
        assertEquals(Map.of("2026-10",BigDecimal.ONE,"2026-11",new BigDecimal("2")),normalized.getFirst().get("prices"));
    }
    @Test void analysisDoesNotMutateCallerRowsMetadataOrPolicy() {
        var incoming=List.of(Collections.unmodifiableMap(row("A","2026-11","2.00")));
        var meta=Collections.unmodifiableMap(metadata());var before=new LinkedHashMap<>(incoming.getFirst());
        assertEquals(analyze(incoming,List.of(),meta),analyze(incoming,List.of(),meta));assertEquals(before,incoming.getFirst());
    }
}
