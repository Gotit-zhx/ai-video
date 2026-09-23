package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.product.domain.CommerceBatchExecution;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CommerceDetailRestyleRevisionTest {
    @Test void explicitRestyleAllowsTheExistingUnpublishedDraftWithoutChangingIt(){
        var h=new Harness();h.execution.put("stage","MOCKUP");h.service.assertMutable(h.run);
        verify(h.db,never()).update(anyString(),any(Object[].class));
    }
    @Test void refreshReusesTheSameCaptureAndThreeImageIdsAndMarksTheIntentDone()throws Exception{
        var h=new Harness();assertEquals("draft",h.refresh());
        verify(h.db,times(3)).update(startsWith("UPDATE product_capture_images"),any(Object[].class));
        verify(h.db,times(1)).update(startsWith("UPDATE product_captures"),any(Object[].class));
        verify(h.db,times(1)).update(startsWith("UPDATE commerce_batch_publications"),any(Object[].class));
        verify(h.db,never()).update(startsWith("DELETE"),any(Object[].class));
        verify(h.db,never()).update(startsWith("INSERT"),any(Object[].class));
        verify(h.db,never()).update(contains("product_capture_skus"),any(Object[].class));
        var saved=h.json.readTree(h.savedReport);assertEquals("DONE",saved.path("restyleRevision").path("status").asText());
        assertEquals("DONE",saved.path("gridSplit").path("status").asText());
        assertEquals(3,saved.path("restyleRevision").path("refreshedCaptureVersion").asInt());
        assertNotNull(CommerceDetailRestyleRevision.class.getMethod("refresh",String.class,String.class,String.class,int.class,Map.class,List.class).getAnnotation(Transactional.class));
    }
    @Test void workflowLinkIndependentWorkflowOrAnyAuditBlocksBothGenerationAndRefresh(){
        for(String fault:List.of("LINK","WORKFLOW","AUDIT","PUBLISHED","DELETED","VERSION","STATUS")){
            var h=new Harness();
            switch(fault){
                case "LINK"->h.publication.put("workflow_id","workflow");
                case "WORKFLOW"->when(h.db.queryForList(CommerceDetailRestyleRevision.WORKFLOWS_LOCK,"org","draft")).thenReturn(List.of(Map.of("id","unlinked-workflow")));
                case "AUDIT"->when(h.db.queryForList(CommerceDetailRestyleRevision.AUDITS_LOCK,"org","draft")).thenReturn(List.of(Map.of("id","even-dry-run-audit")));
                case "PUBLISHED"->h.capture.put("published_at",Instant.now());
                case "DELETED"->h.capture.put("deleted_at",Instant.now());
                case "VERSION"->h.capture.put("version",3L);
                case "STATUS"->h.capture.put("status","PUBLISHED");
            }
            assertThrows(IllegalArgumentException.class,h::refresh,fault);
            h.execution.put("stage","MOCKUP");assertThrows(IllegalArgumentException.class,()->h.service.assertMutable(h.run),fault);h.noWrites();
        }
    }
    @Test void absentAmbiguousOrUntrustedIntentNeverBypassesPublicationProtection(){
        for(String fault:List.of("ABSENT","MULTIPLE","INTENT","CONTRACT","ORG","EXECUTION","SOURCE","VARIANT","USER","INDICES")){
            var h=new Harness();
            switch(fault){
                case "ABSENT"->h.rows=List.of();
                case "MULTIPLE"->h.rows=List.of(h.anchor,h.anchor);
                case "INTENT"->h.intent.put("intent","AUTO_RETRY");
                case "CONTRACT"->h.intent.put("contractVersion","other");
                case "ORG"->h.intent.put("organizationId","another");
                case "EXECUTION"->h.intent.put("executionId","other-run");
                case "SOURCE"->h.intent.put("sourceCaptureId","other-source");
                case "VARIANT"->h.intent.put("variant",2);
                case "USER"->h.intent.put("requestedBy","another-user");
                case "INDICES"->h.intent.set("detailIndices",h.json.valueToTree(List.of(7,7)));
            }
            assertThrows(IllegalArgumentException.class,h::refresh,fault);h.noWrites();
        }
    }
    @Test void changedMainSkuColorOrUnselectedDetailCannotBeRefreshed(){
        for(String fault:List.of("MAIN","SKU","COLOR","DETAIL1","EXTRA_COLOR","DUPLICATE")){
            var h=new Harness();var manifest=h.json.valueToTree(h.next).deepCopy();
            switch(fault){
                case "MAIN"->((ObjectNode)manifest.get(0)).put("frontKey",h.key(88));
                case "SKU"->((ObjectNode)manifest.get(0)).set("skuKeys",h.json.valueToTree(List.of("new-sku")));
                case "COLOR"->((ObjectNode)manifest.get(0)).put("colorLabel","Changed");
                case "DETAIL1"->((tools.jackson.databind.node.ArrayNode)manifest.get(0).get("detailKeys")).set(0,h.json.valueToTree(h.key(90)));
                case "EXTRA_COLOR"->((tools.jackson.databind.node.ArrayNode)manifest).add(h.json.valueToTree(Map.of("frontKey",h.key(40))));
                case "DUPLICATE"->((tools.jackson.databind.node.ArrayNode)manifest.get(0).get("detailKeys")).set(7,manifest.get(0).get("detailKeys").get(6));
            }
            h.next=h.json.convertValue(manifest,List.class);assertThrows(IllegalArgumentException.class,h::refresh,fault);h.noWrites();
        }
    }
    @Test void targetImagesRequireExactOriginalIdsRolesOrdinalAndArchiveBindings(){
        for(String fault:List.of("MISSING","ROLE","ORDINAL","KEY","SOURCE","STATE","COUNT")){
            var h=new Harness();var image=h.images.get(6);
            switch(fault){case "MISSING"->image.put("id","another");case "ROLE"->image.put("role_name","MAIN");case "ORDINAL"->image.put("sort_order",1);case "KEY"->image.put("processed_url","changed");case "SOURCE"->image.put("source_json","{}");case "STATE"->image.put("status","PENDING");case "COUNT"->h.images.removeLast();}
            assertThrows(IllegalArgumentException.class,h::refresh,fault);h.noWrites();
        }
    }
    @Test void refreshedOutputsMustBeUniqueAndHaveCompleteBoundPositiveReview(){
        for(String fault:List.of("MISSING","DUPLICATE","HASH","FAILED","INCOMPLETE","CONTRACT","CAPTURE","VARIANT","ORDINAL","OUTPUT")){
            var h=new Harness();var output=h.outputs.getFirst();var review=(ObjectNode)h.json.readTree((String)output.get("report_json"));
            switch(fault){
                case "MISSING"->h.outputs.removeFirst();case "DUPLICATE"->h.outputs.add(output);case "HASH"->output.put("content_sha256","f".repeat(64));
                case "FAILED"->review.put("passed",false);case "INCOMPLETE"->review.remove("hasModel");case "CONTRACT"->review.put("contractVersion","other");
                case "CAPTURE"->review.put("captureId","other");case "VARIANT"->review.put("variant",2);case "ORDINAL"->review.put("detailIndex",8);case "OUTPUT"->review.put("outputSha256","f".repeat(64));
            }
            output.put("report_json",h.json.writeValueAsString(review));assertThrows(IllegalArgumentException.class,h::refresh,fault);h.noWrites();
        }
    }
    @Test void changedPublicationManifestOrMultipleProductsCannotBeMutated(){
        var changed=new Harness();changed.publication.put("manifest_json","[]");assertThrows(IllegalArgumentException.class,changed::refresh);changed.noWrites();
        var multiple=new Harness();when(multiple.db.queryForList(CommerceDetailRestyleRevision.PUBLICATIONS_LOCK,"org","run")).thenReturn(List.of(multiple.publication,multiple.publication));
        assertThrows(IllegalArgumentException.class,multiple::refresh);multiple.noWrites();
    }
    @Test void wrongExecutionStageOwnerOrStateCannotAuthorizeChanges(){
        for(String fault:List.of("STAGE","OWNER","STATE")){
            var h=new Harness();if(fault.equals("STAGE"))h.execution.put("stage","REVIEW");if(fault.equals("OWNER"))h.execution.put("created_by","other");if(fault.equals("STATE"))h.execution.put("status","WAITING_INPUT");
            assertThrows(IllegalArgumentException.class,h::refresh);h.noWrites();
        }
    }
    @Test void zeroAffectedRowsAtAnyWriteThrowsSoTheTransactionCannotCommitPartialReplacement(){
        for(String table:List.of("product_capture_images","commerce_batch_publications","product_captures","commerce_batch_artwork_checks")){
            var h=new Harness();when(h.db.update(startsWith("UPDATE "+table+" "),any(Object[].class))).thenReturn(0);
            assertThrows(IllegalStateException.class,h::refresh,table);
        }
    }

    static final class Harness{
        final JdbcTemplate db=mock(JdbcTemplate.class);final ObjectMapper json=new ObjectMapper();
        final CommerceDetailRestyleRevision service=new CommerceDetailRestyleRevision(db,json);
        final CommerceBatchExecution run=new CommerceBatchExecution("run","org","batch","owner","MOCKUP","RUNNING","","lease",Instant.now().plusSeconds(300),Instant.now(),Instant.now());
        final Map<String,Object> execution=new HashMap<>(Map.of("id","run","created_by","owner","stage","PUBLISH","status","RUNNING"));
        final Map<String,Object> publication=new HashMap<>(),capture=new HashMap<>(Map.of("id","draft","status","APPROVED","version",2L)),anchor=new HashMap<>();
        final ArrayList<Map<String,Object>> images=new ArrayList<>(),outputs=new ArrayList<>();
        final ObjectNode intent=json.createObjectNode(),report=json.createObjectNode();
        List<Map<String,Object>> rows;List<Map<String,Object>> next;String savedReport;
        Harness(){
            var oldKeys=java.util.stream.IntStream.rangeClosed(1,9).mapToObj(this::key).toList();
            var newKeys=new ArrayList<>(oldKeys);for(int i=7;i<=9;i++)newKeys.set(i-1,key(i+10));
            var old=List.of(Map.of("frontKey",key(30),"colorLabel","Pink","skuKeys",List.of("sku"),"detailKeys",oldKeys));
            next=new ArrayList<>(List.of(new LinkedHashMap<>(Map.of("frontKey",key(30),"colorLabel","Pink","skuKeys",List.of("sku"),"detailKeys",newKeys))));
            publication.putAll(Map.of("item_id","item","variant_index",1,"capture_id","draft","manifest_json",json.writeValueAsString(old)));
            intent.put("contractVersion",CommerceDetailRestyleRevision.CONTRACT);intent.put("intent","USER_REQUESTED_RESTYLE");intent.put("status","ACTIVE");
            intent.put("revisionId","11111111-1111-1111-1111-111111111111");intent.put("organizationId","org");intent.put("executionId","run");intent.put("sourceCaptureId","source");intent.put("variant",1);intent.put("requestedBy","owner");intent.set("detailIndices",json.valueToTree(List.of(7,8,9)));
            var target=intent.putObject("publication");target.put("captureId","draft");target.put("itemId","item");target.put("variant",1);target.put("expectedCaptureVersion",2);target.put("expectedImageCount",9);target.put("manifestSha256",CommerceDetailRestyleRevision.sha((String)publication.get("manifest_json")));var targets=target.putObject("images");
            for(int i=1;i<=9;i++){
                images.add(new HashMap<>(Map.of("id","image-"+i,"role_name","DETAIL","sort_order",i,"processed_url",CommercePublicationDrafts.PREFIX+key(i),"status","PROCESSED","source_json",json.writeValueAsString(Map.of("commerceStorageKey",key(i))))));
                if(i>=7){targets.putObject(Integer.toString(i)).put("imageId","image-"+i).put("storageKey",key(i));var review=json.createObjectNode();CommerceModelDetailReview.CHECKS.forEach(flag->review.put(flag,true));review.put("passed",true);review.put("contractVersion",CommerceModelDetailReview.CONTRACT_VERSION);review.put("captureId","source");review.put("variant",1);review.put("detailIndex",i);review.put("outputSha256",hash(i+10));outputs.add(new HashMap<>(Map.of("id","new-"+i,"kind",CommerceModelDetailPolicy.kind(i),"storage_key",key(i+10),"content_sha256",hash(i+10),"report_json",json.writeValueAsString(review))));}
            }
            report.set("restyleRevision",intent);report.putObject("gridSplit").put("status","DONE");anchor.putAll(Map.of("id","anchor","capture_id","source","variant_index",1));rows=List.of(anchor);
            when(db.queryForList(CommerceDetailRestyleRevision.EXECUTION_LOCK,"run","org")).thenReturn(List.of(execution));
            when(db.queryForList(CommerceDetailRestyleRevision.REVISION_LOCK,"run","org")).thenAnswer(c->{anchor.put("report_json",json.writeValueAsString(report));return rows;});
            when(db.queryForList(CommerceDetailRestyleRevision.PUBLICATIONS_LOCK,"org","run")).thenReturn(List.of(publication));
            when(db.queryForList(CommerceDetailRestyleRevision.CAPTURE_LOCK,"org","draft")).thenReturn(List.of(capture));
            when(db.queryForList(CommerceDetailRestyleRevision.IMAGES_LOCK,"org","draft")).thenReturn(images);
            when(db.queryForList(CommerceDetailRestyleRevision.TARGETS_LOCK,"org","run","source",1)).thenReturn(outputs);
            when(db.update(anyString(),any(Object[].class))).thenReturn(1);
            doAnswer(c->{savedReport=c.getArgument(1);return 1;}).when(db).update(startsWith("UPDATE commerce_batch_artwork_checks"),any(Object[].class));
        }
        String refresh(){return service.refresh("org","run","item",1,publication,next);}
        String key(int i){return CommerceDetailRestyleRevision.sha("org")+"/"+hash(i);}String hash(int i){return String.format("%064x",i);}
        void noWrites(){verify(db,never()).update(anyString(),any(Object[].class));}
    }
}
