package com.yingdao.aicommerce.auth;

import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.saas.domain.Organization;
import com.yingdao.aicommerce.saas.mapper.OrganizationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExtensionAuthServiceTest {
    private ExtensionPairingCodeMapper pairingMapper;
    private ExtensionAccessTokenMapper tokenMapper;
    private DatabaseUserDetailsService userDetailsService;
    private OrganizationMapper organizationMapper;
    private ExtensionAuthService service;

    @BeforeEach
    void setUp() {
        pairingMapper = mock(ExtensionPairingCodeMapper.class);
        tokenMapper = mock(ExtensionAccessTokenMapper.class);
        userDetailsService = mock(DatabaseUserDetailsService.class);
        organizationMapper = mock(OrganizationMapper.class);
        service = new ExtensionAuthService(
                pairingMapper, tokenMapper, userDetailsService, organizationMapper,
                new ObjectMapper(), Duration.ofMinutes(5), Duration.ofHours(8)
        );
        when(pairingMapper.updateById(any(ExtensionPairingCode.class))).thenReturn(1);
        when(tokenMapper.touchIfActive(anyString(), any())).thenReturn(1);
        when(tokenMapper.revokeIfActiveAndOwned(anyString(), anyString(), anyString(), any()))
                .thenReturn(1);
    }

    @Test
    void pairingCodeCanBeConsumedOnlyOnceAndRawTokenIsNeverStored() {
        PlatformPrincipal principal = principal("org-a");
        Organization organization = organization("org-a");
        when(userDetailsService.loadByUserId("user-1")).thenReturn(principal);
        when(organizationMapper.selectById("org-a")).thenReturn(organization);

        ExtensionAuthService.PairingCodeResult pairing = service.createPairingCode(principal);
        ArgumentCaptor<ExtensionPairingCode> savedCode = ArgumentCaptor.forClass(ExtensionPairingCode.class);
        verify(pairingMapper).insert(savedCode.capture());
        when(pairingMapper.selectUsableForUpdate(anyString(), any()))
                .thenReturn(savedCode.getValue()).thenReturn(null);

        ExtensionAuthService.ExchangeResult exchanged = service.exchange(new ExtensionAuthService.ExchangeRequest(
                pairing.code(), "Chrome · macOS", "extension-id", "0.1.0"
        ));

        assertTrue(exchanged.accessToken().startsWith("hxt_"));
        assertNotNull(savedCode.getValue().getConsumedAt());
        ArgumentCaptor<ExtensionAccessToken> savedToken = ArgumentCaptor.forClass(ExtensionAccessToken.class);
        verify(tokenMapper).insert(savedToken.capture());
        assertNotEquals(exchanged.accessToken(), savedToken.getValue().getTokenHash());
        assertTrue(savedToken.getValue().getTokenHash().matches("[0-9a-f]{64}"));
        assertFalse(savedToken.getValue().getScopesJson().contains("ROLE_ADMIN"));

        BusinessException reused = assertThrows(BusinessException.class,
                () -> service.exchange(new ExtensionAuthService.ExchangeRequest(
                        pairing.code(), "Chrome · macOS", "extension-id", "0.1.0"
                )));
        assertEquals(HttpStatus.UNAUTHORIZED, reused.getStatus());
    }

    @Test
    void bearerAuthenticationReloadsUserAndKeepsOnlyExtensionScopes() {
        PlatformPrincipal principal = principal("org-a");
        Organization organization = organization("org-a");
        when(userDetailsService.loadByUserId("user-1")).thenReturn(principal);
        when(organizationMapper.selectById("org-a")).thenReturn(organization);

        ExtensionAuthService.PairingCodeResult pairing = service.createPairingCode(principal);
        ArgumentCaptor<ExtensionPairingCode> codeCaptor = ArgumentCaptor.forClass(ExtensionPairingCode.class);
        verify(pairingMapper).insert(codeCaptor.capture());
        when(pairingMapper.selectUsableForUpdate(anyString(), any())).thenReturn(codeCaptor.getValue());
        ExtensionAuthService.ExchangeResult exchange = service.exchange(new ExtensionAuthService.ExchangeRequest(
                pairing.code(), "Test Device", "extension-id", "0.1.0"
        ));
        ArgumentCaptor<ExtensionAccessToken> tokenCaptor = ArgumentCaptor.forClass(ExtensionAccessToken.class);
        verify(tokenMapper).insert(tokenCaptor.capture());
        when(tokenMapper.selectActive(anyString(), any())).thenReturn(tokenCaptor.getValue());

        ExtensionAuthService.AuthenticatedToken authenticated = service.authenticate(exchange.accessToken());

        assertEquals("org-a", authenticated.principal().organizationId());
        assertEquals(ExtensionAuthService.EXTENSION_SCOPES, authenticated.permissions());
        assertFalse(authenticated.principal().getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority())));
        assertFalse(authenticated.principal().getAuthorities().stream()
                .anyMatch(authority -> List.of("product:edit", "product:publish", "asset:watermark")
                        .contains(authority.getAuthority())));
        verify(userDetailsService, org.mockito.Mockito.atLeast(2)).loadByUserId("user-1");
    }

    @Test
    void authenticationRejectsTokenWhenAtomicTouchFindsItRevoked() {
        PlatformPrincipal principal = principal("org-a");
        ExtensionAccessToken token = token("org-a");
        when(tokenMapper.selectActive(anyString(), any())).thenReturn(token);
        when(userDetailsService.loadByUserId("user-1")).thenReturn(principal);
        when(tokenMapper.touchIfActive(eq(token.getId()), any())).thenReturn(0);

        BusinessException failure = assertThrows(BusinessException.class,
                () -> service.authenticate(validAccessToken()));

        assertEquals(HttpStatus.UNAUTHORIZED, failure.getStatus());
        verify(tokenMapper, never()).updateById(any(ExtensionAccessToken.class));
    }

    @Test
    void concurrentRevokeWinsBeforeAuthenticationTouchWithoutTokenResurrection() throws Exception {
        PlatformPrincipal principal = principal("org-a");
        ExtensionAccessToken token = token("org-a");
        AtomicBoolean active = new AtomicBoolean(true);
        CountDownLatch authenticationLoadedToken = new CountDownLatch(1);
        CountDownLatch allowAuthenticationToTouch = new CountDownLatch(1);

        when(tokenMapper.selectActive(anyString(), any())).thenAnswer(invocation ->
                active.get() ? token : null);
        when(userDetailsService.loadByUserId("user-1")).thenAnswer(invocation -> {
            authenticationLoadedToken.countDown();
            assertTrue(allowAuthenticationToTouch.await(2, TimeUnit.SECONDS));
            return principal;
        });
        when(tokenMapper.revokeIfActiveAndOwned(
                eq(token.getId()), eq("user-1"), eq("org-a"), any()))
                .thenAnswer(invocation -> active.compareAndSet(true, false) ? 1 : 0);
        when(tokenMapper.touchIfActive(eq(token.getId()), any()))
                .thenAnswer(invocation -> active.get() ? 1 : 0);

        CompletableFuture<BusinessException> authentication = CompletableFuture.supplyAsync(() -> {
            try {
                service.authenticate(validAccessToken());
                return null;
            } catch (BusinessException exception) {
                return exception;
            }
        });
        assertTrue(authenticationLoadedToken.await(2, TimeUnit.SECONDS));

        service.revoke(validAccessToken(), "user-1", "org-a");
        allowAuthenticationToTouch.countDown();

        BusinessException failure = authentication.get(2, TimeUnit.SECONDS);
        assertNotNull(failure);
        assertEquals(HttpStatus.UNAUTHORIZED, failure.getStatus());
        assertFalse(active.get());
        verify(tokenMapper, never()).updateById(any(ExtensionAccessToken.class));
    }

    @Test
    void expiredOrRevokedTokenIsRejected() {
        when(tokenMapper.selectActive(anyString(), any())).thenReturn(null);
        String syntacticallyValidToken = "hxt_" + "A".repeat(43);

        BusinessException failure = assertThrows(BusinessException.class,
                () -> service.authenticate(syntacticallyValidToken));

        assertEquals(HttpStatus.UNAUTHORIZED, failure.getStatus());
    }

    @Test
    void pairingCannotCrossOrganizationBoundary() {
        PlatformPrincipal issuer = principal("org-a");
        ExtensionAuthService.PairingCodeResult pairing = service.createPairingCode(issuer);
        ArgumentCaptor<ExtensionPairingCode> codeCaptor = ArgumentCaptor.forClass(ExtensionPairingCode.class);
        verify(pairingMapper).insert(codeCaptor.capture());
        when(pairingMapper.selectUsableForUpdate(anyString(), any())).thenReturn(codeCaptor.getValue());
        when(userDetailsService.loadByUserId("user-1")).thenReturn(principal("org-b"));

        BusinessException failure = assertThrows(BusinessException.class,
                () -> service.exchange(new ExtensionAuthService.ExchangeRequest(
                        pairing.code(), "Device", "extension-id", "0.1.0"
                )));

        assertEquals(HttpStatus.UNAUTHORIZED, failure.getStatus());
    }

    private PlatformPrincipal principal(String organizationId) {
        return new PlatformPrincipal(
                "user-1", organizationId, "tenant", "13800000000", "测试用户",
                "password", true, List.of("ADMIN"), List.of(
                        "product:collect", "product:view", "product:edit",
                        "product:publish", "asset:watermark"
                )
        );
    }

    private Organization organization(String organizationId) {
        Organization organization = mock(Organization.class);
        when(organization.getId()).thenReturn(organizationId);
        when(organization.getCode()).thenReturn("tenant");
        when(organization.getName()).thenReturn("测试企业");
        when(organization.getStatus()).thenReturn("ACTIVE");
        return organization;
    }

    private ExtensionAccessToken token(String organizationId) {
        return new ExtensionAccessToken(
                organizationId, "user-1", "stored-hash", "[\"product:collect\"]",
                "Test Device", "extension-id", "0.1.0",
                java.time.Instant.now().plus(Duration.ofHours(1))
        );
    }

    private String validAccessToken() {
        return "hxt_" + "A".repeat(43);
    }
}
