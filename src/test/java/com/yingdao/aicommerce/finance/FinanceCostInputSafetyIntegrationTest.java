package com.yingdao.aicommerce.finance;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import static com.yingdao.aicommerce.finance.FinanceData.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual HTTP entry methods and a disposable database, never a shared business database. */
class FinanceCostInputSafetyIntegrationTest extends FinanceConfigurationIntegrationTest {
    @Test void conflictingHttpImportIsStagedWithoutChangingActiveSourceBookOrCosts() throws Exception {
        String group=service.saveGroup(owner,null,"人工输入"),shop=groupedShop(owner,"manual-safe",group);
        var workspace=new FinanceCostWorkspace(repo,service);
        repo.transaction(()->workspace.importCosts(owner,owner.userId(),group,csv("10"),"original.csv"));
        var sources=db.queryForList("SELECT * FROM finance_cost_sources");
        var books=db.queryForList("SELECT * FROM finance_cost_books");
        var queue=db.queryForList("SELECT * FROM finance_cost_rebuilds");
        var response=new FinanceCostController(workspace,service).upload(group,new MockMultipartFile("file","new.csv","text/csv",csv("20")),principal());
        assertEquals(true,map(response.data()).get("staged"),"All original human HTTP writes must stage, including conflicting imports");
        assertEquals("REVIEW",map(response.data()).get("status"));
        assertEquals(sources,db.queryForList("SELECT * FROM finance_cost_sources"));
        assertEquals(books,db.queryForList("SELECT * FROM finance_cost_books"));
        assertEquals(queue,db.queryForList("SELECT * FROM finance_cost_rebuilds"));
    }
    FinanceCostInputSafety safety;FinanceCostWorkspace workspace;String group,shop;
    void inputFixture(){
        group=service.saveGroup(owner,null,"核对");shop=groupedShop(owner,"guard-shop",group);
        workspace=new FinanceCostWorkspace(repo,service);safety=new FinanceCostInputSafety(repo,service,workspace);
    }
    com.yingdao.aicommerce.auth.PlatformPrincipal reviewer(){return new com.yingdao.aicommerce.auth.PlatformPrincipal("reviewer","org","heyi","r","r","",true,List.of(),List.of("finance:manage"));}
    Map<String,Object> meta(){return Map.of("unit","包","identifierType","SKU","reason","人工核对","evidenceRef","采购单-123");}
    Map<String,Object> stage(String price){return safety.stage(principal(),"IMPORT",group,"","",Map.of(),csv(price),"candidate.csv",meta());}
    String id(Map<String,Object> staged){return text(staged.get("changeId"));}
    Map<String,Object> ack(Map<String,Object> detail){return Map.of("version",detail.get("version"),"acknowledgeImpact",true,"acknowledgeHistorical",true,"reason","复核采购单","evidenceRef","采购单-123","revision",true);}
    Map<String,Object> info(Map<String,Object> staged){return safety.detail(principal(),id(staged));}
    void oldCost(){repo.transaction(()->workspace.importCosts(owner,"u",group,csv("10"),"old.csv"));}
    @Test void newUnverifiedInputAllowsOwnerConfirmationAndIdempotency(){
        inputFixture();var draft=stage("10");assertEquals(true,draft.get("staged"));var detail=info(draft);
        assertEquals(false,detail.get("requiresIndependentReview"));assertEquals(true,detail.get("canConfirm"));
        assertEquals("SCOPE_UPPER_BOUND",map(detail.get("impact")).get("coverage"));assertNull(map(detail.get("impact")).get("amountChange"));
        assertThrows(RuntimeException.class,()->safety.confirm(principal(),id(draft),Map.of("version",detail.get("version"))));
        var applied=safety.confirm(principal(),id(draft),ack(detail));assertEquals("APPLIED",applied.get("status"));
        var before=db.queryForList("SELECT * FROM finance_cost_books");safety.confirm(principal(),id(draft),ack(detail));assertEquals(before,db.queryForList("SELECT * FROM finance_cost_books"));
        assertEquals(true,stage("10").get("duplicate"));
    }
    @Test void overwrittenPriceNeedsIndependentReviewerAndRevisionIntent(){
        inputFixture();oldCost();var staged=stage("20");var d=info(staged);assertEquals("REVIEW",d.get("status"));
        assertEquals(true,d.get("requiresIndependentReview"));assertEquals(false,d.get("canConfirm"));
        assertThrows(RuntimeException.class,()->safety.confirm(principal(),id(staged),ack(d)));
        var noRevision=new LinkedHashMap<>(ack(d));noRevision.put("revision",false);
        assertThrows(RuntimeException.class,()->safety.confirm(reviewer(),id(staged),noRevision));
        assertEquals("APPLIED",safety.confirm(reviewer(),id(staged),ack(d)).get("status"));
        assertEquals("20",decimal(map(rows(workspace.book(owner,group).get("costs")).getFirst().get("prices")).get("2026-10")).stripTrailingZeros().toPlainString());
    }
    @Test void zeroAndNumericLongXlsxAreBlockedAndCannotBeApproved(){
        inputFixture();var zero=stage("0");assertEquals("BLOCKED",zero.get("status"));assertThrows(RuntimeException.class,()->safety.confirm(reviewer(),id(zero),ack(info(zero))));
        byte[] unsafe=FinanceWorkbook.xlsx(Map.of("Sheet",List.of(List.of("型号","仓库","202610"),List.of("123456789012345678","海外仓-菲律宾","10"))),Map.of("Sheet",Set.of(0,2)));
        var d=safety.stage(principal(),"IMPORT",group,"","",Map.of(),unsafe,"unsafe.xlsx",meta());assertEquals("BLOCKED",d.get("status"));
        var issue=rows(map(info(d).get("validation")).get("issues")).getFirst();assertEquals(2,number(issue.get("row")));assertTrue(text(issue.get("message")).contains("编号"));
        byte[] safe=FinanceWorkbook.xlsx(Map.of("Sheet",List.of(List.of("型号","仓库","202610"),List.of("123456789012345678","海外仓-菲律宾","10"))));
        assertNotEquals("BLOCKED",safety.stage(principal(),"IMPORT",group,"","",Map.of(),safe,"text.xlsx",meta()).get("status"));
    }
    @Test void stalePreviewCannotApplyAndMetadataRevisionRefreshesVersion(){
        inputFixture();var a=stage("10");var b=stage("20");assertFalse(id(a).isBlank());safety.confirm(principal(),id(a),ack(info(a)));
        assertThrows(RuntimeException.class,()->safety.confirm(principal(),id(b),ack(info(b))));assertEquals("STALE",info(b).get("status"));
        var revised=safety.revise(principal(),id(b),Map.of("version",info(b).get("version"),"metadata",meta()));
        assertEquals("REVIEW",revised.get("status"));assertEquals(true,revised.get("requiresIndependentReview"));
        assertEquals("APPLIED",safety.confirm(reviewer(),id(b),ack(revised)).get("status"));
    }
    @Test void companyAndManagementCannotBeForgedAndRawFileIsPrivate(){
        inputFixture();var draft=stage("10");assertFalse(id(draft).isBlank());
        var foreign=new com.yingdao.aicommerce.auth.PlatformPrincipal("x","other-org","other","x","x","",true,List.of("ADMIN"),List.of());
        assertThrows(RuntimeException.class,()->safety.detail(foreign,id(draft)));
        assertThrows(RuntimeException.class,()->safety.confirm(foreign,id(draft),ack(info(draft))));
        assertFalse(info(draft).containsKey("originalBytes"));assertFalse(info(draft).containsKey("payload"));
        assertEquals(1,number(safety.list(principal(),group,shop,1).get("total")),"shop filter includes group-wide drafts");
    }
    @Test void rejectRetainsEvidenceAndUndoCannotOverwriteLaterChanges(){
        inputFixture();var rejected=stage("10");assertFalse(id(rejected).isBlank());
        safety.reject(principal(),id(rejected),Map.of("version",info(rejected).get("version"),"reason","资料有误"));assertEquals("REJECTED",info(rejected).get("status"));
        var draft=stage("10");safety.confirm(principal(),id(draft),ack(info(draft)));
        var next=stage("20");safety.confirm(reviewer(),id(next),ack(info(next)));
        assertThrows(RuntimeException.class,()->safety.undo(principal(),id(draft),ack(info(draft))));
        assertNotNull(db.queryForObject("SELECT original_bytes FROM finance_cost_input_changes WHERE id=?",byte[].class,id(rejected)));
    }
    @Test void hiddenSettingsRulesAndClientVerifiedSourceAreRejected(){
        inputFixture();assertThrows(RuntimeException.class,()->safety.stage(principal(),"SETTINGS",group,shop,"",Map.of("rules",Map.of("allowZero",true)),null,"",meta()));
        var forged=new LinkedHashMap<>(meta());forged.put("sourceVerified",true);
        assertThrows(RuntimeException.class,()->safety.stage(principal(),"IMPORT",group,"","",Map.of(),csv("10"),"f.csv",forged));
    }
    Map<String,Object> manualBody(){return new LinkedHashMap<>(Map.of("product_id","p","sku_id","v","code","MANUAL","warehouse","海外仓-菲律宾","factor",1,"confirmed",true,"unit","包","unitPriceCny","8","version",0,"metadata",meta()));}
    @Test void everyOriginalManualHttpEndpointStagesWithoutChangingEffectiveConfiguration(){
        inputFixture();oldCost();var controller=new FinanceCostController(workspace,service);
        var create=map(controller.manualMapping(shop,manualBody(),principal()).data());assertEquals(true,create.get("staged"));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_manual_costs",Integer.class));safety.confirm(principal(),id(create),ack(info(create)));
        String manual=db.queryForObject("SELECT id FROM finance_manual_costs",String.class);
        var update=map(controller.editManual(group,manual,Map.of("version",1,"unit","包","unitPriceCny","9","confirmed",true,"metadata",meta()),principal()).data());assertEquals(true,update.get("staged"));
        assertEquals(8,db.queryForObject("SELECT unit_price_cny FROM finance_manual_costs",Integer.class));
        var deletion=map(controller.deleteManual(group,manual,Map.of("version",1,"metadata",meta()),principal()).data());assertEquals(true,deletion.get("staged"));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM finance_manual_costs",Integer.class));
        var mapping=map(controller.mapping(shop,Map.of("product_id","p2","sku_id","v2","warehouse","海外仓-菲律宾","code","PACK","factor",1,"confirmed",true,"version",0,"metadata",meta()),principal()).data());assertEquals(true,mapping.get("staged"));
        var settings=map(controller.settings(shop,settingsBody(0),principal()).data());assertEquals(true,settings.get("staged"));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_cost_settings",Integer.class));
        String source=db.queryForObject("SELECT id FROM finance_cost_sources",String.class);var removal=map(controller.removeSource(group,source,Map.of("metadata",meta()),principal()).data());assertEquals(true,removal.get("staged"));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM finance_cost_sources",Integer.class));
    }
    Map<String,Object> settingsBody(int version){return Map.of("warehouse","海外仓-菲律宾","rules",Map.of("unit","包","basis","order","missing","pending","freight","","inbound","","currency","CNY"),"version",version,"metadata",meta());}
    @Test void settingsAndMappingCanApplyAndUndoOnlyTheirOwnTargets(){
        inputFixture();oldCost();groupedShop(owner,"sibling",group);
        var settings=safety.stage(principal(),"SETTINGS",group,shop,"",settingsBody(0),null,"",meta());assertEquals(true,settings.get("staged"));
        safety.confirm(reviewer(),id(settings),ack(info(settings)));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM finance_cost_settings",Integer.class));
        var reverse=safety.undo(principal(),id(settings),ack(info(settings)));assertEquals("REVIEW",reverse.get("status"));
        assertEquals("APPLIED",safety.confirm(reviewer(),id(reverse),ack(info(reverse))).get("status"));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_cost_settings",Integer.class));
    }
    @Test void failedApplyRollsBackSourcesBooksAndDraftState(){
        inputFixture();var draft=stage("10");assertEquals(true,draft.get("staged"));
        db.execute("CREATE TRIGGER reject_cost_book BEFORE INSERT ON finance_cost_books FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected rollback test'");
        try{assertThrows(RuntimeException.class,()->safety.confirm(principal(),id(draft),ack(info(draft))));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_cost_sources",Integer.class));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_cost_books",Integer.class));assertEquals("REVIEW",info(draft).get("status"));}
        finally{db.execute("DROP TRIGGER reject_cost_book");}
        assertEquals("APPLIED",safety.confirm(principal(),id(draft),ack(info(draft))).get("status"));
    }
    @Test void historicalScopeRequiresSeparateAcknowledgment(){
        inputFixture();record(owner,shop,"ORDER","historical",java.math.BigDecimal.TEN,Map.of());var draft=stage("10");var detail=info(draft);assertEquals(true,map(detail.get("impact")).get("hasHistorical"));
        var request=new LinkedHashMap<>(ack(detail));request.put("acknowledgeHistorical",false);assertThrows(RuntimeException.class,()->safety.confirm(principal(),id(draft),request));assertEquals("APPLIED",safety.confirm(principal(),id(draft),ack(detail)).get("status"));
    }
    @Test void removalAndUndoRetainOriginalEvidenceAndReimportAfterWithdrawalIsLegitimate(){
        inputFixture();var first=stage("10");safety.confirm(principal(),id(first),ack(info(first)));String source=db.queryForObject("SELECT id FROM finance_cost_sources",String.class);
        var removal=safety.stage(principal(),"REMOVE_SOURCE",group,"",source,Map.of(),null,"",meta());safety.confirm(reviewer(),id(removal),ack(info(removal)));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_cost_sources",Integer.class));
        var reverse=safety.undo(principal(),id(removal),ack(info(removal)));assertEquals("APPLIED",safety.confirm(reviewer(),id(reverse),ack(info(reverse))).get("status"));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM finance_cost_sources",Integer.class));
        var removal2=safety.stage(principal(),"REMOVE_SOURCE",group,"",source,Map.of(),null,"",meta());safety.confirm(reviewer(),id(removal2),ack(info(removal2)));
        var repeat=stage("10");assertEquals(false,repeat.get("duplicate"));assertEquals("APPLIED",safety.confirm(principal(),id(repeat),ack(info(repeat))).get("status"));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM finance_cost_sources",Integer.class));
    }
    @Test void mixedBatchCanBeReviewedAndChangingDeclaredTypeCannotHideOverwrite(){
        inputFixture();oldCost();var metadata=new LinkedHashMap<>(meta());metadata.put("identifierType","MIXED");
        var draft=safety.stage(principal(),"IMPORT",group,"","",Map.of(),csv("20"),"mixed.csv",metadata);assertEquals("REVIEW",draft.get("status"));var detail=info(draft);assertEquals(true,detail.get("requiresIndependentReview"));assertEquals(true,safety.detail(reviewer(),id(draft)).get("canConfirm"));
        assertEquals("APPLIED",safety.confirm(reviewer(),id(draft),ack(detail)).get("status"));
        metadata.put("identifierType","SPU");metadata.put("spuUniform",true);
        var another=safety.stage(principal(),"IMPORT",group,"","",Map.of(),csv("30"),"spu.csv",metadata);assertEquals(true,info(another).get("requiresIndependentReview"));
    }
    @Test void conflictingPricesAcrossDeclaredRowTypesStillBlock(){
        inputFixture();byte[] bytes="仓库,型号,编号类型,202610\n海外仓-菲律宾,PACK,SKU,10\n海外仓-菲律宾,PACK,SPU,20\n".getBytes(StandardCharsets.UTF_8);
        var draft=safety.stage(principal(),"IMPORT",group,"","",Map.of(),bytes,"cross-types.csv",Map.of("unit","包","identifierType","MIXED","spuUniform",true));assertEquals("BLOCKED",draft.get("status"));
    }
    @Test void pendingSemanticRepeatRetainsOneOriginalFile(){
        inputFixture();var first=stage("10.00");var second=stage("10");assertEquals(id(first),id(second));assertEquals(true,second.get("duplicate"));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM finance_cost_input_changes WHERE original_bytes IS NOT NULL",Integer.class));
    }
    @Test void blankWarehouseForeignCurrencyFormulaAndUnknownIdentifierStayBlocked(){
        inputFixture();for(String content:List.of("仓库,型号,202610\n海外仓-菲律宾,A,10\n,B,20\n","仓库,型号,币种,202610\n海外仓-菲律宾,A,USD,10\n","仓库,型号,202610\n海外仓-菲律宾,=BAD,10\n","仓库,型号,202610\n海外仓-菲律宾,1.23E+20,10\n"))assertEquals("BLOCKED",safety.stage(principal(),"IMPORT",group,"","",Map.of(),content.getBytes(StandardCharsets.UTF_8),"invalid.csv",meta()).get("status"));
    }
    @Test void unchangedSettingsMappingAndManualPriceDoNotQueueRecalculation(){
        inputFixture();oldCost();var controller=new FinanceCostController(workspace,service);
        var settings=map(controller.settings(shop,settingsBody(0),principal()).data());safety.confirm(reviewer(),id(settings),ack(info(settings)));
        var before=db.queryForList("SELECT * FROM finance_cost_rebuilds");var again=map(controller.settings(shop,settingsBody(1),principal()).data());assertEquals(true,again.get("duplicate"));assertEquals(before,db.queryForList("SELECT * FROM finance_cost_rebuilds"));
    }
    @Test void rereadingAnOlderStillPresentFileCanRestoreAnOverwrittenPrice(){
        inputFixture();var original=stage("10");safety.confirm(principal(),id(original),ack(info(original)));var newer=stage("20");safety.confirm(reviewer(),id(newer),ack(info(newer)));
        var restore=stage("10");assertEquals(false,restore.get("duplicate"));safety.confirm(reviewer(),id(restore),ack(info(restore)));
        assertEquals(0,decimal("10").compareTo(decimal(map(rows(workspace.book(owner,group).get("costs")).getFirst().get("prices")).get("2026-10"))));
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM finance_cost_input_changes WHERE original_bytes IS NOT NULL",Integer.class),"identical original content is retained once across reimports");
        var undo=safety.undo(principal(),id(restore),ack(info(restore)));safety.confirm(reviewer(),id(undo),ack(info(undo)));
        assertEquals(0,decimal("20").compareTo(decimal(map(rows(workspace.book(owner,group).get("costs")).getFirst().get("prices")).get("2026-10"))));
    }
    @Test void readOnlyIdentityGetsNoWriteActionsAndCannotConfirm(){
        inputFixture();var draft=stage("10");var policy=org.mockito.Mockito.mock(com.yingdao.aicommerce.saas.application.OrganizationAccessPolicy.class);
        var limitedService=new FinanceService(repo,policy,auth,fx);var guarded=new FinanceCostInputSafety(repo,limitedService,workspace);
        var viewer=new com.yingdao.aicommerce.auth.PlatformPrincipal("viewer","org","heyi","v","v","",true,List.of(),List.of("finance:view"));
        var detail=guarded.detail(viewer,id(draft));for(String key:List.of("canConfirm","canReject","canRevise","canUndo"))assertEquals(false,detail.get(key));
        assertThrows(RuntimeException.class,()->guarded.confirm(viewer,id(draft),ack(detail)));assertThrows(RuntimeException.class,()->guarded.stage(viewer,"IMPORT",group,"","",Map.of(),csv("10"),"v.csv",meta()));
    }
    @Test void staleSettingsRevisionUsesFreshTargetVersionAndCannotBeEditedByReviewer(){
        inputFixture();oldCost();var draft=safety.stage(principal(),"SETTINGS",group,shop,"",settingsBody(0),null,"",meta());
        repo.transaction(()->{workspace.settings(owner,"u",shop,settingsBody(0));return null;});
        assertThrows(RuntimeException.class,()->safety.confirm(reviewer(),id(draft),ack(info(draft))));assertEquals("STALE",info(draft).get("status"));
        assertThrows(RuntimeException.class,()->safety.revise(reviewer(),id(draft),Map.of("version",info(draft).get("version"),"metadata",meta())));
        var revised=safety.revise(principal(),id(draft),Map.of("version",info(draft).get("version"),"metadata",meta()));assertEquals("APPLIED",safety.confirm(reviewer(),id(draft),ack(revised)).get("status"));
    }
    @Test void manualUpdateDeleteAndCreateUndoRestoreOnlyTheExactTargets(){
        inputFixture();var created=safety.stage(principal(),"MANUAL_CREATE",group,shop,"",manualBody(),null,"",meta());safety.confirm(principal(),id(created),ack(info(created)));
        String manual=db.queryForObject("SELECT id FROM finance_manual_costs",String.class);
        var update=safety.stage(principal(),"MANUAL_UPDATE",group,"",manual,Map.of("unit","包","unitPriceCny","9","confirmed",true,"version",1),null,"",meta());safety.confirm(reviewer(),id(update),ack(info(update)));assertEquals(9,db.queryForObject("SELECT unit_price_cny FROM finance_manual_costs",Integer.class));
        var reverse=safety.undo(principal(),id(update),ack(info(update)));safety.confirm(reviewer(),id(reverse),ack(info(reverse)));assertEquals(8,db.queryForObject("SELECT unit_price_cny FROM finance_manual_costs",Integer.class));
        int v=db.queryForObject("SELECT version FROM finance_manual_costs",Integer.class);var deletion=safety.stage(principal(),"MANUAL_DELETE",group,"",manual,Map.of("version",v),null,"",meta());safety.confirm(reviewer(),id(deletion),ack(info(deletion)));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_manual_costs",Integer.class));
        var restore=safety.undo(principal(),id(deletion),ack(info(deletion)));safety.confirm(reviewer(),id(restore),ack(info(restore)));assertEquals(8,db.queryForObject("SELECT unit_price_cny FROM finance_manual_costs",Integer.class));
    }
    @Test void shopScopedUndoDoesNotMistakeSiblingOrdersForItsScope(){
        inputFixture();oldCost();String sibling=groupedShop(owner,"sibling-order",group);record(owner,shop,"ORDER","own-order",java.math.BigDecimal.TEN,Map.of());record(owner,sibling,"ORDER","other-order",java.math.BigDecimal.TEN,Map.of());
        var draft=safety.stage(principal(),"SETTINGS",group,shop,"",settingsBody(0),null,"",meta());safety.confirm(reviewer(),id(draft),ack(info(draft)));var reverse=safety.undo(principal(),id(draft),ack(info(draft)));
        assertEquals(1,number(map(info(reverse).get("impact")).get("scopeOrderCount")));assertEquals("APPLIED",safety.confirm(reviewer(),id(reverse),ack(info(reverse))).get("status"));
    }
    @Test void normalizedManualTargetsRemainUndoableWithSurroundingWhitespace(){
        inputFixture();var body=manualBody();body.put("product_id"," p ");body.put("sku_id"," v ");body.put("warehouse"," 海外仓-菲律宾 ");body.put("code"," MANUAL ");
        var created=safety.stage(principal(),"MANUAL_CREATE",group,shop,"",body,null,"",meta());safety.confirm(principal(),id(created),ack(info(created)));
        var reverse=safety.undo(principal(),id(created),ack(info(created)));safety.confirm(reviewer(),id(reverse),ack(info(reverse)));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_manual_costs",Integer.class));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_cost_mappings",Integer.class),"reverse must target the normalized mapping saved by the existing method");
    }
    @Test void movedShopInvalidatesThePreviewDurably(){
        inputFixture();oldCost();var draft=safety.stage(principal(),"SETTINGS",group,shop,"",settingsBody(0),null,"",meta());
        String moved=service.saveGroup(owner,null,"移动后");db.update("UPDATE finance_connections SET group_id=? WHERE id=?",moved,shop);
        assertThrows(RuntimeException.class,()->safety.confirm(reviewer(),id(draft),ack(info(draft))));assertEquals("STALE",info(draft).get("status"));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_cost_settings",Integer.class));
    }
    @Test void numericIdentifierExponentCannotOverflowTheSafetyBoundary(){
        byte[] bytes;
        try(var buffer=new java.io.ByteArrayOutputStream();var zip=new java.util.zip.ZipOutputStream(buffer)){
            FinanceWorkbook.put(zip,"xl/worksheets/sheet1.xml","<worksheet><sheetData><row><c t=\"inlineStr\"><is><t>型号</t></is></c><c t=\"inlineStr\"><is><t>仓库</t></is></c><c t=\"inlineStr\"><is><t>202610</t></is></c></row><row><c><v>1E+2147483647</v></c><c t=\"inlineStr\"><is><t>菲律宾</t></is></c><c><v>10</v></c></row></sheetData></worksheet>");zip.finish();bytes=buffer.toByteArray();
        }catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}
        assertThrows(RuntimeException.class,()->FinanceCostInputPreview.parse(bytes,"extreme.xlsx"));
    }
    @Test void extremeValuesRemainBoundedEvenForBlockedDraftSemanticHashes(){
        inputFixture();var state=new FinanceCostInputState(repo,service,workspace);
        for(String value:List.of("1E+2147483647","100E+2147483647","1E-2147483647"))assertEquals(64,state.digest(Map.of("price",new java.math.BigDecimal(value))).length());
        assertEquals("BLOCKED",stage("1E+2147483647").get("status"));assertEquals("BLOCKED",stage("100E+2147483647").get("status"));
        var body=manualBody();body.put("factor","1E+2147483647");assertThrows(RuntimeException.class,()->safety.stage(principal(),"MANUAL_CREATE",group,shop,"",body,null,"",meta()));
        body.put("factor",1);body.put("unitPriceCny","1E+2147483647");assertThrows(RuntimeException.class,()->safety.stage(principal(),"MANUAL_CREATE",group,shop,"",body,null,"",meta()));
    }
    @Test void reverseDraftCanReviseEvidenceAndStillRequiresIndependentConfirmation(){
        inputFixture();var original=stage("10");safety.confirm(principal(),id(original),ack(info(original)));var reverse=safety.undo(principal(),id(original),ack(info(original)));
        var revised=safety.revise(principal(),id(reverse),Map.of("version",info(reverse).get("version"),"metadata",meta()));assertEquals("REVIEW",revised.get("status"));assertEquals(true,revised.get("requiresIndependentReview"));
        assertEquals("APPLIED",safety.confirm(reviewer(),id(reverse),ack(revised)).get("status"));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_cost_sources",Integer.class));
    }
    @Test void changingWarehouseIdentityOfKnownModelNeedsIndependentReview(){
        inputFixture();oldCost();byte[] file="仓库,型号,202610\n新仓库,PACK,10\n".getBytes(StandardCharsets.UTF_8);
        var draft=safety.stage(principal(),"IMPORT",group,"","",Map.of(),file,"warehouse.csv",meta());assertEquals(true,info(draft).get("requiresIndependentReview"));assertEquals(false,info(draft).get("canConfirm"));
    }
    static byte[] csv(String price){return ("仓库,型号,202610\n海外仓-菲律宾,PACK,"+price+"\n").getBytes(StandardCharsets.UTF_8);}
}
