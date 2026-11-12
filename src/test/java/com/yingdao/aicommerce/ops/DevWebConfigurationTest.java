package com.yingdao.aicommerce.ops;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;

class DevWebConfigurationTest {
    @Test void financeSourcesAreDeniedEvenWhenAnAuthenticatedUserRequestsThem() {
        for (String path : new String[]{"/modern", "/modern/", "/modern/package-lock.json", "/modern/src/app/mount.tsx", "/modern/node_modules/vite/package.json", "/modern-build.mjs"}) {
            assertTrue(DevWebConfiguration.privateFinanceSource(new MockHttpServletRequest("GET", path)), path);
            assertFalse(DevWebConfiguration.publicRequest(new MockHttpServletRequest("GET", path)), path);
        }
        for (String path : new String[]{"/assets/finance-modern/manifest.json", "/assets/finance-modern/assets/entry-hash.js", "/finance-host.js", "/api/v1/finance/overview"}) {
            assertFalse(DevWebConfiguration.privateFinanceSource(new MockHttpServletRequest("GET", path)), path);
        }
    }
    @Test void permitsFrontendAndRevisionButNeverBusinessApisOrPrivateFiles() {
        for (String path : new String[]{"/", "/index.html", "/app.js", "/styles.css", "/_dev/revision", "/assets/logo.svg", "/vendor/pdf/pdf.mjs", "/i18n/zh.json", "/plugin-downloads/agent.zip"}) {
            assertTrue(DevWebConfiguration.publicRequest(new MockHttpServletRequest("GET", path)), path);
        }
        for (String path : new String[]{"/api/v1/secret.js", "/_heyi/ops/status.js", "/actuator/env.js", "/.env", "/assets/.env", "/assets/../private.js", "/test/private.js", "/nginx.conf", "/_dev/anything", "/assets/secret.pem"}) {
            assertFalse(DevWebConfiguration.publicRequest(new MockHttpServletRequest("GET", path)), path);
        }
        assertFalse(DevWebConfiguration.publicRequest(new MockHttpServletRequest("POST", "/_dev/revision")));
    }
}
