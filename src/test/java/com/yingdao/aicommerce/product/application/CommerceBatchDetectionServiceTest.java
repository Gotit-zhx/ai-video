package com.yingdao.aicommerce.product.application;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class CommerceBatchDetectionServiceTest {
 @Test void batchAccessFailureStopsBeforeReadingCaptures(){var batches=mock(CommerceBatchService.class);var captures=mock(ProductCaptureService.class);when(batches.get("b",null)).thenThrow(new IllegalStateException("denied"));assertThrows(IllegalStateException.class,()->new CommerceBatchDetectionService(batches,captures).check("b",null));verifyNoInteractions(captures);}
 @Test void dataReportNeverClaimsVisualDetection(){var batches=mock(CommerceBatchService.class);when(batches.get("b",null)).thenReturn(new CommerceBatchService.View("b","batch","t","DRAFT",java.time.Instant.now(),java.util.List.of()));var report=new CommerceBatchDetectionService(batches,mock(ProductCaptureService.class)).check("b",null);assertFalse(report.visualDetectionAvailable());assertTrue(report.items().isEmpty());}
}
