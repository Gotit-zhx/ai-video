package com.yingdao.aicommerce.auth;

import com.yingdao.aicommerce.common.exception.BusinessException;
import org.springframework.http.HttpStatus;

/** Compatibility gate for capture clients, independent of the login token's age. */
public final class ExtensionVersionPolicy {
    public static final String HEADER = "X-Heyi-Extension-Version";
    public static final String MINIMUM_VERSION = "0.2.6";
    public static final String DOWNLOAD_URL = "https://hoyee.org.cn/downloads/hoyee-assistant-latest.zip";

    private ExtensionVersionPolicy() {}

    public static boolean supported(String version) {
        if (version == null || !version.matches("\\d{1,5}(\\.\\d{1,5}){0,3}")) return false;
        String[] actual = version.split("\\.");
        String[] minimum = MINIMUM_VERSION.split("\\.");
        int comparison = 0;
        for (int index = 0; index < 4; index++) {
            int current = index < actual.length ? Integer.parseInt(actual[index]) : 0;
            int required = index < minimum.length ? Integer.parseInt(minimum[index]) : 0;
            if (current > 65535) return false;
            if (comparison == 0) comparison = Integer.compare(current, required);
        }
        return comparison >= 0;
    }

    public static void requireSupported(String version) {
        if (!supported(version)) throw new UpgradeRequired();
    }

    public static UpgradeResponse response() {
        return new UpgradeResponse(false, "EXTENSION_UPDATE_REQUIRED",
                "采集插件版本过旧，请从官网“插件下载”更新至 " + MINIMUM_VERSION
                        + " 或更新版本后再使用。下载：" + DOWNLOAD_URL
                        + "；解压覆盖原插件目录，在扩展程序页点“重新加载”，再刷新商品页，无需退出登录。",
                MINIMUM_VERSION, DOWNLOAD_URL);
    }

    public record UpgradeResponse(boolean success, String code, String message,
                                  String minimumVersion, String downloadUrl) {}

    public static final class UpgradeRequired extends BusinessException {
        public UpgradeRequired() {
            super(HttpStatus.UPGRADE_REQUIRED, response().message());
        }
    }
}
