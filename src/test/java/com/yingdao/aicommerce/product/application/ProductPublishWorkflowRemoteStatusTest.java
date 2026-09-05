package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.product.domain.ProductPublishAudit;
import com.yingdao.aicommerce.product.domain.ProductPublishWorkflow;
import com.yingdao.aicommerce.product.mapper.ProductCaptureMapper;
import com.yingdao.aicommerce.product.mapper.ProductImageProcessingJobMapper;
import com.yingdao.aicommerce.product.mapper.ProductPublishAuditMapper;
import com.yingdao.aicommerce.product.mapper.ProductPublishWorkflowMapper;
import com.yingdao.aicommerce.shop.application.ShopIntegrationSettings;
import com.yingdao.aicommerce.shop.mapper.ShopMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductPublishWorkflowRemoteStatusTest {
    private ProductPublishWorkflowMapper workflowMapper;
    private ProductPublishAuditMapper auditMapper;
    private ProductPublishWorkflowService service;
    private PlatformPrincipal principal;

    @BeforeEach
    void setUp() {
        workflowMapper = mock(ProductPublishWorkflowMapper.class);
        auditMapper = mock(ProductPublishAuditMapper.class);
        service = new ProductPublishWorkflowService(
                workflowMapper,
                mock(ProductCaptureMapper.class),
                mock(ProductImageProcessingJobMapper.class),
                auditMapper,
                mock(ShopMapper.class),
                mock(ProductCaptureService.class),
                mock(ShopIntegrationSettings.class),
                new ObjectMapper());
        principal = new PlatformPrincipal("user-1", "org-a", "tenant", "user", "User", "",
                true, List.of(), List.of("product:publish"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"REVIEWING", "PENDING", "NORMAL", "ACTIVATE"})
    void exposesNormalizedLiveMarketplaceStatus(String remoteStatus) {
        ProductPublishAudit audit = audit(false,
                "{\"remoteStatus\":\"  " + remoteStatus.toLowerCase() + "  \"}");
        prepare(audit);

        ProductPublishWorkflowService.WorkflowView result = service.get(
                "capture-1", "workflow-1", principal);

        assertEquals(remoteStatus, result.publication().remoteStatus());
    }

    @Test
    void neverExposesRemoteStatusForDryRunAudit() {
        ProductPublishAudit audit = audit(true, "{\"remoteStatus\":\"NORMAL\"}");
        prepare(audit);

        ProductPublishWorkflowService.WorkflowView result = service.get(
                "capture-1", "workflow-1", principal);

        assertNull(result.publication().remoteStatus());
    }

    @Test
    void malformedReceiptDoesNotBreakWorkflowRead() {
        ProductPublishAudit audit = audit(false, "{not-json");
        prepare(audit);

        ProductPublishWorkflowService.WorkflowView result = service.get(
                "capture-1", "workflow-1", principal);

        assertEquals("workflow-1", result.id());
        assertNull(result.publication().remoteStatus());
    }

    @Test
    void rejectsUnsafeRemoteStatusValue() {
        ProductPublishAudit audit = audit(false,
                "{\"remoteStatus\":\"NORMAL\\n<script>alert(1)</script>\"}");
        prepare(audit);

        ProductPublishWorkflowService.WorkflowView result = service.get(
                "capture-1", "workflow-1", principal);

        assertNull(result.publication().remoteStatus());
    }

    private ProductPublishAudit audit(boolean dryRun, String responseJson) {
        ProductPublishAudit audit = new ProductPublishAudit(
                "org-a", "capture-1", "user-1", "audit-key-123", "SHOPEE", "shop-1",
                dryRun ? "DRY_RUN" : "SHOPEE_OFFICIAL_V2", dryRun, "{}");
        audit.completed("external-1", responseJson);
        return audit;
    }

    private void prepare(ProductPublishAudit audit) {
        ProductPublishWorkflow workflow = mock(ProductPublishWorkflow.class);
        when(workflow.getId()).thenReturn("workflow-1");
        when(workflow.getCaptureId()).thenReturn("capture-1");
        when(workflow.getOrganizationId()).thenReturn("org-a");
        when(workflow.getDestinationPlatform()).thenReturn("SHOPEE");
        when(workflow.getShopId()).thenReturn("shop-1");
        when(workflow.getRequestJson()).thenReturn(
                "{\"executionMode\":\"LIVE\",\"shopId\":\"shop-1\"}");
        when(workflow.getStatus()).thenReturn("SUCCEEDED");
        when(workflow.getCurrentStep()).thenReturn("COMPLETED");
        when(workflow.getPublishAuditId()).thenReturn(audit.getId());
        when(workflowMapper.selectOne(any())).thenReturn(workflow);
        when(auditMapper.selectOne(any())).thenReturn(audit);
    }
}
