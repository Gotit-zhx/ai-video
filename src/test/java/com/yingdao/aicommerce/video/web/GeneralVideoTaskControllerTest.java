package com.yingdao.aicommerce.video.web;

import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.video.application.AiVideoTaskService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class GeneralVideoTaskControllerTest {
    private final AiVideoTaskService service = mock(AiVideoTaskService.class);
    private final GeneralVideoTaskController controller = new GeneralVideoTaskController(service);

    @ParameterizedTest
    @ValueSource(strings = {"1080p", "2K", "4K"})
    void rejectsUnsupportedSpeedResolutionBeforeCreatingTask(String resolution) {
        var file = new MockMultipartFile("files", "reference.png", "image/png", new byte[]{1});

        assertThatThrownBy(() -> controller.create("task", "prompt", 6, "9:16",
                resolution, "SPEED", false, List.of(file), null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("768P")
                .satisfies(exception -> assertThat(((BusinessException) exception).getStatus().value()).isEqualTo(400));
        verifyNoInteractions(service);
    }
}
