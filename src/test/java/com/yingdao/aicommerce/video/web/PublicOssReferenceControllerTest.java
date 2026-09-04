package com.yingdao.aicommerce.video.web;

import com.yingdao.aicommerce.storage.OssStorageService;
import com.yingdao.aicommerce.storage.OssReferenceLinkService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.URI;
import java.time.Duration;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PublicOssReferenceControllerTest {
    private static final String LOCATION = "oss://private-bucket/heyi/image.png";
    private final OssReferenceLinkService links = new OssReferenceLinkService(
            "https://media.example.test", "test-secret-not-for-production", Duration.ofHours(72));
    private final OssStorageService oss = mock(OssStorageService.class);
    private MockMvc mvc;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ObjectProvider<OssStorageService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(oss);
        when(oss.isOssLocation(LOCATION)).thenReturn(true);
        when(oss.download(LOCATION)).thenReturn(new OssStorageService.Download(
                new ByteArrayResource(new byte[]{1, 2, 3, 4, 5, 6}), 6, "image/png"));
        mvc = MockMvcBuilders.standaloneSetup(new PublicOssReferenceController(links, provider)).build();
    }

    @Test
    void servesBytesWithoutAnOssRedirectAndSupportsHeadAndRange() throws Exception {
        URI uri = URI.create(links.ossObject(LOCATION));
        mvc.perform(get(uri)).andExpect(status().isOk())
                .andExpect(content().bytes(new byte[]{1, 2, 3, 4, 5, 6}))
                .andExpect(header().doesNotExist("Location"))
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(head(uri)).andExpect(status().isOk()).andExpect(header().longValue("Content-Length", 6))
                .andExpect(content().bytes(new byte[0]));
        mvc.perform(get(uri).header("Range", "bytes=1-3")).andExpect(status().isPartialContent())
                .andExpect(header().string("Content-Range", "bytes 1-3/6"))
                .andExpect(content().bytes(new byte[]{2, 3, 4}));
        verify(oss, times(3)).download(LOCATION);
    }

    @Test
    void rejectsModifiedSignatureBeforeReadingStorage() throws Exception {
        URI uri = URI.create(links.ossObject(LOCATION) + "x");
        mvc.perform(get(uri)).andExpect(status().isNotFound());
        verifyNoInteractions(oss);
    }

    @Test
    void rejectsAnotherBucketEvenWithAValidApplicationSignature() throws Exception {
        mvc.perform(get(URI.create(links.ossObject("oss://other-bucket/secret.png"))))
                .andExpect(status().isNotFound());
        verify(oss, never()).download(anyString());
    }

    @Test
    void resolvesStorageOnEachReadInsteadOfReusingAnExpiredStsUrl() throws Exception {
        URI uri = URI.create(links.ossObject(LOCATION));
        mvc.perform(get(uri)).andExpect(status().isOk());
        when(oss.download(LOCATION)).thenReturn(new OssStorageService.Download(
                new ByteArrayResource(new byte[]{7, 8}), 2, "image/png"));
        mvc.perform(get(uri)).andExpect(status().isOk()).andExpect(content().bytes(new byte[]{7, 8}));
        verify(oss, times(2)).download(LOCATION);
    }
}
