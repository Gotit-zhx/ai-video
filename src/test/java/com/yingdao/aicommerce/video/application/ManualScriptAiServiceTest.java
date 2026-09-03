package com.yingdao.aicommerce.video.application;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.assertThat;

class ManualScriptAiServiceTest {
    @Test
    void normalizesEverySpokenScriptLineToOffCameraNarration() {
        assertThat(ManualScriptAiService.normalizeOffCameraNarration("对白：今天看看新款"))
                .isEqualTo("旁白：今天看看新款");
        assertThat(ManualScriptAiService.normalizeOffCameraNarration("口播:  穿着很舒服"))
                .isEqualTo("旁白：穿着很舒服");
        assertThat(ManualScriptAiService.normalizeOffCameraNarration("")).isEmpty();
    }

    @Test
    void distributesNineShotsContinuouslyAcrossExactlyFifteenSeconds() {
        BigDecimal[] expected = {new BigDecimal("0.0"), new BigDecimal("1.7"), new BigDecimal("3.3"),
                new BigDecimal("5.0"), new BigDecimal("6.7"), new BigDecimal("8.3"),
                new BigDecimal("10.0"), new BigDecimal("11.7"), new BigDecimal("13.3"),
                new BigDecimal("15.0")};
        for (int index = 0; index < expected.length; index++) {
            assertThat(ManualScriptAiService.boundary(index)).isEqualByComparingTo(expected[index]);
        }
    }
}
