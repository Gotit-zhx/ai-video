package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.product.domain.ProductCapture;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProductMainImageAssetsTest {
    final JdbcTemplate db = mock(JdbcTemplate.class);
    final CommerceImageArchive archive = mock(CommerceImageArchive.class);
    final ProductMainImageTemplateService templates = mock(ProductMainImageTemplateService.class);
    final ObjectMapper json = new ObjectMapper();
    final ProductMainImageAssets assets = new ProductMainImageAssets(db, archive, json, templates);
    final ProductCapture capture = mock(ProductCapture.class);
    final String template = "12345678-1234-1234-1234-123456789012", workflow = "87654321-1234-1234-1234-123456789012";

    @Test void optOutDoesNotReadGenerateOrReplaceAnyImages() {
        assertNull(assets.gallery(capture,Map.of()));
        assets.verifyCurrentGallery(capture,Map.of(),null);
        verifyNoInteractions(db,archive,templates,capture);
    }
    @Test void arbitraryClientImageOverridesCannotBypassWorkflowOwnership() {
        assertThrows(com.yingdao.aicommerce.common.exception.BusinessException.class,
                () -> assets.gallery(capture,Map.of("mainImageStyleTemplateId",template,"idempotencyKey","arbitrary-request")));
        verifyNoInteractions(db,archive);
    }
    @Test void incompleteOrStaleOutputNeverFallsBackToOriginalProductImages() {
        var slot = setup();
        when(db.queryForList(contains("SELECT * FROM product_main_image_jobs"),eq(workflow),eq("org"),eq("capture"))).thenReturn(List.of(
                Map.of("sort_order",0,"status","READY","template_fingerprint","stale")));
        assertThrows(ProductMainImageAssets.TemplateChanged.class, () -> assets.gallery(capture,request()));
        verifyNoInteractions(archive);
    }
    @Test void returnsExactlyOneMainImagePerCurrentTemplateSlotInOrder() {
        var slot = setup();
        var review = new LinkedHashMap<String,Object>();ProductMainImageStylePolicy.CHECKS.forEach(check -> review.put(check,true));
        var row = Map.<String,Object>of("id","job","sort_order",0,"status","READY","storage_key","stored",
                "template_fingerprint",ProductMainImageStylePolicy.fingerprint(slot,json),"review_json",json.writeValueAsString(review));
        when(db.queryForList(contains("SELECT * FROM product_main_image_jobs"),eq(workflow),eq("org"),eq("capture"))).thenReturn(List.of(row));
        when(archive.read("org","stored")).thenReturn(new CommerceDesignImageGateway.Reference("image/png","aW1hZ2U="));
        var result=assets.gallery(capture,request());
        assertEquals(1,result.size());assertEquals("MAIN",result.getFirst().getRoleName());
        assertEquals(ProductMainImageStylePolicy.PREFIX+"job",result.getFirst().getProcessedUrl());
        assertEquals("capture",result.getFirst().getCaptureId());assertEquals("org",result.getFirst().getOrganizationId());
        assertDoesNotThrow(() -> assets.verifyCurrentGallery(capture,request(),result));
        var changed = new LinkedHashMap<String,Object>(slot);
        var analysis = ProductMainImageStylePolicyTest.analysis();
        analysis.put("prompt","Changed during marketplace media staging {{product}}");
        changed.put("analysis",analysis);
        when(templates.current(template,"org")).thenReturn(Map.of("images",List.of(changed)));
        assertThrows(ProductMainImageAssets.TemplateChanged.class,()->assets.verifyCurrentGallery(capture,request(),result));
    }
    @Test void previewReadsAreTenantScopedAndDoNotExposeAnotherCompany() {
        when(db.queryForList(anyString(),eq("job"),eq("other-org"))).thenReturn(List.of());
        assertThrows(com.yingdao.aicommerce.common.exception.BusinessException.class, () -> assets.preview("other-org","job"));
        verifyNoInteractions(archive);
    }
    @Test void originalSlotReadsExactTemplateBytesAndRejectsSubstitutionWithoutFakeQualityChecks() {
        var slot = new LinkedHashMap<>(setup());
        slot.put("analysis",ProductMainImageStylePolicyTest.originalAnalysis());
        when(templates.current(template,"org")).thenReturn(Map.of("images",List.of(slot)));
        var row = new LinkedHashMap<String,Object>(Map.of("id","job","sort_order",0,"status","READY",
                "storage_key","reference","source_key","reference","reference_key","reference",
                "template_fingerprint",ProductMainImageStylePolicy.fingerprint(slot,json),
                "review_json",json.writeValueAsString(Map.of("mode","PRESERVE_ORIGINAL"))));
        when(db.queryForList(contains("SELECT * FROM product_main_image_jobs"),eq(workflow),eq("org"),eq("capture"))).thenReturn(List.of(row));
        var original = new CommerceDesignImageGateway.Reference("image/png","b3JpZ2luYWw=");
        when(archive.read("org","reference")).thenReturn(original);
        var gallery = assets.gallery(capture,request());
        assertEquals(1,gallery.size());
        verify(archive).read("org","reference");
        assertDoesNotThrow(() -> assets.verifyCurrentGallery(capture,request(),gallery));
        for (String field : List.of("storage_key","source_key","reference_key")) {
            row.put(field,"other");
            assertThrows(com.yingdao.aicommerce.common.exception.BusinessException.class,()->assets.gallery(capture,request()));
            row.put(field,"reference");
        }
        row.put("review_json","{}");
        assertThrows(com.yingdao.aicommerce.common.exception.BusinessException.class,()->assets.gallery(capture,request()));
    }
    @Test void generatedImagesArePublishableWithoutAnyReviewMetadata() {
        var slot = setup();
        var row = new LinkedHashMap<String,Object>(Map.of("id","job","sort_order",0,"status","READY","storage_key","stored",
                "template_fingerprint",ProductMainImageStylePolicy.fingerprint(slot,json)));
        when(db.queryForList(contains("SELECT * FROM product_main_image_jobs"),eq(workflow),eq("org"),eq("capture"))).thenReturn(List.of(row));
        when(archive.read("org","stored")).thenReturn(new CommerceDesignImageGateway.Reference("image/png","aW1hZ2U="));
        for (String review : Arrays.asList(null,"","{}","{\"textSafe\":false}","invalid json","{\"mode\":\"PRESERVE_ORIGINAL\"}")) {
            row.put("review_json",review);
            var result = assets.gallery(capture,request());
            assertEquals(1,result.size());
            assertEquals(ProductMainImageStylePolicy.PREFIX+"job",result.getFirst().getProcessedUrl());
        }
        verify(archive,times(6)).read("org","stored");
    }
    @Test void generatedImageMustStillBeReadyBeforePublishing() {
        var slot = setup();
        var row = Map.<String,Object>of("id","job","sort_order",0,"status","RESULT_RECEIVED","storage_key","stored",
                "template_fingerprint",ProductMainImageStylePolicy.fingerprint(slot,json));
        when(db.queryForList(contains("SELECT * FROM product_main_image_jobs"),eq(workflow),eq("org"),eq("capture"))).thenReturn(List.of(row));
        assertThrows(ProductMainImageAssets.TemplateChanged.class,()->assets.gallery(capture,request()));
        verifyNoInteractions(archive);
    }
    @Test void generatedImageMustStillHaveAReadableArchivedFile() {
        var slot = setup();
        var row = Map.<String,Object>of("id","job","sort_order",0,"status","READY","storage_key","missing",
                "template_fingerprint",ProductMainImageStylePolicy.fingerprint(slot,json));
        when(db.queryForList(contains("SELECT * FROM product_main_image_jobs"),eq(workflow),eq("org"),eq("capture"))).thenReturn(List.of(row));
        var error = new IllegalStateException("Archived output missing");
        when(archive.read("org","missing")).thenThrow(error);
        assertSame(error,assertThrows(IllegalStateException.class,()->assets.gallery(capture,request())));
    }
    private Map<String,Object> request(){return Map.of("mainImageStyleTemplateId",template,"idempotencyKey","wf-"+workflow+"-publish");}
    private Map<String,Object> setup(){
        when(capture.getOrganizationId()).thenReturn("org");when(capture.getId()).thenReturn("capture");
        when(db.queryForList(contains("SELECT request_json"),eq(workflow),eq("org"),eq("capture")))
                .thenReturn(List.of(Map.of("request_json",json.writeValueAsString(Map.of("mainImageStyleTemplateId",template)))));
        var slot=Map.<String,Object>of("imageId","image","storageKey","reference","width",1024,"height",1024,"analysis",ProductMainImageStylePolicyTest.analysis());
        when(templates.current(template,"org")).thenReturn(Map.of("images",List.of(slot)));
        return slot;
    }
}
