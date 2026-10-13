package com.yingdao.aicommerce.auth;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExtensionBearerAuthenticationFilterTest {
    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void extensionTokenCannotBeUsedOutsideExtensionApi() throws Exception {
        ExtensionAuthService service = mock(ExtensionAuthService.class);
        ExtensionBearerAuthenticationFilter filter = new ExtensionBearerAuthenticationFilter(service);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/system/users");
        request.addHeader("Authorization", "Bearer hxt_" + "A".repeat(43));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("只能访问商品采集接口"));
        verify(service, never()).authenticate(any());
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void productRequestUsesScopeLimitedPrincipal() throws Exception {
        ExtensionAuthService service = mock(ExtensionAuthService.class);
        PlatformPrincipal principal = new PlatformPrincipal(
                "user-1", "org-a", "tenant", "user", "User", "password", true,
                List.of(), List.of("product:collect")
        );
        when(service.authenticate(any())).thenReturn(new ExtensionAuthService.AuthenticatedToken(
                "token-id", principal, List.of("product:collect"), Instant.now().plusSeconds(60),
                "Device", "extension-id", "0.1.0"
        ));
        ExtensionBearerAuthenticationFilter filter = new ExtensionBearerAuthenticationFilter(service);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/product-captures");
        request.addHeader("Authorization", "Bearer hxt_" + "A".repeat(43));
        request.addHeader(ExtensionVersionPolicy.HEADER, "0.2.7");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (servletRequest, servletResponse) -> {
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            assertEquals("user-1", ((PlatformPrincipal) authentication.getPrincipal()).userId());
            assertEquals(List.of("product:collect"), authentication.getAuthorities().stream()
                    .map(authority -> authority.getAuthority()).toList());
        };

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
    }

    @Test
    void oldMissingOrInvalidBuildCannotReachCaptureOrMeEvenWithNewToken() throws Exception {
        for (String path : List.of("/api/v1/product-captures", "/api/v1/product-captures/item/videos", "/api/v1/extension-auth/me")) {
            for (String version : List.of("", "0.2.4", "0.2.5", "invalid")) {
                var service = authenticatedService("0.2.7");
                var request = tokenRequest(path);
                if (!version.isEmpty()) request.addHeader(ExtensionVersionPolicy.HEADER, version);
                var response = new MockHttpServletResponse();
                var chain = mock(FilterChain.class);
                new ExtensionBearerAuthenticationFilter(service).doFilter(request, response, chain);
                assertEquals(426, response.getStatus());
                assertTrue(response.getContentAsString().contains("EXTENSION_UPDATE_REQUIRED"));
                assertTrue(response.getContentAsString().contains(ExtensionVersionPolicy.DOWNLOAD_URL));
                verify(chain, never()).doFilter(any(), any());
                assertEquals(null, SecurityContextHolder.getContext().getAuthentication());
            }
        }
    }

    @Test
    void oldBuildCanStillRevokeCredential() throws Exception {
        for (String path : List.of("/api/v1/extension-auth/logout", "/api/v1/extension-auth/revoke")) {
            var response = new MockHttpServletResponse();
            var chain = mock(FilterChain.class);
            new ExtensionBearerAuthenticationFilter(authenticatedService("0.2.4"))
                    .doFilter(tokenRequest(path), response, chain);
            assertEquals(200, response.getStatus());
            verify(chain).doFilter(any(), any());
        }
    }

    @Test
    void regularWebsiteSessionIsNotVersionGated() throws Exception {
        var service = mock(ExtensionAuthService.class);
        var request = new MockHttpServletRequest("POST", "/api/v1/product-captures");
        var response = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);
        new ExtensionBearerAuthenticationFilter(service).doFilter(request, response, chain);
        assertEquals(200, response.getStatus());
        verify(chain).doFilter(request, response);
        verify(service, never()).authenticate(any());
    }

    private MockHttpServletRequest tokenRequest(String path) {
        var request = new MockHttpServletRequest("POST", path);
        request.addHeader("Authorization", "Bearer hxt_" + "A".repeat(43));
        return request;
    }

    private ExtensionAuthService authenticatedService(String issuedVersion) {
        var service = mock(ExtensionAuthService.class);
        var principal = new PlatformPrincipal("user-1", "org-a", "tenant", "user", "User", "password",
                true, List.of(), List.of("product:collect"));
        when(service.authenticate(any())).thenReturn(new ExtensionAuthService.AuthenticatedToken(
                "token-id", principal, List.of("product:collect"), Instant.now().plusSeconds(60),
                "Device", "extension-id", issuedVersion));
        return service;
    }
}
