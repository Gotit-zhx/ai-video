package com.yingdao.aicommerce.video.application;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class AiVideoImageAttemptsTest {
    @TempDir Path directory;
    final ObjectMapper mapper = new ObjectMapper();
    @Test void timeoutsAndRestartsReuseReceiptWithoutRefundingBudget() {
        Path file = directory.resolve("requests.json");
        var first = new AiVideoImageAttempts(mapper).reserve(file, "prompt-a", 0);
        assertTrue(first.fresh());
        var restarted = new AiVideoImageAttempts(mapper);
        assertEquals(first.attempt(), restarted.reserve(file, "prompt-a", 100).attempt());
        assertFalse(restarted.reserve(file, "prompt-a", 100).fresh());
        restarted.reserve(file, "corrected-prompt", 100);
        assertThrows(IllegalStateException.class, () -> restarted.reserve(file, "third-prompt", 0));
        // Even after the budget is spent, the original result remains recoverable.
        assertEquals(first.attempt(), restarted.reserve(file, "prompt-a", 0).attempt());
    }
    @Test void confirmedRejectionCanUseOnlyOneRemainingSubmission() {
        Path file = directory.resolve("requests.json");
        var ledger = new AiVideoImageAttempts(mapper);
        var a = ledger.reserve(file, "prompt", 0);
        ledger.rejected(file, a.attempt().id());
        var b = ledger.reserve(file, "prompt", 0);
        assertNotEquals(a.attempt().id(), b.attempt().id());
        ledger.rejected(file, b.attempt().id());
        assertThrows(IllegalStateException.class, () -> new AiVideoImageAttempts(mapper).reserve(file, "prompt", 0));
    }
    @Test void legacyExpenditureAndCorruptLedgerCannotStartNewPaidCalls() throws Exception {
        assertThrows(IllegalStateException.class, () -> new AiVideoImageAttempts(mapper)
                .reserve(directory.resolve("legacy.json"), "prompt", 5));
        Path corrupt = directory.resolve("corrupt.json");
        Files.writeString(corrupt, "{broken");
        assertThrows(IllegalStateException.class, () -> new AiVideoImageAttempts(mapper).reserve(corrupt, "prompt", 0));
        assertEquals("{broken", Files.readString(corrupt));
        assertFalse(AiVideoImageProcessor.retryableImageTransientFailure(
                "图片自动恢复等待重试：IMAGE_GENERATION_LIMIT: 网络超时"));
        assertFalse(AiVideoImageProcessor.retryableImageTransientFailure("IMAGE_RESULT_UNKNOWN: 网络超时"));
    }
}
