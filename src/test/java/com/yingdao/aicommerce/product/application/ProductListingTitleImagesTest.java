package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.product.domain.ProductCapture;
import com.yingdao.aicommerce.product.domain.ProductCaptureImage;
import com.yingdao.aicommerce.shop.infrastructure.MarketplaceImageDownloader;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductListingTitleImagesTest {
    static byte[] png() throws Exception {
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(80,40,BufferedImage.TYPE_INT_RGB),"png",output);
        return output.toByteArray();
    }
    @Test void mainComesFirstWithRealPixelsAndChartsAreExcluded() throws Exception {
        var downloader=mock(MarketplaceImageDownloader.class);
        when(downloader.download(anyString())).thenReturn(new MarketplaceImageDownloader.ImageBinary(png(),"image/png",".png"));
        var images=new ProductListingTitleImages(downloader);
        var photos=images.captured(List.of(Map.of("url","gallery","role","GALLERY"),
                Map.of("url","chart","role","SIZE_CHART"),Map.of("url","main","role","MAIN"),
                Map.of("url","gallery","role","GALLERY"),Map.of("url","sku","role","VARIANT"),
                Map.of("url","detail","role","DETAIL"),Map.of("url","extra","role","DETAIL")));
        assertEquals(4,photos.size());
        assertEquals("MAIN",photos.getFirst().get("role"));
        var decoded=ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode((String)photos.getFirst().get("data"))));
        assertEquals(80,decoded.getWidth());assertEquals(40,decoded.getHeight());
        assertEquals("image/jpeg",photos.getFirst().get("mimeType"));
        verify(downloader,never()).download("chart");verify(downloader,never()).download("extra");
        verify(downloader,times(1)).download("gallery");
    }
    @Test void mainFailureDoesNotSilentlyTurnIntoTextOnlyTitleGeneration() throws Exception {
        var downloader=mock(MarketplaceImageDownloader.class);
        when(downloader.download("main")).thenThrow(new IllegalStateException("private transport detail"));
        when(downloader.download("gallery")).thenReturn(new MarketplaceImageDownloader.ImageBinary(png(),"image/png",".png"));
        var images=new ProductListingTitleImages(downloader);
        var failure=assertThrows(BusinessException.class,()->images.captured(
                List.of(Map.of("url","main","role","MAIN"),Map.of("url","gallery","role","GALLERY"))));
        assertFalse(failure.getMessage().contains("private transport"));
        assertThrows(BusinessException.class,()->images.captured(List.of(Map.of("url","chart","role","SIZE_CHART"))));
    }
    @Test void storedImagesMustBelongToTheCurrentTenantAndCapture() {
        var downloader=mock(MarketplaceImageDownloader.class);
        var images=new ProductListingTitleImages(downloader);
        var capture=mock(ProductCapture.class);
        when(capture.getOrganizationId()).thenReturn("org-a");when(capture.getId()).thenReturn("capture-a");
        var foreign=new ProductCaptureImage("org-b","capture-a","https://fixture.invalid/main","MAIN",null,0,null,null);
        assertThrows(BusinessException.class,()->images.stored(capture,List.of(foreign)));
        verifyNoInteractions(downloader);
    }
}
