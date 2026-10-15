package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.product.web.CommerceArtworkLibraryController;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CommerceArtworkLibraryServiceTest {
    final JdbcTemplate db = mock(JdbcTemplate.class);
    final CommerceImageArchive archive = mock(CommerceImageArchive.class);
    final CommerceArtworkLibraryService service = new CommerceArtworkLibraryService(db, archive);
    final PlatformPrincipal user = user("org", "u", false, List.of("product:view", "product:publish"));

    static PlatformPrincipal user(String org, String id, boolean external, List<String> permissions) {
        return new PlatformPrincipal(id, org, org, id, id, "", true, external ? "SOCIAL" : "LOCAL", List.of(), permissions);
    }

    static byte[] artwork(boolean transparent) throws Exception {
        var image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 32; y++) for (int x = 0; x < 32; x++)
            if (!transparent || x >= 8 && x < 24 && y >= 8 && y < 24)
                image.setRGB(x, y, x < 16 ? 0xffffffff : 0xffcf3030);
        var output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    static Map<String, Object> row(String id, String org, byte[] bytes, boolean nativeVerified) {
        var row = new HashMap<String, Object>();
        String hash = CommerceImageArchive.sha(bytes);
        row.put("id", id); row.put("status", "READY"); row.put("kind", "VARIATE_G1");
        row.put("variant_index", 2); row.put("content_sha256", hash);
        row.put("storage_key", CommerceSurfaceTemplateInputs.key(org, hash));
        row.put("batch_name", "猫咪春季款"); row.put("created_at", Timestamp.from(Instant.parse("2026-09-16T01:00:00Z")));
        row.put("native_verified", nativeVerified ? 1 : 0);
        return row;
    }

    void rows(List<Map<String, Object>> rows) {
        when(db.queryForList(anyString(), any(Object[].class))).thenReturn(rows);
    }

    void archived(Map<String, Object> row, byte[] bytes) {
        when(archive.read("org", (String) row.get("storage_key"))).thenReturn(
                new CommerceDesignImageGateway.Reference("image/png", Base64.getEncoder().encodeToString(bytes)));
    }

    @Test void allEntryPointsRequireCurrentProductPermissionBeforeDatabaseOrStorage() {
        for (var denied : Arrays.asList(null, user("org", "u", false, List.of("product:edit")),
                user("", "u", false, List.of("product:view")))) {
            assertEquals(HttpStatus.FORBIDDEN, assertThrows(BusinessException.class, () -> service.list(0, "", denied)).getStatus());
            assertEquals(HttpStatus.FORBIDDEN, assertThrows(BusinessException.class, () -> service.resolve("job", denied)).getStatus());
            assertEquals(HttpStatus.FORBIDDEN, assertThrows(BusinessException.class, () -> service.image("job", denied)).getStatus());
        }
        verifyNoInteractions(db, archive);
    }

    @Test void invalidPagingAndSearchAreRejectedBeforeQuery() {
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(BusinessException.class, () -> service.list(-1, "", user)).getStatus());
        assertThrows(BusinessException.class, () -> service.list(100001, "", user));
        assertThrows(BusinessException.class, () -> service.list(0, "x".repeat(121), user));
        verifyNoInteractions(db, archive);
    }

    @Test void trustedNativePageUsesSavedFactsWithoutReReadingEveryLargeImage() throws Exception {
        var records = new ArrayList<Map<String, Object>>();
        byte[] bytes = artwork(true);
        for (int n = 0; n < 25; n++) records.add(row("job-" + n, "org", bytes, true));
        rows(records);
        var page = service.list(0, "  猫咪  ", user);
        assertEquals(0, page.page()); assertTrue(page.hasMore()); assertEquals(24, page.items().size());
        var item = page.items().getFirst();
        assertEquals("猫咪春季款 · 印花 V2 · 图案 1", item.name());
        assertEquals("/api/v1/commerce-artwork-library/job-0/image", item.imageUrl());
        assertEquals(Instant.parse("2026-09-16T01:00:00Z"), item.createdAt());
        verify(db).queryForList(CommerceArtworkLibraryService.LIST_SQL, "org", null, null, "猫咪", "猫咪", 48, 0L);
        verifyNoInteractions(archive);
    }

    @Test void externalLibraryQueriesAreScopedToRunsStartedByThatUser() {
        var external = user("org", "external", true, List.of("product:view"));
        rows(List.of());
        assertTrue(service.list(0, null, external).items().isEmpty());
        verify(db).queryForList(CommerceArtworkLibraryService.LIST_SQL, "org", "external", "external", "", "", 48, 0L);
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(BusinessException.class, () -> service.resolve("others-job", external)).getStatus());
        verify(db).queryForList(contains("AND artwork_id=?"), eq("org"), eq("external"), eq("external"), eq("others-job"));
        verifyNoInteractions(archive);
    }

    @Test void healthyLaterPageReadsOnlyItsDatabaseOffset() throws Exception {
        var job = row("late-page", "org", artwork(true), true);
        when(db.queryForList(eq(CommerceArtworkLibraryService.LEGACY_SQL), any(Object[].class))).thenReturn(List.of());
        when(db.queryForList(eq(CommerceArtworkLibraryService.LIST_SQL), any(Object[].class))).thenReturn(List.of(job));
        assertEquals(List.of("late-page"), service.list(80, "", user).items().stream().map(CommerceArtworkLibraryService.Item::id).toList());
        verify(db).queryForList(CommerceArtworkLibraryService.LIST_SQL, "org", null, null, "", "", 25, 1920L);
        verifyNoInteractions(archive);
    }

    @Test void compatibilityScanLimitRequiresNarrowerSearchInsteadOfClaimingNoMoreResults() throws Exception {
        var chunk = new ArrayList<Map<String, Object>>();
        for (int n = 0; n < 48; n++) chunk.add(row("job-" + n, "org", artwork(true), true));
        rows(chunk);
        var failure = assertThrows(BusinessException.class, () -> service.list(41, "", user));
        assertEquals(HttpStatus.BAD_REQUEST, failure.getStatus());
        assertTrue(failure.getMessage().contains("缩小搜索范围"));
        verifyNoInteractions(archive);
    }

    @Test void oldNativePrintsWithoutMetadataRemainDiscoverableButOpaqueImagesAreFilteredAndCached() throws Exception {
        byte[] nativeBytes = artwork(true), opaque = artwork(false);
        var oldNative = row("legacy-native", "org", nativeBytes, false);
        var oldOpaque = row("legacy-opaque", "org", opaque, false);
        rows(List.of(oldOpaque, oldNative)); archived(oldNative, nativeBytes); archived(oldOpaque, opaque);
        for (int repeat = 0; repeat < 2; repeat++) {
            var page = service.list(0, "", user);
            assertEquals(List.of("legacy-native"), page.items().stream().map(CommerceArtworkLibraryService.Item::id).toList());
            assertFalse(page.hasMore());
        }
        verify(archive).read("org", (String) oldNative.get("storage_key"));
        verify(archive).read("org", (String) oldOpaque.get("storage_key"));
        verifyNoMoreInteractions(archive);
    }

    @Test void temporarilyMissingHistoricalArchiveCanAppearAfterStorageRecovers() throws Exception {
        byte[] bytes = artwork(true); var old = row("legacy", "org", bytes, false); rows(List.of(old));
        when(archive.read("org", (String) old.get("storage_key"))).thenThrow(new IllegalStateException("offline"))
                .thenReturn(new CommerceDesignImageGateway.Reference("image/png", Base64.getEncoder().encodeToString(bytes)));
        assertTrue(service.list(0, "", user).items().isEmpty());
        assertEquals(1, service.list(0, "", user).items().size());
    }

    @Test void unauthorizedOrMissingIdsCannotReachArchivedBytes() {
        rows(List.of());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(BusinessException.class, () -> service.resolve("other-org-id", user)).getStatus());
        verify(db).queryForList(contains("organization_id=?"), eq("org"), isNull(), isNull(), eq("other-org-id"));
        assertThrows(BusinessException.class, () -> service.image("x".repeat(37), user));
        verifyNoInteractions(archive);
    }

    @Test void incompleteRetriedOrNonPrintJobsCannotBeReused() throws Exception {
        for (String state : List.of("FAILED", "RETRIED", "SUBMITTING", "UNKNOWN")) {
            var job = row("job", "org", artwork(true), true); job.put("status", state); rows(List.of(job));
            assertEquals(HttpStatus.CONFLICT, assertThrows(BusinessException.class, () -> service.resolve("job", user)).getStatus());
        }
        for (String kind : List.of("EXTRACT", "MOCKUP", "VARIATE_G0", "VARIATE_BAD")) {
            var job = row("job", "org", artwork(true), true); job.put("kind", kind); rows(List.of(job));
            assertThrows(BusinessException.class, () -> service.resolve("job", user));
        }
        verifyNoInteractions(archive);
    }

    @Test void wrongTenantStorageAndShaMismatchAreRejectedDespiteReadyDatabaseStatus() throws Exception {
        byte[] bytes = artwork(true); var job = row("job", "org", bytes, true);
        job.put("storage_key", CommerceSurfaceTemplateInputs.key("other-org", (String) job.get("content_sha256")));
        rows(List.of(job)); assertThrows(BusinessException.class, () -> service.resolve("job", user));
        verifyNoInteractions(archive);
        job.put("storage_key", CommerceSurfaceTemplateInputs.key("org", (String) job.get("content_sha256")));
        archived(job, artwork(false));
        assertEquals(HttpStatus.CONFLICT, assertThrows(BusinessException.class, () -> service.resolve("job", user)).getStatus());
    }

    @Test void resolveNeverTrustsReadyFlagOrListingMarkerInsteadOfNativeTransparency() throws Exception {
        for (byte[] invalid : List.of(artwork(false), new byte[]{1, 2, 3})) {
            var job = row("job", "org", invalid, true); rows(List.of(job)); archived(job, invalid);
            assertEquals(HttpStatus.CONFLICT, assertThrows(BusinessException.class, () -> service.resolve("job", user)).getStatus());
            assertEquals(HttpStatus.CONFLICT, assertThrows(BusinessException.class, () -> service.image("job", user)).getStatus());
        }
    }

    @Test void nativeWhiteArtworkIsReusedUnchangedAndImageEndpointDoesNotCacheProtectedBytes() throws Exception {
        byte[] bytes = artwork(true); var job = row("job", "org", bytes, false); rows(List.of(job)); archived(job, bytes);
        var resolved = service.resolve("job", user);
        assertEquals("job", resolved.id()); assertEquals(CommerceImageArchive.sha(bytes), resolved.contentSha256());
        assertEquals(job.get("storage_key"), resolved.storageKey());
        var response = new CommerceArtworkLibraryController(service).image("job", user);
        assertEquals("image/png", response.getHeaders().getContentType().toString());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertArrayEquals(bytes, response.getBody());
        assertEquals(0xffffffff, CommerceMockupComposer.decode(response.getBody()).getRGB(10, 10));
        verify(db, never()).update(anyString(), any(Object[].class));
    }

    @Test void cachedListingApprovalNeverBypassesFreshIntegrityCheckForReuse() throws Exception {
        byte[] bytes = artwork(true); var job = row("job", "org", bytes, false); rows(List.of(job)); archived(job, bytes);
        assertEquals(1, service.list(0, "", user).items().size());
        archived(job, artwork(false));
        assertThrows(BusinessException.class, () -> service.resolve("job", user));
    }
}
