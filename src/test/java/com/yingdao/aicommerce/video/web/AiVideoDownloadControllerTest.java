package com.yingdao.aicommerce.video.web;

import com.yingdao.aicommerce.video.application.AiVideoTaskService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.file.Path;
import java.nio.file.Files;
import java.io.ByteArrayInputStream;
import java.util.List;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.common.exception.GlobalExceptionHandler;
import org.springframework.http.HttpStatus;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AiVideoDownloadControllerTest {
    @TempDir Path directory;

    @Test void remoteVideoDownloadStreamsAnAttachmentWithoutRedirectOrArchive() throws Exception {
        var service = mock(AiVideoTaskService.class);
        byte[] bytes = {0, 0, 0, 24, 'f', 't', 'y', 'p'};
        var file = new AiVideoTaskService.AssetFile(null, "https://provider.example/video.mp4?secret=private",
                "video/mp4", bytes.length);
        var input = new ByteArrayInputStream(bytes) {
            boolean closed;
            @Override public void close() { closed = true; }
        };
        when(service.asset(eq("task"), eq("asset"), isNull())).thenReturn(file);
        when(service.openVideoDownload(file)).thenReturn(input);
        var mvc = MockMvcBuilders.standaloneSetup(new AiVideoTaskController(service))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
        mvc.perform(get("/api/v1/ai-video-tasks/task/assets/asset")
                        .param("download", "true").param("filename", "任务-01-成片.mp4"))
                .andExpect(status().isOk()).andExpect(content().bytes(bytes))
                .andExpect(header().string("Content-Type", "video/mp4"))
                .andExpect(header().string("Content-Length", "8"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment;")))
                .andExpect(header().doesNotExist("Location"));
        org.assertj.core.api.Assertions.assertThat(input.closed).isTrue();
        verify(service, never()).prepareVideoBatch(any());
        verify(service).asset(eq("task"), eq("asset"), isNull());
    }

    @Test void videoPreviewKeepsItsOriginalRedirect() throws Exception {
        var service = mock(AiVideoTaskService.class);
        var file = new AiVideoTaskService.AssetFile(null, "https://provider.example/video.mp4", "video/mp4", 10);
        when(service.asset(eq("task"), eq("asset"), isNull())).thenReturn(file);
        when(service.signedAssetUrl(file.location(), false)).thenReturn(file.location());
        var mvc = MockMvcBuilders.standaloneSetup(new AiVideoTaskController(service))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
        mvc.perform(get("/api/v1/ai-video-tasks/task/assets/asset"))
                .andExpect(status().isTemporaryRedirect()).andExpect(header().string("Location", file.location()));
        verify(service, never()).openVideoDownload(any());
    }

    @Test void sourceFailureReturnsAnActionableErrorBeforeAnyVideoBytes() throws Exception {
        var service = mock(AiVideoTaskService.class);
        var file = new AiVideoTaskService.AssetFile(null, "https://provider.example/video.mp4", "video/mp4", 10);
        when(service.asset(eq("task"), eq("asset"), isNull())).thenReturn(file);
        when(service.openVideoDownload(file)).thenThrow(new BusinessException(HttpStatus.BAD_GATEWAY, "视频源文件暂时无法读取"));
        var mvc = MockMvcBuilders.standaloneSetup(new AiVideoTaskController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
        mvc.perform(get("/api/v1/ai-video-tasks/task/assets/asset").param("download", "true"))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.message").value("视频源文件暂时无法读取"))
                .andExpect(header().doesNotExist("Content-Disposition"));
    }

    @Test void localVideoStillDownloadsWithoutPacking() throws Exception {
        var service = mock(AiVideoTaskService.class);
        var path = Files.write(directory.resolve("result.mp4"), new byte[]{1, 2, 3});
        when(service.asset(eq("task"), eq("asset"), isNull())).thenReturn(
                new AiVideoTaskService.AssetFile(path, null, "video/mp4", 3));
        var mvc = MockMvcBuilders.standaloneSetup(new AiVideoTaskController(service))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
        mvc.perform(get("/api/v1/ai-video-tasks/task/assets/asset").param("download", "true"))
                .andExpect(status().isOk()).andExpect(content().bytes(new byte[]{1, 2, 3}))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment;")));
        verify(service, never()).prepareVideoBatch(any());
    }

    @Test void zipResponseUsesStreamingHandlerAndCleansTemporaryArchive() throws Exception {
        var service = mock(AiVideoTaskService.class);
        byte[] bytes = new byte[]{'P', 'K', 3, 4, 10};
        Path file = directory.resolve("result.zip");
        Files.write(file, bytes);
        var batch = new AiVideoTaskService.VideoBatch("org", "task", "验收", List.of());
        var prepared = new AiVideoTaskService.PreparedVideoBatch(file, null, bytes.length);
        when(service.videoBatch(eq("task"), isNull())).thenReturn(batch);
        when(service.prepareVideoBatch(batch)).thenReturn(prepared);
        var mvc = MockMvcBuilders.standaloneSetup(new AiVideoTaskController(service))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
        var result = mvc.perform(get("/api/v1/ai-video-tasks/task/video-results.zip"))
                .andExpect(request().asyncStarted()).andReturn();
        org.assertj.core.api.Assertions.assertThat(result.getRequest().getAsyncContext().getTimeout())
                .isEqualTo(java.time.Duration.ofMinutes(30).toMillis());
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk())
                .andExpect(content().bytes(bytes)).andExpect(header().string("Content-Length", "5"));
        verify(service).deletePreparedVideoBatch(prepared);
    }
}
