package com.yingdao.aicommerce.video.application;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VideoReferenceLinkServiceTest {

    private final VideoReferenceLinkService links = new VideoReferenceLinkService(
            "https://media.example.test", false,
            "unit-test-reference-link-secret-that-is-not-shared", Duration.ofMinutes(10));

    @Test
    void signsPublicReferenceLinksAndRejectsTampering() {
        URI uri = URI.create(links.asset("asset-123"));
        Map<String, String> query = query(uri);
        long expires = Long.parseLong(query.get("expires"));
        String signature = query.get("signature");

        assertTrue(links.isValid("asset", "asset-123", expires, signature));
        assertFalse(links.isValid("asset", "asset-124", expires, signature));
        assertFalse(links.isValid("source", "asset-123", expires, signature));
        assertFalse(links.isValid("asset", "asset-123", expires, signature + "x"));
    }

    @Test
    void rejectsExpiredLinksAndSignsAudioSuffixBeforeQueryString() {
        URI uri = URI.create(links.scriptAudio("script-1"));
        Map<String, String> query = query(uri);

        assertTrue(uri.getPath().endsWith("/script-audio/script-1/transcription-audio.mp3"));
        assertFalse(links.isValid("script-audio", "script-1", 1L, query.get("signature")));
    }

    @Test void storyboardSignatureBindsBothAssetAndSequence() {
        var q = query(URI.create(links.storyboardFrame("grid-1", 2)));
        long expires = Long.parseLong(q.get("expires"));
        assertTrue(links.isValid("storyboard-frame/2", "grid-1", expires, q.get("signature")));
        assertFalse(links.isValid("storyboard-frame/3", "grid-1", expires, q.get("signature")));
        assertFalse(links.isValid("storyboard-frame/2", "grid-2", expires, q.get("signature")));
        assertFalse(links.isValid("storyboard-frame/2", "grid-1", 1, q.get("signature")));
    }

    private Map<String, String> query(URI uri) {
        return Arrays.stream(uri.getRawQuery().split("&"))
                .map(value -> value.split("=", 2))
                .collect(Collectors.toMap(value -> value[0], value -> value[1]));
    }
}
