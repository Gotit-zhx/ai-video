package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.product.domain.ProductCapture;
import com.yingdao.aicommerce.product.domain.ProductCaptureImage;
import com.yingdao.aicommerce.product.mapper.ProductCaptureMapper;
import com.yingdao.aicommerce.product.mapper.ProductCaptureImageMapper;
import org.junit.jupiter.api.Test;
import java.net.SocketTimeoutException;
import org.springframework.web.client.ResourceAccessException;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ProductWatermarkQueueWorkerTest {
    @Test void generatedResultDownloadIsDeferredWithoutMarkingFailed() throws Exception {
        run(new GeneratedImageDownloadPendingException("download pending",new SocketTimeoutException()),true);
    }
    @Test void provenUnsentImageRequestUsesExistingDeferredQueue() throws Exception {
        run(new WatermarkRateLimitException("request was not sent"), true);
    }
    @Test void ambiguousResponseFailureIsNotPutBackIntoAutomaticQueue() throws Exception {
        run(new ResourceAccessException("read timed out", new SocketTimeoutException()), false);
    }
    private void run(Exception failure, boolean deferred) throws Exception {
        var queue = mock(ProductWatermarkQueue.class); var captures = mock(ProductCaptureMapper.class);
        var images = mock(ProductCaptureImageMapper.class); var provider = mock(AiWatermarkRemovalProvider.class);
        var capture = mock(ProductCapture.class); var image = mock(ProductCaptureImage.class);
        var work = new ProductWatermarkQueue.Work("work", "org", "capture", "image", "job", "token");
        when(captures.selectById("capture")).thenReturn(capture); when(images.selectById("image")).thenReturn(image);
        when(capture.getOrganizationId()).thenReturn("org"); when(image.getOrganizationId()).thenReturn("org");
        when(image.getCaptureId()).thenReturn("capture"); when(provider.process(capture, image)).thenThrow(failure);
        var worker = new ProductWatermarkQueueWorker(queue, captures, images, provider);
        try {
            worker.run(work);
            if (deferred) {
                if(failure instanceof GeneratedImageDownloadPendingException)verify(queue).deferDownload(work,failure.getMessage());
                else verify(queue).defer(work, failure.getMessage());
                verify(queue, never()).finish(any(), any(), any(), any());
            } else {
                verify(queue, never()).defer(any(), anyString());
                verify(queue).finish(eq(work), isNull(), isNull(), eq(failure.getMessage()));
            }
            verify(provider).process(capture, image);
        } finally { worker.close(); }
    }
}
