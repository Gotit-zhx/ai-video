package com.yingdao.aicommerce.video.web;

import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.video.application.AiVideoTaskService;
import com.yingdao.aicommerce.video.application.GeneralVideoModel;
import com.yingdao.aicommerce.video.domain.AiVideoTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
        mvc().perform(multipart("/api/v1/general-video-tasks")
                        .param("prompt", prompt)
                        .param("aspectRatio", "adaptive")
                        .param("durationSeconds", "-1")
                        .param("autoStart", "true"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(task.getId()));
    }

    @Test
    void listsSelectableModelsAndAcceptsTheRequestedOptionsWithoutFiles() throws Exception {
        String id = "doubao-seedance-2-0-260128";
        var model = new GeneralVideoModel(id, "Seedance 2.0", List.of("480p", "720p", "1080p", "4k"));
        when(service.generalVideoOptions(isNull())).thenReturn(new GeneralVideoModel.Options(
                List.of(model), List.of("adaptive", "21:9"), 4, 15));
        var command = new AiVideoTaskService.GeneralCreateCommand(null, "日出", 12,
                "21:9", "4k", "QUALITY", false, null, null, id);
        var task = AiVideoTask.general("org", "日出", "{}", 12, "21:9", "4k",
                "{\"videoModelId\":\"" + id + "\"}", "{}", "user");
        when(service.createGeneral(eq(command), isNull(), isNull())).thenReturn(task);
        var mvc = mvc();

        mvc.perform(get("/api/v1/general-video-tasks/options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.models[0].id").value(id))
                .andExpect(jsonPath("$.data.models[0].resolutions[3]").value("4k"));
        mvc.perform(multipart("/api/v1/general-video-tasks")
                        .param("prompt", "日出").param("modelId", id)
                        .param("aspectRatio", "21:9").param("resolution", "4k").param("durationSeconds", "12"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.modelId").value(id));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1080p", "2K", "4K"})
    void rejectsUnsupportedSpeedResolutionBeforeCreatingTask(String resolution) {
        var file = new MockMultipartFile("files", "reference.png", "image/png", new byte[]{1});

        assertThatThrownBy(() -> controller.create("task", "prompt", 6, "9:16",
                resolution, "SPEED", null, false, List.of(file), null, null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("768P")
                .satisfies(exception -> assertThat(((BusinessException) exception).getStatus().value()).isEqualTo(400));
        verifyNoInteractions(service);
    }

    private MockMvc mvc() {
        var validatedController = new ProxyFactory(controller);
        validatedController.setProxyTargetClass(true);
        validatedController.addAdvice(new MethodValidationInterceptor());
        return MockMvcBuilders.standaloneSetup(validatedController.getProxy())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
    }
}
