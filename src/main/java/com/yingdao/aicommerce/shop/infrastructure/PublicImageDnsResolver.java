package com.yingdao.aicommerce.shop.infrastructure;

import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.net.IDN;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;

/** Resolves proxy fake-IP answers through a bounded, independently bootstrapped public DNS client. */
final class PublicImageDnsResolver extends SystemDefaultDnsResolver implements Closeable {
    private static final int MAX_CACHE_ENTRIES = 256;
    private static final int MAX_IN_FLIGHT_HOSTS = 64;
    private final HostLookup system;
    private final DohLookup doh;
    private final LongSupplier clockMillis;
    private final Closeable transport;
    private final Map<String, CachedAnswer> cache = new LinkedHashMap<>(16, .75f, true);
    private final Map<String, CompletableFuture<DohAnswer>> inFlight = new LinkedHashMap<>();

    @FunctionalInterface interface HostLookup { InetAddress[] resolve(String host) throws UnknownHostException; }
    @FunctionalInterface interface DohLookup { DohAnswer resolve(String host) throws IOException; }
    record DohAnswer(InetAddress[] addresses, long ttlSeconds) {
        DohAnswer { addresses = addresses.clone(); }
        @Override public InetAddress[] addresses() { return addresses.clone(); }
    }
    private record CachedAnswer(DohAnswer answer, long expiresAt) {}

    PublicImageDnsResolver(boolean googleFallback) {
        var client = new DohClient(googleFallback);
        this.system = new SystemDefaultDnsResolver()::resolve;
        this.doh = client::resolve;
        this.clockMillis = System::currentTimeMillis;
        this.transport = client;
    }

    PublicImageDnsResolver(HostLookup system, DohLookup doh, LongSupplier clockMillis) {
        this.system = system;
        this.doh = doh;
        this.clockMillis = clockMillis;
        this.transport = () -> {};
    }

    @Override public InetAddress[] resolve(String hostname) throws UnknownHostException {
        if (hostname == null || hostname.isBlank()) throw new UnknownHostException("图片域名为空");
        if (hostname.matches("[0-9.]+") || hostname.contains(":")) {
            return MarketplaceImageDownloader.publicAddresses(new InetAddress[]{InetAddress.getByName(hostname)});
        }
        String host;
        try { host = IDN.toASCII(hostname).toLowerCase(Locale.ROOT); }
        catch (IllegalArgumentException exception) { throw new UnknownHostException("图片域名无效"); }
        if (host.length() > 253) throw new UnknownHostException("图片域名过长");
        InetAddress[] addresses = system.resolve(host);
        if (addresses.length == 0 || !Arrays.stream(addresses).allMatch(PublicImageDnsResolver::isFakeIp)) {
            return MarketplaceImageDownloader.publicAddresses(addresses);
        }
        return resolveFakeIp(host);
    }

    private InetAddress[] resolveFakeIp(String host) throws UnknownHostException {
        CompletableFuture<DohAnswer> future;
        boolean owner;
        synchronized (cache) {
            var cached = cache.get(host);
            if (cached != null && cached.expiresAt() > clockMillis.getAsLong()) return cached.answer().addresses();
            cache.remove(host);
            future = inFlight.get(host);
            owner = future == null;
            if (owner) {
                if (inFlight.size() >= MAX_IN_FLIGHT_HOSTS) throw new UnknownHostException("图片公网 DNS 回退解析繁忙");
                future = new CompletableFuture<>();
                inFlight.put(host, future);
            }
        }
        if (owner) {
            try {
                DohAnswer answer = doh.resolve(host);
                // The public-address check also rejects fake IPs returned by the fallback resolver.
                MarketplaceImageDownloader.publicAddresses(answer.addresses());
                long ttl = Math.max(0, Math.min(300, answer.ttlSeconds()));
                synchronized (cache) {
                    if (ttl > 0) {
                        cache.put(host, new CachedAnswer(answer, clockMillis.getAsLong() + ttl * 1000));
                        while (cache.size() > MAX_CACHE_ENTRIES) cache.remove(cache.keySet().iterator().next());
                    }
                }
                future.complete(answer);
                return answer.addresses();
            } catch (Exception exception) {
                UnknownHostException failure = new UnknownHostException("图片代理 DNS 返回虚拟地址，公网 DNS 回退解析失败");
                failure.initCause(exception);
                future.completeExceptionally(failure);
                throw failure;
            } finally {
                synchronized (cache) { inFlight.remove(host); }
            }
        }
        try {
            return future.get(30, TimeUnit.SECONDS).addresses();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            UnknownHostException failure = new UnknownHostException("图片公网 DNS 回退解析已中断");
            failure.initCause(exception);
            throw failure;
        } catch (ExecutionException | TimeoutException exception) {
            UnknownHostException failure = new UnknownHostException("图片公网 DNS 回退解析失败或超时");
            failure.initCause(exception);
            throw failure;
        }
    }

    private static boolean isFakeIp(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 4 && (bytes[0] & 255) == 198 && ((bytes[1] & 255) == 18 || (bytes[1] & 255) == 19);
    }

