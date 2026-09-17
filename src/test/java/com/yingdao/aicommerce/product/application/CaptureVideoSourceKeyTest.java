package com.yingdao.aicommerce.product.application;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CaptureVideoSourceKeyTest {
    private static final String PATH = "/api/v4/11110105/mms/ph-11110105-6v6xa-mqzwevrhc35wbd";

    @Test void knownShopeeEncodesAndSignedCdnAliasesShareOneAsset() {
        String expected = CaptureVideoSourceKey.key("https://down-aka-ph.vod.susercontent.com" + PATH + ".16000081784791738.mp4");
        assertEquals(expected, CaptureVideoSourceKey.key("https://mms.vod.susercontent.com" + PATH + ".default.mp4?token=changed"));
        assertEquals(expected, CaptureVideoSourceKey.key("https://cvf.vod.shopee.com:443" + PATH + ".mp4#playback"));
        assertEquals(64, expected.length());
    }

    @Test void differentAssetsDoNotMerge() {
        String first = "https://mms.vod.susercontent.com" + PATH + ".default.mp4";
        assertNotEquals(CaptureVideoSourceKey.key(first), CaptureVideoSourceKey.key(first.replace("mqzwevrhc35wbd", "anotherasset")));
        assertNotEquals(CaptureVideoSourceKey.key(first), CaptureVideoSourceKey.key(first.replace("ph-11110105", "my-11110105")));
    }

    @Test void unknownHostsPathsAndSchemesKeepTheirExactUrlIdentity() {
        String trusted = "https://mms.vod.susercontent.com" + PATH + ".default.mp4";
        for (String untrusted : new String[]{
                trusted.replace("mms.vod.susercontent.com", "mms.vod.susercontent.com.evil.example"),
                trusted.replace("mms.vod.susercontent.com", "example.com"),
                trusted.replace("mms.vod.susercontent.com", "vod.susercontent.com"),
                trusted.replace("https://", "http://"),
                trusted.replace("https://", "https://user@"),
                trusted.replace(".com/", ".com:8443/"),
                trusted.replace("/mms/", "/other/"),
                trusted.replace("/api/", "/ignored/../api/"),
                trusted.replace("mqzwevrhc35wbd", "MQZWEVRHC35WBD"),
                trusted.replace(".default.mp4", ".unknown.mp4")}) {
            assertNotEquals(CaptureVideoSourceKey.key(trusted), CaptureVideoSourceKey.key(untrusted));
            assertNotEquals(CaptureVideoSourceKey.key(untrusted), CaptureVideoSourceKey.key(untrusted + "?token=new"));
            assertEquals(CaptureVideoSourceKey.key(untrusted), CaptureVideoSourceKey.key(untrusted));
        }
    }
}
