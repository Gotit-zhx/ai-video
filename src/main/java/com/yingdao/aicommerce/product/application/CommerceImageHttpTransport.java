package com.yingdao.aicommerce.product.application;

import java.io.Closeable;
import java.io.IOException;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;

/** Dedicated long-running image POST transport. Never retries a potentially paid request. */
final class CommerceImageHttpTransport implements Closeable {
    static final int CAPACITY = ProductWatermarkQueue.MAX_CONCURRENT + 12;
    static final Timeout CONNECT_TIMEOUT = Timeout.ofSeconds(10);
    static final Timeout LEASE_TIMEOUT = Timeout.ofSeconds(60);
    static final Timeout RESPONSE_TIMEOUT = Timeout.ofMinutes(60);
    final PoolingHttpClientConnectionManager manager;
    final RequestConfig requestConfig;
    private final CloseableHttpClient client;

    CommerceImageHttpTransport() { this(CAPACITY, LEASE_TIMEOUT, RESPONSE_TIMEOUT); }

    // Smaller limits are used only by loopback transport tests.
    CommerceImageHttpTransport(int capacity, Timeout leaseTimeout, Timeout responseTimeout) {
        manager = PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(capacity).setMaxConnPerRoute(capacity)
                .setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(CONNECT_TIMEOUT)
                        .setSocketTimeout(responseTimeout).build()).build();
        requestConfig = RequestConfig.custom().setConnectionRequestTimeout(leaseTimeout)
                .setResponseTimeout(responseTimeout).build();
        client = HttpClients.custom().setConnectionManager(manager).setDefaultRequestConfig(requestConfig)
                .disableAutomaticRetries().disableRedirectHandling().disableCookieManagement().build();
    }

    HttpComponentsClientHttpRequestFactory factory() { return new HttpComponentsClientHttpRequestFactory(client); }
    @Override public void close() throws IOException { client.close(); }
}
