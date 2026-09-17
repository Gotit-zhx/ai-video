package com.yingdao.aicommerce.video.web;

import com.yingdao.aicommerce.video.application.AiVideoTaskService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AiVideoDownloadControllerTest {
    @TempDir Path directory;
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
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk())
                .andExpect(content().bytes(bytes)).andExpect(header().string("Content-Length", "5"));
        verify(service).deletePreparedVideoBatch(prepared);
    }
}
