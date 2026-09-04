package com.yingdao.aicommerce.storage;

import com.aliyun.oss.OSS;
import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OssStorageServiceDirectLinkTest {
    @Test
    void newMaterialLinksAreSignedDirectlyByOssOnEveryCall() throws Exception {
        OssStorageService storage = mock(OssStorageService.class, CALLS_REAL_METHODS);
        OSS publicClient = mock(OSS.class);
        ReflectionTestUtils.setField(storage, "bucket", "private-bucket");
        ReflectionTestUtils.setField(storage, "signedUrlTtl", Duration.ofHours(2));
        ReflectionTestUtils.setField(storage, "publicClient", publicClient);
        ReflectionTestUtils.setField(storage, "readOnlySigner", mock(OssReadOnlySigner.class));
        String direct = "https://private-bucket.oss-cn-guangzhou.aliyuncs.com/heyi/grid.png?signature=test";
        when(publicClient.generatePresignedUrl(any(GeneratePresignedUrlRequest.class)))
                .thenReturn(URI.create(direct).toURL());
        Instant before = Instant.now().minusMillis(1);
        assertEquals(direct, storage.signedUrl("oss://private-bucket/heyi/grid.png"));
        assertEquals(direct, storage.signedUrl("oss://private-bucket/heyi/grid.png"));
        ArgumentCaptor<GeneratePresignedUrlRequest> requests = ArgumentCaptor.forClass(GeneratePresignedUrlRequest.class);
        verify(publicClient, times(2)).generatePresignedUrl(requests.capture());
        for (GeneratePresignedUrlRequest request : requests.getAllValues()) {
            assertEquals("private-bucket", request.getBucketName());
            assertEquals("heyi/grid.png", request.getKey());
            assertEquals(HttpMethod.GET, request.getMethod());
            assertFalse(request.getExpiration().toInstant().isBefore(before.plus(Duration.ofHours(2))));
            assertFalse(direct.contains("/api/public/"));
        }
        assertThrows(IllegalArgumentException.class, () -> storage.signedUrl("oss://other-bucket/private.png"));
        verifyNoMoreInteractions(publicClient);
    }

    @Test
    void configuredReadOnlySignerDoesNotUseEcsOrTheGateway() {
        OssStorageService storage = mock(OssStorageService.class, CALLS_REAL_METHODS);
        OSS publicClient = mock(OSS.class);
        OssReadOnlySigner signer = mock(OssReadOnlySigner.class);
        ReflectionTestUtils.setField(storage, "bucket", "private-bucket");
        ReflectionTestUtils.setField(storage, "publicClient", publicClient);
        ReflectionTestUtils.setField(storage, "readOnlySigner", signer);
        when(signer.isEnabled()).thenReturn(true);
        when(signer.sign("private-bucket", "heyi/grid.png")).thenReturn("https://direct.example/grid.png");
        assertEquals("https://direct.example/grid.png", storage.signedUrl("oss://private-bucket/heyi/grid.png"));
        verify(signer).sign("private-bucket", "heyi/grid.png");
        verifyNoInteractions(publicClient);
    }
}
