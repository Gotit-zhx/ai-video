package com.yingdao.aicommerce.video.application;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.web.client.ResourceAccessException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DayanAudioTranscriptionServiceTest {
    @Test
    void parsesSubSecondFiletransTimestampsAsMilliseconds() {
        Map<String, Object> document = Map.of("transcripts", List.of(Map.of(
                "sentences", List.of(
                        Map.of("begin_time", 120, "end_time", 860, "text", "第一句"),
                        Map.of("begin_time", 1000, "end_time", 2350, "text", "第二句")
                )
        )));

        DayanAudioTranscriptionService.Transcript transcript =
                DayanAudioTranscriptionService.parseTranscript(document);

        assertEquals("第一句第二句", transcript.fullText());
        assertEquals(new BigDecimal("0.120"), transcript.segments().getFirst().startSecond());
        assertEquals(new BigDecimal("0.860"), transcript.segments().getFirst().endSecond());
        assertEquals(new BigDecimal("1.000"), transcript.segments().get(1).startSecond());
        assertEquals(new BigDecimal("2.350"), transcript.segments().get(1).endSecond());
    }

    @Test
    void keepsGenericStartAndEndFieldsInSeconds() {
        Map<String, Object> document = Map.of("sentences", List.of(
                Map.of("start", 0.25, "end", 1.75, "text", "hello")
        ));

        DayanAudioTranscriptionService.Transcript transcript =
                DayanAudioTranscriptionService.parseTranscript(document);

        assertEquals(new BigDecimal("0.250"), transcript.segments().getFirst().startSecond());
        assertEquals(new BigDecimal("1.750"), transcript.segments().getFirst().endSecond());
    }

    @Test
    void retriesSafeReadsForEofAndTransientStatuses() {
        assertTrue(DayanAudioTranscriptionService.retryableReadFailure(
                new ResourceAccessException("EOF reached while reading")));
        assertTrue(DayanAudioTranscriptionService.retryableReadStatus(429));
        assertTrue(DayanAudioTranscriptionService.retryableReadStatus(502));
        assertFalse(DayanAudioTranscriptionService.retryableReadStatus(400));
        assertFalse(DayanAudioTranscriptionService.retryableReadStatus(401));
    }
}
