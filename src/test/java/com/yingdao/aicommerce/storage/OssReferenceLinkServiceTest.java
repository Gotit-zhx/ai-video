package com.yingdao.aicommerce.storage;

import org.junit.jupiter.api.Test;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

class OssReferenceLinkServiceTest {
    @Test void legacyLinksBindTheObjectAndExpireAfterThreeDays() {
        OssReferenceLinkService links = new OssReferenceLinkService("https://media.example.test", "unit-test-secret", null);
        long before = Instant.now().getEpochSecond();
        URI uri = URI.create(links.ossObject("oss://private-bucket/heyi/source.png"));
        Map<String,String> query = Arrays.stream(uri.getRawQuery().split("&")).map(s -> s.split("=",2))
                .collect(Collectors.toMap(v -> v[0],v -> v[1]));
        long expires = Long.parseLong(query.get("expires"));
        String id = uri.getPath().substring(uri.getPath().lastIndexOf('/')+1);
        String signature = query.get("signature");
        assertTrue(expires >= before + Duration.ofDays(3).toSeconds());
        assertTrue(expires <= Instant.now().plus(Duration.ofDays(3)).getEpochSecond());
        assertTrue(links.isValid("oss-object",id,expires,signature));
        assertFalse(links.isValid("asset",id,expires,signature));
        assertFalse(links.isValid("oss-object",id+"x",expires,signature));
        assertFalse(links.isValid("oss-object",id,expires+1,signature));
        assertFalse(links.isValid("oss-object",id,1,signature));
        assertFalse(links.isValid("oss-object",id,expires,"invalid!"));
    }
}
