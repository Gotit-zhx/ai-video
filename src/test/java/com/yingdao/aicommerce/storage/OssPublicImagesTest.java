package com.yingdao.aicommerce.storage;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OssPublicImagesTest {
    private final OSS internal = mock(OSS.class);
    private final OSS external = mock(OSS.class);
    private final OssReadOnlySigner signer = mock(OssReadOnlySigner.class);

    private OssStorageService storage() {
        OssStorageService storage = mock(OssStorageService.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(storage, "bucket", "hoyee");
        ReflectionTestUtils.setField(storage, "publicImages", true);
        ReflectionTestUtils.setField(storage, "publicEndpoint", URI.create("https://oss-cn-guangzhou.aliyuncs.com"));
        ReflectionTestUtils.setField(storage, "internalClient", internal);
        ReflectionTestUtils.setField(storage, "publicClient", external);
        ReflectionTestUtils.setField(storage, "readOnlySigner", signer);
        return storage;
    }

    private ObjectMetadata metadata(String mime) {
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentType(mime);
        return metadata;
    }

    @Test void publicImageLinksHaveNoSignatureIncludingExtensionlessImages() {
        OssStorageService storage = storage();
        when(internal.getObjectMetadata("hoyee", "heyi/source-images/image"))
                .thenReturn(metadata("image/jpeg"));
        String location = "oss://hoyee/heyi/source-images/image";
        String expected = "https://hoyee.oss-cn-guangzhou.aliyuncs.com/heyi/source-images/image";
        assertEquals(expected, storage.signedUrl(location));
        assertEquals(expected, storage.signedDownloadUrl(location, "photo.jpg"));
        verifyNoInteractions(external, signer);
    }

    @Test void previewRetainsResizeButNoAuthentication() {
        OssStorageService storage = storage();
        when(internal.getObjectMetadata("hoyee", "heyi/grid.png")).thenReturn(metadata("image/png"));
        URI preview = URI.create(storage.signedImagePreviewUrl("oss://hoyee/heyi/grid.png"));
        assertEquals("x-oss-process=image/resize,m_lfit,w_960,limit_1/format,webp/quality,q_82", preview.getQuery());
        verifyNoInteractions(external, signer);
    }

    @Test void publicUrlsEscapePathCharactersAndRejectOtherBuckets() {
        OssStorageService storage = storage();
        when(internal.getObjectMetadata("hoyee", "heyi/photo name#.png")).thenReturn(metadata("image/png"));
        assertEquals("https://hoyee.oss-cn-guangzhou.aliyuncs.com/heyi/photo%20name%23.png",
                storage.signedUrl("oss://hoyee/heyi/photo%20name%23.png"));
        assertThrows(IllegalArgumentException.class, () -> storage.signedUrl("oss://other/heyi/a.png"));
    }

    @Test void misleadingFilenameDoesNotMakeNonImagePublic() {
        OssStorageService storage = storage();
        when(internal.getObjectMetadata("hoyee", "heyi/movie.png")).thenReturn(metadata("video/mp4"));
        when(signer.isEnabled()).thenReturn(true);
        when(signer.sign("hoyee", "heyi/movie.png")).thenReturn("https://private.example/signed");
        assertEquals("https://private.example/signed", storage.signedUrl("oss://hoyee/heyi/movie.png"));
        verify(signer).sign("hoyee", "heyi/movie.png");
    }

    @Test void byteUploadsPublishOnlyImagesAndKeepOtherFilesPrivate() throws Exception {
        OssStorageService storage = storage();
        storage.store(new byte[]{1, 2}, "image/png", "generated-images", "grid.png");
        storage.store(new byte[]{3, 4}, "video/mp4", "generated-videos", "video.mp4");
        ArgumentCaptor<PutObjectRequest> requests = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(internal, times(2)).putObject(requests.capture());
        ObjectMetadata image = requests.getAllValues().get(0).getMetadata();
        ObjectMetadata video = requests.getAllValues().get(1).getMetadata();
        assertEquals("public-read", image.getRawMetadata().get("x-oss-object-acl").toString());
        assertEquals("private", video.getRawMetadata().get("x-oss-object-acl").toString());
        assertTrue(image.getCacheControl().startsWith("public,"));
        assertTrue(video.getCacheControl().startsWith("private,"));
    }

    @Test void multipartImagesCarryPublicAclOnCompletion() throws Exception {
        OssStorageService storage = storage();
        InitiateMultipartUploadResult started = new InitiateMultipartUploadResult();
        started.setUploadId("upload-id");
        when(internal.initiateMultipartUpload(any())).thenReturn(started);
        UploadPartResult part = new UploadPartResult();
        part.setPartNumber(1);
        part.setETag("etag");
        when(internal.uploadPart(any())).thenReturn(part);
        storage.store(new ByteArrayInputStream(new byte[]{1, 2, 3}), -1, 100, "image/png", "generated-images", "grid.png");
        ArgumentCaptor<InitiateMultipartUploadRequest> request = ArgumentCaptor.forClass(InitiateMultipartUploadRequest.class);
        verify(internal).initiateMultipartUpload(request.capture());
        assertEquals("public-read", request.getValue().getObjectMetadata().getRawMetadata().get("x-oss-object-acl").toString());
        ArgumentCaptor<CompleteMultipartUploadRequest> completed = ArgumentCaptor.forClass(CompleteMultipartUploadRequest.class);
        verify(internal).completeMultipartUpload(completed.capture());
        assertEquals(CannedAccessControlList.PublicRead, completed.getValue().getObjectACL());
    }

    @Test void visibilityOptOutDoesNotChangeUploadAcl() throws Exception {
        OssStorageService storage = storage();
        ReflectionTestUtils.setField(storage, "publicImages", false);
        storage.store(new byte[]{1}, "image/png", "generated-images", "grid.png");
        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(internal).putObject(request.capture());
        assertNull(request.getValue().getMetadata().getRawMetadata().get("x-oss-object-acl"));
        assertTrue(request.getValue().getMetadata().getCacheControl().startsWith("private,"));
    }
}
