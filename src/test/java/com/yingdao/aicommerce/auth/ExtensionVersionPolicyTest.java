package com.yingdao.aicommerce.auth;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ExtensionVersionPolicyTest {
    @Test
    void comparesNumericChromeVersionsAndFailsClosedForUnknownVersions() {
        for (String value : List.of("0.2.7", "0.2.7.0", "0.2.10", "0.3", "1", "1.0.0"))
            assertTrue(ExtensionVersionPolicy.supported(value), value);
        for (String value : List.of("", "0.2.6", "0.2.5", "0.2", "0.1.99", "0.2.7-beta", "0.2.7.0.1", "99999", " 0.2.7", "NaN"))
            assertFalse(ExtensionVersionPolicy.supported(value), value);
        assertFalse(ExtensionVersionPolicy.supported(null));
    }

    @Test
    void oldUnauthenticatedLoginAndExchangeReturnActionable426BeforeIssuingCredentials() throws Exception {
        var service = mock(ExtensionAuthService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new ExtensionAuthController(service)).build();
        for (String path : List.of("login", "exchange")) {
            mvc.perform(post("/api/v1/extension-auth/" + path)
                            .contentType("application/json").content("{\"extensionVersion\":\"0.2.5\"}"))
                    .andExpect(status().isUpgradeRequired())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("EXTENSION_UPDATE_REQUIRED"))
                    .andExpect(jsonPath("$.minimumVersion").value("0.2.7"))
                    .andExpect(jsonPath("$.downloadUrl").value(ExtensionVersionPolicy.DOWNLOAD_URL));
        }
        verifyNoInteractions(service);
    }

    @Test
    void oldSessionClientCannotGeneratePairingCode() {
        var service = mock(ExtensionAuthService.class);
        var controller = new ExtensionAuthController(service);
        assertThrows(ExtensionVersionPolicy.UpgradeRequired.class, () -> controller.session(
                new ExtensionAuthController.DeviceRequest("test", "a".repeat(32), "0.2.4"),
                null, new MockHttpServletRequest()));
        verifyNoInteractions(service);
    }
}
