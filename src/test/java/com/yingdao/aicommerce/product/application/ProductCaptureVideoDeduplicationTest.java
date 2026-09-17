package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.product.web.ProductCaptureVideoController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ProductCaptureVideoDeduplicationTest {
    private static final String ORIGINAL = "https://down-aka-ph.vod.susercontent.com/api/v4/11110105/mms/ph-11110105-6v6xa-mqzwevrhc35wbd.16000081784791738.mp4";
    private static final String ALIAS = "https://mms.vod.susercontent.com/api/v4/11110105/mms/ph-11110105-6v6xa-mqzwevrhc35wbd.default.mp4";
    private final PlatformPrincipal user = new PlatformPrincipal("u", "org", "code", "login", "name", "", true,
            List.of(), List.of("product:collect", "product:view"));
    @TempDir Path storage;

    @Test void differentEncodesReturnTheStoredVideoAndDoNotWriteAnotherFile() throws Exception {
        var db = new VideoDatabase();
        var controller = controller(db);
        Object first = controller.upload("capture", ORIGINAL, video(1), user).data().get("id");
        Object repeated = controller.upload("capture", ALIAS, video(2), user).data().get("id");
        assertEquals(first, repeated);
        assertEquals(1, db.rows.size());
        assertEquals(1, fileCount());
    }

    @Test void differentAssetsAndDifferentCapturesRemainSeparate() throws Exception {
        var db = new VideoDatabase();
        var controller = controller(db);
        Object first = controller.upload("capture", ORIGINAL, video(1), user).data().get("id");
        Object second = controller.upload("capture", ALIAS.replace("mqzwevrhc35wbd", "distinctvideo"), video(2), user).data().get("id");
        Object otherCapture = controller.upload("other-capture", ORIGINAL, video(1), user).data().get("id");
        assertNotEquals(first, second);
        assertNotEquals(first, otherCapture);
        assertEquals(3, db.rows.size());
        assertEquals(3, fileCount());
    }

    @Test void legacyUnkeyedSourceIsStillIdempotentAcrossEncodes() throws Exception {
        var db = new VideoDatabase();
        db.rows.add(new Row("legacy", "org", "capture", ORIGINAL, "old-hash", null, null));
        Object found = controller(db).upload("capture", ALIAS, video(2), user).data().get("id");
        assertEquals("legacy", found);
        assertEquals(1, db.rows.size());
        assertFalse(Files.exists(storage.resolve("capture-videos")));
    }

    @Test void concurrentAliasesReturnOneWinnerAndRemoveTheLosingFile() throws Exception {
        var db = new VideoDatabase();
        db.insertBarrier = new CountDownLatch(2);
        var controller = controller(db);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> controller.upload("capture", ORIGINAL, video(1), user));
            var second = pool.submit(() -> controller.upload("capture", ALIAS, video(2), user));
            assertEquals(first.get(10, TimeUnit.SECONDS).data().get("id"), second.get(10, TimeUnit.SECONDS).data().get("id"));
        }
        assertEquals(2, db.insertAttempts.get());
        assertEquals(1, db.rows.size());
        assertEquals(1, fileCount());
    }

    @Test void supersededRowsAreHiddenFromTheVideoList() {
        var db = new VideoDatabase();
        db.rows.add(new Row("winner", "org", "capture", ORIGINAL, "a", CaptureVideoSourceKey.key(ORIGINAL), null));
        db.rows.add(new Row("duplicate", "org", "capture", ALIAS, "b", null, "winner"));
        assertEquals(List.of(Map.of("id", "winner")), controller(db).list("capture", user).data());
        assertTrue(db.lastQuery.contains("superseded_by IS NULL"));
    }

    private ProductCaptureVideoController controller(VideoDatabase db) {
        return new ProductCaptureVideoController(mock(ProductCaptureService.class), db, storage.toString());
    }

    private MockMultipartFile video(int marker) {
        byte[] bytes = new byte[20];
        bytes[4] = 'f'; bytes[5] = 't'; bytes[6] = 'y'; bytes[7] = 'p'; bytes[12] = (byte) marker;
        return new MockMultipartFile("file", "source.mp4", "video/mp4", bytes);
    }

    private long fileCount() throws Exception {
        try (var files = Files.list(storage.resolve("capture-videos"))) { return files.count(); }
    }

    private record Row(String id, String organization, String capture, String source, String hash, String key, String supersededBy) {}

    /** Models the two independent unique constraints, including simultaneous failed inserts. */
    private static class VideoDatabase extends JdbcTemplate {
        final List<Row> rows = new ArrayList<>();
        final AtomicInteger insertAttempts = new AtomicInteger();
        CountDownLatch insertBarrier;
        String lastQuery;

        @Override public synchronized List<Map<String, Object>> queryForList(String sql, Object... args) {
            lastQuery = sql;
            return rows.stream().filter(row -> row.organization.equals(args[0]) && row.capture.equals(args[1]))
                    .filter(row -> {
                        if (sql.contains("COALESCE")) return Objects.equals(row.key, args[2]) || row.hash.equals(args[3]);
                        if (sql.contains("source_asset_key IS NULL")) return row.key == null && row.supersededBy == null;
                        return !sql.contains("superseded_by IS NULL") || row.supersededBy == null;
                    }).map(row -> {
                        if (sql.contains("source_asset_key IS NULL")) return Map.<String, Object>of("id", row.id, "sourceUrl", row.source);
                        return Map.<String, Object>of("id", row.supersededBy == null ? row.id : row.supersededBy);
                    }).toList();
        }

        @Override public int update(String sql, Object... args) {
            assertTrue(sql.contains("source_asset_key"));
            insertAttempts.incrementAndGet();
            if (insertBarrier != null) {
                insertBarrier.countDown();
                try { assertTrue(insertBarrier.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new RuntimeException(interrupted); }
            }
            Row added = new Row((String) args[0], (String) args[1], (String) args[2], (String) args[3], (String) args[6], (String) args[7], null);
            synchronized (this) {
                boolean duplicate = rows.stream().anyMatch(row -> row.organization.equals(added.organization) && row.capture.equals(added.capture)
                        && (row.hash.equals(added.hash) || Objects.equals(row.key, added.key)));
                if (duplicate) throw new DuplicateKeyException("duplicate video asset");
                rows.add(added);
            }
            return 1;
        }
    }
}
