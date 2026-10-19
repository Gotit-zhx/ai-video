package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.ObjectMapper;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.concurrent.Executor;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProductMainImageTemplateServiceTest {
    final JdbcTemplate db = mock(JdbcTemplate.class);
    final CommerceImageArchive archive = mock(CommerceImageArchive.class);
    final ProductMainImageStyleVision vision = mock(ProductMainImageStyleVision.class);
    final Executor executor = mock(Executor.class);
    final ObjectMapper json = new ObjectMapper();
    final ProductMainImageTemplateService service = new ProductMainImageTemplateService(db, archive, vision, json, executor);
    final String id = "12345678-1234-1234-1234-123456789012";
    final PlatformPrincipal editor = user("org", "product:view", "product:edit");

    @Test void viewOnlyUsersCannotCreateArchiveOrChangePrompts() {
        var viewer = user("org", "product:view");
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(BusinessException.class,
                () -> service.create("Style", List.of(), "request-1", viewer)).getStatus());
        assertThrows(BusinessException.class, () -> service.archive(id, viewer));
        assertThrows(BusinessException.class, () -> service.savePrompt(id, "image", "{{product}}", viewer));
        verifyNoInteractions(db, archive, vision, executor);
    }

    @Test void currentTemplateAndImagesCannotCrossTenantBoundaries() {
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(BusinessException.class,
                () -> service.current(id, "another-org")).getStatus());
        assertThrows(BusinessException.class, () -> service.image(id, "image", user("another-org", "product:view")));
        verify(db).queryForList(contains("AND archived=FALSE"), eq(id), eq("another-org"));
        verifyNoInteractions(archive, vision);
    }

    @Test void currentReadsLatestPromptWithoutAnImmutableTemplateCopy() {
        var image = readyTemplate();
        var first = service.current(id, editor);
        var analysis = ProductMainImageStylePolicyTest.analysis();
        analysis.put("prompt", "Changed layout with {{product}}");
        image.put("analysis_json", json.writeValueAsString(analysis));
        var second = service.current(id, editor);
        assertNotEquals(json.writeValueAsString(first), json.writeValueAsString(second));
        assertTrue(json.writeValueAsString(second).contains("Changed layout"));
        verifyNoInteractions(archive, vision);
    }

    @Test void incompleteTemplateCannotBeSelectedForGeneration() {
        var image = readyTemplate();
        image.put("status", "ANALYZING");
        assertEquals(HttpStatus.CONFLICT, assertThrows(BusinessException.class,
                () -> service.current(id, editor)).getStatus());
        verifyNoInteractions(vision, archive);
    }
    @Test void originalInformationPagesAreSelectableButHaveNoEditableGenerationPrompt() {
        var image = readyTemplate();
        image.put("analysis_json", json.writeValueAsString(ProductMainImageStylePolicyTest.originalAnalysis()));
        assertDoesNotThrow(() -> service.current(id, editor));
        when(db.queryForList(contains("FOR UPDATE"), eq("image"), eq(id), eq("org"))).thenReturn(List.of(image));
        assertThrows(BusinessException.class, () -> service.savePrompt(id, "image", "Generate {{product}}", editor));
        verify(db, never()).update(anyString(), any(Object[].class));
    }

    @Test void editingPromptUpdatesTheOriginalImageAndKeepsItsProductPlaceholder() {
        var image = readyTemplate();
        when(db.queryForList(contains("FOR UPDATE"), eq("image"), eq(id), eq("org"))).thenReturn(List.of(image));
        service.savePrompt(id, "image", "New poster for {{product}}", editor);
        verify(db).update(contains("UPDATE product_main_image_template_images SET analysis_json="),
                contains("New poster for {{product}}"), eq("image"), eq("org"));
        assertThrows(BusinessException.class, () -> service.savePrompt(id, "image", "No product placeholder", editor));
        verifyNoInteractions(vision, archive);
    }

    @Test void invalidImageCollectionsNeverReachStorageOrAnalysis() {
        assertThrows(BusinessException.class, () -> service.create("Style", List.of(), "request-1", editor));
        var file = new MockMultipartFile("files", "fake.png", "image/png", "not an image".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThrows(BusinessException.class, () -> service.create("Style", Collections.nCopies(10, file), "request-1", editor));
        assertThrows(BusinessException.class, () -> service.create("Style", List.of(file), "request-1", editor));
        verifyNoInteractions(db, archive, vision, executor);
    }

    @Test void imageDimensionsUseDecodedContentAndRejectTinyImages() throws Exception {
        assertArrayEquals(new int[]{128,256}, ProductMainImageTemplateService.dimensions(png(128,256)));
        assertThrows(BusinessException.class, () -> ProductMainImageTemplateService.dimensions(png(127,256)));
    }

    @Test void saturatedWorkerQueueRestoresPendingWithoutCallingVision() {
        when(db.queryForList(contains("SELECT i.*"))).thenReturn(List.of(Map.of("id", "image", "organization_id", "org")));
        when(db.update(contains("SET status='QUEUED'"), eq("image"))).thenReturn(1);
        doThrow(new java.util.concurrent.RejectedExecutionException("busy")).when(executor).execute(any());
        service.poll();
        verify(db).update(contains("SET status='PENDING'"), eq("image"));
        verifyNoInteractions(vision, archive);
    }

    @Test void queuedWorkIsNotAnalyzingUntilExecutorStartsIt() {
        when(db.queryForList(contains("SELECT i.*"))).thenReturn(List.of(Map.of("id", "image", "organization_id", "org")));
        when(db.update(contains("SET status='QUEUED'"), eq("image"))).thenReturn(1);
        service.poll();
        verify(executor).execute(any());
        verify(db, never()).update(contains("SET i.status='ANALYZING'"), eq("image"));
        verifyNoInteractions(vision, archive);
    }

    @Test void streamedProgressIsPersistedBeforeValidatedCompletion() {
        when(db.queryForList(contains("SELECT i.*"))).thenReturn(List.of(Map.of("id", "image", "organization_id", "org", "storage_key", "ref")));
        when(db.update(contains("SET status='QUEUED'"), eq("image"))).thenReturn(1);
        when(db.update(contains("SET i.status='ANALYZING'"), eq("image"))).thenReturn(1);
        when(archive.read("org", "ref")).thenReturn(new CommerceDesignImageGateway.Reference("image/png", "fixture"));
        doAnswer(call -> { ((Runnable) call.getArgument(0)).run(); return null; }).when(executor).execute(any());
        when(vision.analyze(eq("org"), eq("PRODUCT_MAIN_IMAGE_STYLE_V1"), anyList(), anyMap(), any())).thenAnswer(call -> {
            java.util.function.Consumer<Map<String,Object>> progress = call.getArgument(4);
            progress.accept(Map.of("stage", "MODEL_STREAMING", "outputCharacters", 123, "elapsedSeconds", 5));
            return ProductMainImageStylePolicyTest.analysis();
        });
        service.poll();
        verify(db).update(contains("SET analysis_json="), contains("\"outputCharacters\":123"), eq("image"));
        verify(db).update(contains("SET status='READY'"), contains("{{product}}"), eq("image"));
    }

    private Map<String,Object> readyTemplate() {
        when(db.queryForList(contains("SELECT * FROM product_main_image_templates"), eq(id), eq("org")))
                .thenReturn(List.of(Map.of("id", id, "organization_id", "org", "name", "Style")));
        var image = new LinkedHashMap<String,Object>(Map.of("id","image","sort_order",0,"storage_key","reference",
                "width",1024,"height",1024,"status","READY","analysis_json",json.writeValueAsString(ProductMainImageStylePolicyTest.analysis())));
        when(db.queryForList(contains("SELECT * FROM product_main_image_template_images WHERE template_id="), eq(id), eq("org")))
                .thenReturn(List.of(image));
        return image;
    }
    private static PlatformPrincipal user(String org, String... permissions) {
        return new PlatformPrincipal("user",org,"code","login","name","unused",true,List.of(),List.of(permissions));
    }
    private static byte[] png(int width, int height) throws Exception {
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB),"png",bytes);
        return bytes.toByteArray();
    }
}
