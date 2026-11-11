package com.yingdao.aicommerce.ops;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;

class DevWebConfigurationTest {
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
