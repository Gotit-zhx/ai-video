package com.yingdao.aicommerce.video.web;

import com.yingdao.aicommerce.video.application.AiVideoTaskService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LocalVideoAssetControllerTest {
    @TempDir Path root;

    @Test
    void productAndGeneralImagesServeLocalBytesForPreviewAndDownload() throws Exception {
        Path image = Files.write(root.resolve("image.png"), new byte[]{1, 2, 3, 4});
        var file = new AiVideoTaskService.AssetFile(image, null, "image/png", 4);
        var service = mock(AiVideoTaskService.class);
        when(service.asset("task", "asset", null)).thenReturn(file);
        when(service.generalAsset("task", "asset", null)).thenReturn(file);
        var product = new AiVideoTaskController(service);
        var general = new GeneralVideoTaskController(service);
        for (boolean download : new boolean[]{false, true}) {
            for (var response : java.util.List.of(
                    product.asset("task", "asset", download, true, "参考图", null),
                    general.asset("task", "asset", download, true, "参考图", null))) {
                assertEquals(HttpStatus.OK, response.getStatusCode());
                assertNull(response.getHeaders().getLocation());
                assertEquals("image/png", response.getHeaders().getContentType().toString());
                assertEquals(4, response.getHeaders().getContentLength());
                if (download) assertTrue(response.getHeaders().getContentDisposition().isAttachment());
                try (var input = ((FileSystemResource) response.getBody()).getInputStream()) {
                    assertArrayEquals(new byte[]{1, 2, 3, 4}, input.readAllBytes());
                }
            }
        }
        verify(service, never()).signedAssetUrl(any(), anyBoolean());
    }
}
