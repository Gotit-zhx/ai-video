package com.yingdao.aicommerce.shop.infrastructure;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ShopeeVideoMediaSigningTest {
    @Test void videoPartUsesPublicSignatureAndPartContentMultipartField() {
        var http = mock(MarketplaceHttp.class);
        var client = client(http);
        byte[] bytes = {1, 2, 3};
        when(http.postMultipart(any(), anyMap(), anyString(), anyString(), anyString(), any(), anyMap())).thenReturn(Map.of());
        client.uploadVideoPart("upload-123", 0, "md5-value", bytes);
        var uri = ArgumentCaptor.forClass(URI.class);
        verify(http).postMultipart(uri.capture(), eq(Map.of()), eq("part_content"), eq("part-0.bin"),
                eq("application/octet-stream"), eq(bytes), eq(Map.of("video_upload_id", "upload-123", "part_seq", "0", "content_md5", "md5-value")));
        Map<String, String> query = query(uri.getValue());
        assertThat(query).containsOnlyKeys("partner_id", "timestamp", "sign");
        assertThat(uri.getValue().getPath()).isEqualTo("/api/v2/media_space/upload_video_part");
        assertThat(query.get("sign")).isEqualTo(client.signPublic(uri.getValue().getPath(), Long.parseLong(query.get("timestamp"))));
    }

    @Test void completionUsesPublicJsonWithEveryZeroBasedPartAndUploadCost() {
        var http = mock(MarketplaceHttp.class); var client = client(http);
        when(http.json(anyMap())).thenReturn("{}"); when(http.postJson(any(), anyMap(), anyString())).thenReturn(Map.of());
        client.completeVideoUpload("upload-123", List.of(0, 1), 2500);
        verify(http).json(Map.of("video_upload_id", "upload-123", "part_seq_list", List.of(0, 1), "report_data", Map.of("upload_cost", 2500L)));
        var uri = ArgumentCaptor.forClass(URI.class);
        verify(http).postJson(uri.capture(), eq(Map.of()), eq("{}"));
        Map<String, String> query = query(uri.getValue());
        assertThat(query).containsOnlyKeys("partner_id", "timestamp", "sign");
        assertThat(query.get("sign")).isEqualTo(client.signPublic(uri.getValue().getPath(), Long.parseLong(query.get("timestamp"))));
    }

    @Test void videoMediaWritesRespectExistingSandboxDestinationGuard() {
        var http = mock(MarketplaceHttp.class); var client = client(http);
        ReflectionTestUtils.setField(client, "sandboxOnly", true);
        assertThatThrownBy(() -> client.uploadVideoPart("upload", 0, "md5", new byte[0])).isInstanceOf(MarketplaceApiException.class);
        assertThatThrownBy(() -> client.completeVideoUpload("upload", List.of(0), 0)).isInstanceOf(MarketplaceApiException.class);
        verifyNoInteractions(http);
    }

    private ShopeeOfficialClient client(MarketplaceHttp http) {
        return new ShopeeOfficialClient(http, true, "123", "unit-secret", "https://open.shopee.com", "https://partner.shopeemobile.com", "SHOP");
    }
    private Map<String, String> query(URI uri) {
        return Arrays.stream(uri.getRawQuery().split("&")).map(part -> part.split("=", 2))
                .collect(Collectors.toMap(pair -> pair[0], pair -> URLDecoder.decode(pair[1], StandardCharsets.UTF_8)));
    }
}