    @Override public void close() throws IOException { transport.close(); }

    static DohAnswer parseAnswer(byte[] bytes) throws IOException {
        if (bytes.length == 0 || bytes.length > 64 * 1024) throw new IOException("公网 DNS 响应大小无效");
        try {
            var root = new ObjectMapper().readTree(bytes);
            if (!root.path("Status").isIntegralNumber() || root.path("Status").intValue() != 0) throw new IOException("公网 DNS 未返回成功结果");
            var answer = root.path("Answer");
            if (!answer.isArray() || answer.size() > 64) throw new IOException("公网 DNS 记录无效");
            var addresses = new java.util.ArrayList<InetAddress>();
            long ttl = 300;
            for (var record : answer) {
                if (record.path("type").intValue() == 5) {
                    ttl = Math.min(ttl, record.path("TTL").isIntegralNumber() ? Math.max(0, record.path("TTL").longValue()) : 0);
                }
                if (record.path("type").intValue() != 1) continue;
                String value = record.path("data").asText("");
                if (!value.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")) throw new IOException("公网 DNS A 记录无效");
                String[] octets = value.split("\\.");
                byte[] raw = new byte[4];
                for (int index = 0; index < 4; index++) {
                    int octet = Integer.parseInt(octets[index]);
                    if (octet > 255) throw new IOException("公网 DNS A 记录无效");
                    raw[index] = (byte) octet;
                }
                addresses.add(InetAddress.getByAddress(raw));
                ttl = Math.min(ttl, record.path("TTL").isIntegralNumber() ? Math.max(0, record.path("TTL").longValue()) : 0);
            }
            InetAddress[] result = addresses.toArray(InetAddress[]::new);
            MarketplaceImageDownloader.publicAddresses(result);
            return new DohAnswer(result, ttl);
        } catch (IOException exception) { throw exception; }
        catch (Exception exception) { throw new IOException("公网 DNS 响应格式无效", exception); }
    }

    private static final class DohClient implements Closeable {
        private final CloseableHttpClient client;
        private final boolean googleFallback;

        private DohClient(boolean googleFallback) {
            this.googleFallback = googleFallback;
            var manager = PoolingHttpClientConnectionManagerBuilder.create()
                    .setMaxConnTotal(4).setMaxConnPerRoute(4)
                    .setDnsResolver(new SystemDefaultDnsResolver() {
                        @Override public InetAddress[] resolve(String host) throws UnknownHostException {
                            return switch (host.toLowerCase(Locale.ROOT)) {
                                case "cloudflare-dns.com" -> new InetAddress[]{InetAddress.getByAddress(new byte[]{1, 1, 1, 1}), InetAddress.getByAddress(new byte[]{1, 0, 0, 1})};
                                case "dns.google" -> new InetAddress[]{InetAddress.getByAddress(new byte[]{8, 8, 8, 8}), InetAddress.getByAddress(new byte[]{8, 8, 4, 4})};
                                default -> throw new UnknownHostException("禁止连接未配置的公网 DNS 服务");
                            };
                        }
                    })
                    .setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(Timeout.ofSeconds(3)).setSocketTimeout(Timeout.ofSeconds(3)).build()).build();
            client = HttpClients.custom().setConnectionManager(manager).disableRedirectHandling().disableAutomaticRetries().disableCookieManagement()
                    .setDefaultRequestConfig(RequestConfig.custom().setConnectionRequestTimeout(Timeout.ofSeconds(3)).setResponseTimeout(Timeout.ofSeconds(3)).build()).build();
        }

        private DohAnswer resolve(String host) throws IOException {
            byte[] response;
            try { response = fetch("https://cloudflare-dns.com/dns-query", host); }
            catch (IOException exception) {
                if (!googleFallback) throw exception;
                response = fetch("https://dns.google/resolve", host);
            }
            // Invalid/private answers fail closed; only transport failure may select the second service.
            return parseAnswer(response);
        }

        private byte[] fetch(String endpoint, String host) throws IOException {
            var request = new HttpGet(endpoint + "?name=" + URLEncoder.encode(host, StandardCharsets.UTF_8) + "&type=A");
            request.setHeader("Accept", "application/dns-json");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            return client.execute(request, response -> {
                if (response.getCode() != 200) { request.cancel(); throw new IOException("公网 DNS HTTP " + response.getCode()); }
                var entity = response.getEntity();
                if (entity == null || entity.getContentLength() > 64 * 1024) { request.cancel(); throw new IOException("公网 DNS 响应大小无效"); }
                try (var input = entity.getContent(); var output = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[4096];
                    while (true) {
                        if (System.nanoTime() > deadline) { request.cancel(); throw new IOException("公网 DNS 查询超时"); }
                        int length = input.read(buffer);
                        if (length == -1) break;
                        if (output.size() + length > 64 * 1024) { request.cancel(); throw new IOException("公网 DNS 响应过大"); }
                        output.write(buffer, 0, length);
                    }
                    return output.toByteArray();
                }
            });
        }

        @Override public void close() throws IOException { client.close(); }
    }
}
