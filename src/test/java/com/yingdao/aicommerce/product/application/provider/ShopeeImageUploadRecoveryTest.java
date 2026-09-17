package com.yingdao.aicommerce.product.application.provider;

import com.yingdao.aicommerce.product.domain.ProductCaptureImage;
import com.yingdao.aicommerce.product.mapper.ProductCaptureImageMapper;
import com.yingdao.aicommerce.product.mapper.ProductCaptureSkuMapper;
import com.yingdao.aicommerce.shop.application.MarketplaceAccess;
import com.yingdao.aicommerce.shop.application.ShopAuthorizationService;
import com.yingdao.aicommerce.shop.infrastructure.MarketplaceApiException;
import com.yingdao.aicommerce.shop.infrastructure.MarketplaceImageDownloader;
import com.yingdao.aicommerce.shop.infrastructure.ShopeeOfficialClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

import java.io.InterruptedIOException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ShopeeImageUploadRecoveryTest {
    private static final String API = "/api/v2/media_space/upload_image";
    private ShopeeOfficialClient client;
    private MarketplaceImageDownloader downloader;
    private ShopeeOfficialPublishingProvider provider;
    private MarketplaceAccess.Connection connection;
    private List<Long> delays;

    @BeforeEach void setup() {
        client = mock(ShopeeOfficialClient.class);
        downloader = mock(MarketplaceImageDownloader.class);
        connection = mock(MarketplaceAccess.Connection.class);
        provider = new ShopeeOfficialPublishingProvider(mock(ShopAuthorizationService.class), client,
                mock(ProductCaptureSkuMapper.class), mock(ProductCaptureImageMapper.class), downloader,
                new ObjectMapper(), mock(ShopeeProductVideoUploader.class));
        when(downloader.download(anyString())).thenReturn(
                new MarketplaceImageDownloader.ImageBinary(new byte[]{1, 2, 3}, "image/png", ".png"));
        delays = new ArrayList<>();
    }

    @Test void networkDisconnectRetriesOnlyCurrentImageAndKeepsEarlierImageIds() {
        AtomicInteger secondAttempts = new AtomicInteger();
        when(client.shopMultipart(eq(API), eq(connection), anyString(), anyString(), any(byte[].class), anyMap()))
                .thenAnswer(call -> {
                    String filename = call.getArgument(2);
                    if ("product-1.png".equals(filename)) return uploaded("first-id");
                    if (secondAttempts.incrementAndGet() == 1) {
                        throw new MarketplaceApiException("店铺官方接口请求失败",
                                new SocketException("reset https://remote.invalid?access_token=secret&sign=signature"));
                    }
                    return uploaded("second-id");
                });

        var result = provider.uploadImages(connection, List.of(image(1), image(2)), delays::add);

        assertThat(result).containsEntry("https://processed.invalid/1.png", "first-id")
                .containsEntry("https://source.invalid/1.png", "first-id")
                .containsEntry("https://processed.invalid/2.png", "second-id");
        verify(client, times(1)).shopMultipart(eq(API), eq(connection), eq("product-1.png"), anyString(), any(byte[].class), anyMap());
        verify(client, times(2)).shopMultipart(eq(API), eq(connection), eq("product-2.png"), anyString(), any(byte[].class), anyMap());
        verify(downloader, times(1)).download("https://processed.invalid/1.png");
        verify(downloader, times(1)).download("https://processed.invalid/2.png");
        assertThat(delays).containsExactly(1_000L);
    }

    @Test void repeatedTimeoutStopsAfterThreeAttemptsWithSafeImageAndApiContext() {
        when(client.shopMultipart(eq(API), eq(connection), anyString(), anyString(), any(byte[].class), anyMap()))
                .thenThrow(new MarketplaceApiException("店铺官方接口请求失败",
                        new SocketTimeoutException("https://remote.invalid?access_token=secret&sign=signature")));

        assertThatThrownBy(() -> provider.uploadImages(connection, List.of(image(1)), delays::add))
                .isInstanceOf(MarketplaceApiException.class)
                .hasMessageContaining("第 1/1 张图片").hasMessageContaining(API)
                .hasMessageContaining("尝试 3 次").hasMessageContaining("SocketTimeoutException")
                .hasMessageContaining("商品尚未提交")
                .hasMessageNotContaining("https:").hasMessageNotContaining("secret")
                .hasMessageNotContaining("signature").hasNoCause();
        verify(client, times(3)).shopMultipart(eq(API), eq(connection), anyString(), anyString(), any(byte[].class), anyMap());
        verify(downloader, times(1)).download(anyString());
        assertThat(delays).containsExactly(1_000L, 2_000L);
    }

    @ParameterizedTest @ValueSource(ints = {500, 502, 503, 504})
    void transientGatewayStatusRetriesCurrentImage(int status) {
        when(client.shopMultipart(eq(API), eq(connection), anyString(), anyString(), any(byte[].class), anyMap()))
                .thenThrow(new MarketplaceApiException("店铺官方接口 HTTP " + status + "：gateway response body"))
                .thenReturn(uploaded("recovered-id"));
        assertThat(provider.uploadImages(connection, List.of(image(1)), delays::add))
                .containsEntry("https://processed.invalid/1.png", "recovered-id");
        verify(client, times(2)).shopMultipart(eq(API), eq(connection), anyString(), anyString(), any(byte[].class), anyMap());
        assertThat(delays).containsExactly(1_000L);
    }

    @ParameterizedTest @ValueSource(ints = {400, 401, 403, 408, 429})
    void explicitClientRejectionIsNotRetried(int status) {
        when(client.shopMultipart(eq(API), eq(connection), anyString(), anyString(), any(byte[].class), anyMap()))
                .thenThrow(new MarketplaceApiException("店铺官方接口 HTTP " + status + "：private response body"));
        assertThatThrownBy(() -> provider.uploadImages(connection, List.of(image(1)), delays::add))
                .hasMessageContaining("HTTP " + status).hasMessageNotContaining("private response body");
        verify(client, times(1)).shopMultipart(eq(API), eq(connection), anyString(), anyString(), any(byte[].class), anyMap());
        assertThat(delays).isEmpty();
    }

    @Test void successfulHttpResponseWithPlatformBusinessRejectionIsNotRetried() {
        when(client.shopMultipart(eq(API), eq(connection), anyString(), anyString(), any(byte[].class), anyMap()))
                .thenThrow(new MarketplaceApiException("Shopee 官方接口 " + API + " 失败：error_param; HTTP 503 inside business message"));
        assertThatThrownBy(() -> provider.uploadImages(connection, List.of(image(1)), delays::add))
                .hasMessageContaining("尝试 1 次").hasMessageNotContaining("error_param");
        verify(client, times(1)).shopMultipart(eq(API), eq(connection), anyString(), anyString(), any(byte[].class), anyMap());
        assertThat(delays).isEmpty();
    }

    @Test void configurationCertificateAndInterruptionCausesDoNotEnableNetworkRetry() {
        List<Throwable> causes = List.of(new IllegalArgumentException("invalid URI"),
                new javax.net.ssl.SSLHandshakeException("certificate invalid"),
                new javax.net.ssl.SSLPeerUnverifiedException("hostname invalid"),
                new InterruptedException("shutdown"), new InterruptedIOException("interrupted"));
        for (Throwable cause : causes) {
            reset(client);
            when(client.shopMultipart(eq(API), eq(connection), anyString(), anyString(), any(byte[].class), anyMap()))
                    .thenThrow(new MarketplaceApiException("店铺官方接口请求失败", cause));
            assertThatThrownBy(() -> provider.uploadImages(connection, List.of(image(1)), delays::add))
                    .hasMessageContaining(cause.getClass().getSimpleName()).hasMessageContaining("尝试 1 次");
            verify(client, times(1)).shopMultipart(eq(API), eq(connection), anyString(), anyString(), any(byte[].class), anyMap());
        }
        assertThat(delays).isEmpty();
    }

    @Test void malformedJsonDoesNotEnableNetworkRetry() {
        Throwable malformedJson = catchThrowable(() -> new ObjectMapper().readValue("{bad}", Map.class));
        when(client.shopMultipart(eq(API), eq(connection), anyString(), anyString(), any(byte[].class), anyMap()))
                .thenThrow(new MarketplaceApiException("店铺官方接口请求失败", malformedJson));
        assertThatThrownBy(() -> provider.uploadImages(connection, List.of(image(1)), delays::add))
                .hasMessageContaining("尝试 1 次");
        verify(client, times(1)).shopMultipart(eq(API), eq(connection), anyString(), anyString(), any(byte[].class), anyMap());
        assertThat(delays).isEmpty();
    }

    @Test void gatewayStatusWithStructuredBusinessRejectionIsNotRetried() {
        when(client.shopMultipart(eq(API), eq(connection), anyString(), anyString(), any(byte[].class), anyMap()))
                .thenThrow(new MarketplaceApiException("店铺官方接口 HTTP 503：{\"error\":\"error_param\",\"message\":\"invalid image\"}"));
        assertThatThrownBy(() -> provider.uploadImages(connection, List.of(image(1)), delays::add))
                .hasMessageContaining("HTTP 503").hasMessageContaining("尝试 1 次");
        verify(client, times(1)).shopMultipart(eq(API), eq(connection), anyString(), anyString(), any(byte[].class), anyMap());
        assertThat(delays).isEmpty();
    }

    @Test void gatewayStatusWithStructuredSystemFailureCanRecover() {
        when(client.shopMultipart(eq(API), eq(connection), anyString(), anyString(), any(byte[].class), anyMap()))
                .thenThrow(new MarketplaceApiException("店铺官方接口 HTTP 503：{\"error\":\"error_system\",\"message\":\"service unavailable\"}"))
                .thenReturn(uploaded("recovered-id"));
        assertThat(provider.uploadImages(connection, List.of(image(1)), delays::add))
                .containsEntry("https://processed.invalid/1.png", "recovered-id");
        verify(client, times(2)).shopMultipart(eq(API), eq(connection), anyString(), anyString(), any(byte[].class), anyMap());
    }

    @Test void specificallyTruncatedResponseJsonCanRecover() {
        Throwable truncated = catchThrowable(() -> new ObjectMapper().readValue("{\"response\":", Map.class));
        assertThat(truncated.getClass().getName()).isEqualTo("tools.jackson.core.exc.UnexpectedEndOfInputException");
        when(client.shopMultipart(eq(API), eq(connection), anyString(), anyString(), any(byte[].class), anyMap()))
                .thenThrow(new MarketplaceApiException("店铺官方接口请求失败", truncated))
                .thenReturn(uploaded("recovered-id"));
        assertThat(provider.uploadImages(connection, List.of(image(1)), delays::add))
                .containsEntry("https://processed.invalid/1.png", "recovered-id");
        verify(client, times(2)).shopMultipart(eq(API), eq(connection), anyString(), anyString(), any(byte[].class), anyMap());
        assertThat(delays).containsExactly(1_000L);
    }

    @Test void localJsonFailureIsNotTreatedAsTruncatedRemoteResponse() {
        Throwable truncated = catchThrowable(() -> new ObjectMapper().readValue("{\"response\":", Map.class));
        when(client.shopMultipart(eq(API), eq(connection), anyString(), anyString(), any(byte[].class), anyMap()))
                .thenThrow(new MarketplaceApiException("无法序列化店铺接口请求", truncated));
        assertThatThrownBy(() -> provider.uploadImages(connection, List.of(image(1)), delays::add))
                .hasMessageContaining("尝试 1 次");
        verify(client, times(1)).shopMultipart(eq(API), eq(connection), anyString(), anyString(), any(byte[].class), anyMap());
        assertThat(delays).isEmpty();
    }

    private ProductCaptureImage image(int number) {
        var image = new ProductCaptureImage("org-1", "capture-1", "https://source.invalid/" + number + ".png",
                "MAIN", "", number - 1, "image/png", "{}");
        image.processed("https://processed.invalid/" + number + ".png");
        return image;
    }

    private Map<String, Object> uploaded(String id) {
        return Map.of("response", Map.of("image_info", Map.of("image_id", id)));
    }
}
