package com.yingdao.aicommerce.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class OssReadOnlySignerTest {
    @TempDir Path temporary;
    private static final String ENDPOINT = "https://oss-cn-guangzhou.aliyuncs.com";

    @Test
    void missingConfigurationKeepsTheExistingEcsPathAvailable() {
        OssReadOnlySigner signer = new OssReadOnlySigner(ENDPOINT, "cn-guangzhou", "", Duration.ofHours(72));
        assertFalse(signer.isEnabled());
        assertThrows(IllegalStateException.class, () -> signer.sign("bucket", "key"));
        signer.close();
    }

    @Test
    void signsThreeDayOssUrlWithoutStsTokenOrAnyNetworkCall() throws Exception {
        Path file = temporary.resolve("fake-test-credentials.properties");
        Files.writeString(file, "accessKeyId=LTAIunitTestNotReal\naccessKeySecret=unitTestFakeSecretNotReal123456789\n");
        OssReadOnlySigner signer = new OssReadOnlySigner(ENDPOINT, "cn-guangzhou", file.toString(), Duration.ofHours(72));
        try {
            URI url = URI.create(signer.sign("test-bucket", "heyi/test.png"));
            Map<String, String> query = Arrays.stream(url.getRawQuery().split("&"))
                    .map(v -> v.split("=", 2)).collect(Collectors.toMap(v -> v[0],
                            v -> URLDecoder.decode(v[1], StandardCharsets.UTF_8)));
            assertEquals("test-bucket.oss-cn-guangzhou.aliyuncs.com", url.getHost());
            assertEquals("/heyi/test.png", url.getPath());
            assertEquals("OSS4-HMAC-SHA256", query.get("x-oss-signature-version"));
            long expires = Long.parseLong(query.get("x-oss-expires"));
            assertTrue(expires >= 259199 && expires <= 259200);
            assertFalse(query.containsKey("x-oss-security-token"));
            assertFalse(url.toString().contains("unitTestFakeSecret"));
        } finally { signer.close(); }
    }

    @Test
    void rejectsExpiredOrOverSevenDayConfiguration() {
        for (Duration invalid : new Duration[]{Duration.ZERO, Duration.ofSeconds(-1), Duration.ofDays(8)}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new OssReadOnlySigner(ENDPOINT, "cn-guangzhou", "", invalid));
        }
    }

    @Test
    void incompleteOrTemporaryCredentialsFailWithoutExposingValues() throws Exception {
        Path file = temporary.resolve("bad.properties");
        Files.writeString(file, "accessKeyId=LTAIunitTestNotReal\n");
        assertThrows(IllegalArgumentException.class,
                () -> new OssReadOnlySigner(ENDPOINT, "cn-guangzhou", file.toString(), Duration.ofDays(3)));
        Files.writeString(file, "accessKeyId=STS.unitTestNotReal\naccessKeySecret=testSecretThatMustNotLeak\n");
        Exception error = assertThrows(IllegalArgumentException.class,
                () -> new OssReadOnlySigner(ENDPOINT, "cn-guangzhou", file.toString(), Duration.ofDays(3)));
        assertFalse(error.getMessage().contains("testSecretThatMustNotLeak"));
    }
}
