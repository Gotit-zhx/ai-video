package com.yingdao.aicommerce.shop.infrastructure;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class PublicImageDnsResolverTest {
    @Test void generatedImageHostUsesAuthenticatedDnsEvenForPoisonedPublicAnswer() throws Exception {
        try(var resolver=new PublicImageDnsResolver(host->{fail("Do not use poisoned system DNS");return null;},
                host->new PublicImageDnsResolver.DohAnswer(addresses("104.18.18.80"),60),()->0)) {
            assertArrayEquals(addresses("104.18.18.80"),resolver.resolve("imgen.x.ai"));
        }
    }

    @Test void fakeIpAnswersAreReplacedByValidatedPublicAnswers() throws Exception {
        var calls = new AtomicInteger();
        try (var resolver = new PublicImageDnsResolver(host -> addresses("198.18.0.117", "198.19.255.254"), host -> {
            calls.incrementAndGet();
            assertEquals("imgen.x.ai", host);
            return new PublicImageDnsResolver.DohAnswer(addresses("104.18.18.80", "104.18.19.80"), 300);
        }, () -> 0)) {
            assertArrayEquals(addresses("104.18.18.80", "104.18.19.80"), resolver.resolve("IMGEN.X.AI"));
            assertEquals(1, calls.get());
        }
    }

    @Test void ordinaryPublicAddressesKeepTheSystemAnswerWithoutDoh() throws Exception {
        try (var resolver = new PublicImageDnsResolver(host -> addresses("104.18.18.80"), host -> {
            fail("Public system DNS must not trigger DoH"); return null;
        }, () -> 0)) {
            assertArrayEquals(addresses("104.18.18.80"), resolver.resolve("images.example"));
        }
    }

    @Test void privateOrMixedSystemAnswersNeverTriggerDoh() throws Exception {
        for (var answer : List.of(addresses("10.0.0.1"), addresses("127.0.0.1"), addresses("198.18.0.117", "104.18.18.80"),
                addresses("198.18.0.117", "10.0.0.1"))) {
            try (var resolver = new PublicImageDnsResolver(host -> answer, host -> {
                fail("Private or mixed answers must fail closed"); return null;
            }, () -> 0)) {
                assertThrows(UnknownHostException.class, () -> resolver.resolve("images.example"));
            }
        }
    }

    @Test void unknownSystemHostnameIsNotTurnedIntoADohBypass() throws Exception {
        try (var resolver = new PublicImageDnsResolver(host -> { throw new UnknownHostException("not found"); }, host -> {
            fail("Only all-fake-IP system answers may fall back"); return null;
        }, () -> 0)) {
            assertThrows(UnknownHostException.class, () -> resolver.resolve("missing.example"));
        }
    }

    @Test void fakeAndPrivateIpLiteralsRemainRejectedWithoutAnyResolver() throws Exception {
        try (var resolver = new PublicImageDnsResolver(host -> {
            fail("IP literals must not use system DNS"); return null;
        }, host -> { fail("IP literals must not use DoH"); return null; }, () -> 0)) {
            for (String literal : List.of("198.18.0.117", "198.19.0.1", "10.0.0.1", "127.0.0.1", "::1")) {
                assertThrows(UnknownHostException.class, () -> resolver.resolve(literal));
            }
        }
    }

    @Test void dohPrivateFakeOrMixedAnswersAreRejected() throws Exception {
        for (var answer : List.of(addresses("10.0.0.1"), addresses("198.18.0.117"), addresses("104.18.18.80", "127.0.0.1"))) {
            try (var resolver = new PublicImageDnsResolver(host -> addresses("198.18.0.117"),
                    host -> new PublicImageDnsResolver.DohAnswer(answer, 300), () -> 0)) {
                assertThrows(UnknownHostException.class, () -> resolver.resolve("images.example"));
            }
        }
    }

    @Test void cacheRespectsTtlAndNeverKeepsAnAnswerBeyondFiveMinutes() throws Exception {
        for (long ttl : List.of(60L, 10000L)) {
            var now = new AtomicLong(0);
            var calls = new AtomicInteger();
            try (var resolver = new PublicImageDnsResolver(host -> addresses("198.18.0.117"), host -> {
                calls.incrementAndGet(); return new PublicImageDnsResolver.DohAnswer(addresses("104.18.18.80"), ttl);
            }, now::get)) {
                var result = resolver.resolve("images.example");
                result[0] = addresses("10.0.0.1")[0];
                assertArrayEquals(addresses("104.18.18.80"), resolver.resolve("images.example"));
                assertEquals(1, calls.get());
                now.set(Math.min(ttl, 300) * 1000);
                resolver.resolve("images.example");
                assertEquals(2, calls.get());
            }
        }
    }

    @Test void zeroTtlIsNotCachedAndFailedLookupCanRecover() throws Exception {
        var calls = new AtomicInteger();
        try (var resolver = new PublicImageDnsResolver(host -> addresses("198.18.0.117"), host -> {
            if (calls.incrementAndGet() == 1) throw new IOException("temporary failure");
            return new PublicImageDnsResolver.DohAnswer(addresses("104.18.18.80"), 0);
        }, () -> 0)) {
            assertThrows(UnknownHostException.class, () -> resolver.resolve("images.example"));
            resolver.resolve("images.example"); resolver.resolve("images.example");
            assertEquals(3, calls.get());
        }
    }

    @Test void cacheHasABoundedNumberOfHostnames() throws Exception {
        var calls = new AtomicInteger();
        try (var resolver = new PublicImageDnsResolver(host -> addresses("198.18.0.117"), host -> {
            calls.incrementAndGet(); return new PublicImageDnsResolver.DohAnswer(addresses("104.18.18.80"), 300);
        }, () -> 0)) {
            for (int index = 0; index <= 256; index++) resolver.resolve("image-" + index + ".example");
            resolver.resolve("image-0.example");
            assertEquals(258, calls.get(), "The oldest hostname should have been evicted");
        }
    }

    @Test void concurrentDownloadsShareOneDohLookup() throws Exception {
        int concurrency = 24;
        var callers = new CountDownLatch(concurrency);
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        try (var resolver = new PublicImageDnsResolver(host -> {
            callers.countDown();
            try { if (!callers.await(5, TimeUnit.SECONDS)) throw new UnknownHostException("test callers timed out"); }
            catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new UnknownHostException("interrupted"); }
            return addresses("198.18.0.117");
        }, host -> {
            calls.incrementAndGet(); started.countDown();
            try { if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("test release timed out"); }
            catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IOException(exception); }
            return new PublicImageDnsResolver.DohAnswer(addresses("104.18.18.80"), 300);
        }, () -> 0); var executor = Executors.newFixedThreadPool(concurrency)) {
            var results = new ArrayList<java.util.concurrent.Future<InetAddress[]>>();
            for (int index = 0; index < concurrency; index++) results.add(executor.submit(() -> resolver.resolve("images.example")));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            release.countDown();
            for (var result : results) assertArrayEquals(addresses("104.18.18.80"), result.get(5, TimeUnit.SECONDS));
            assertEquals(1, calls.get());
        } finally { release.countDown(); }
    }

    @Test void boundedDohParserKeepsOnlyPublicARecordsAndMinimumTtl() throws Exception {
        var answer = PublicImageDnsResolver.parseAnswer(bytes("""
                {"Status":0,"Answer":[{"type":5,"TTL":300,"data":"cdn.example"},
                {"type":1,"TTL":250,"data":"104.18.18.80"},{"type":1,"TTL":60,"data":"104.18.19.80"}]}
                """));
        assertEquals(60, answer.ttlSeconds());
        assertArrayEquals(addresses("104.18.18.80", "104.18.19.80"), answer.addresses());
        for (String body : List.of("{\"Status\":3}", "{\"Status\":0,\"Answer\":[]}",
                "{\"Status\":0,\"Answer\":[{\"type\":1,\"TTL\":300,\"data\":\"127.0.0.1\"}]}",
                "{\"Status\":0,\"Answer\":[{\"type\":1,\"TTL\":300,\"data\":\"metadata.internal\"}]}",
                "{\"Status\":0,\"Answer\":[{\"type\":1,\"TTL\":300,\"data\":\"999.0.0.1\"}]}")) {
            assertThrows(IOException.class, () -> PublicImageDnsResolver.parseAnswer(bytes(body)));
        }
        assertThrows(IOException.class, () -> PublicImageDnsResolver.parseAnswer(new byte[65537]));
    }

    private static InetAddress[] addresses(String... values) throws UnknownHostException {
        InetAddress[] result = new InetAddress[values.length];
        for (int index = 0; index < values.length; index++) result[index] = InetAddress.getByName(values[index]);
        return result;
    }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
}
