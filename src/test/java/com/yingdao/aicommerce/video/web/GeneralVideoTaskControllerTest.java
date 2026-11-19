package com.yingdao.aicommerce.video.web;

import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.video.application.AiVideoTaskService;
import com.yingdao.aicommerce.video.domain.AiVideoTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.MethodValidationInterceptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GeneralVideoTaskControllerTest {
    private final AiVideoTaskService service = mock(AiVideoTaskService.class);
    private final GeneralVideoTaskController controller = new GeneralVideoTaskController(service);

    @Test
    void acceptsPromptOnlyMultipartWithThePageDefaultAspectRatio() throws Exception {
        String prompt = "镜头推向山间日出";
        var command = new AiVideoTaskService.GeneralCreateCommand(null, prompt, -1,
                "adaptive", "720p", "QUALITY", true);
        var task = AiVideoTask.general("org", "山间日出", "{}", -1,
                "adaptive", "720p", "{}", "{}", "user");
        when(service.createGeneral(eq(command), isNull(), isNull())).thenReturn(task);
        var validatedController = new ProxyFactory(controller);
        validatedController.setProxyTargetClass(true);
        validatedController.addAdvice(new MethodValidationInterceptor());
        var mvc = MockMvcBuilders.standaloneSetup(validatedController.getProxy())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();

        mvc.perform(multipart("/api/v1/general-video-tasks")
                        .param("prompt", prompt)
                        .param("aspectRatio", "adaptive")
                        .param("durationSeconds", "-1")
                        .param("autoStart", "true"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(task.getId()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1080p", "2K", "4K"})
    void rejectsUnsupportedSpeedResolutionBeforeCreatingTask(String resolution) {
        var file = new MockMultipartFile("files", "reference.png", "image/png", new byte[]{1});

        assertThatThrownBy(() -> controller.create("task", "prompt", 6, "9:16",
                resolution, "SPEED", false, List.of(file), null, null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("768P")
                .satisfies(exception -> assertThat(((BusinessException) exception).getStatus().value()).isEqualTo(400));
        verifyNoInteractions(service);
    }
}
