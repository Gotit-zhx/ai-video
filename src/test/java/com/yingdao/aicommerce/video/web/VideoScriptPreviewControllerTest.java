package com.yingdao.aicommerce.video.web;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.video.application.ManualScriptAiService;
import com.yingdao.aicommerce.video.application.VideoScriptService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class VideoScriptPreviewControllerTest {
    @TempDir Path directory;
    private final VideoScriptService service = mock(VideoScriptService.class);
    private final PlatformPrincipal principal = new PlatformPrincipal(
            "user", "org", "org", "13800000000", "测试用户", "password", true,
            List.of("ADMIN"), List.of("video:script:view"));

    @Test
    void servesTheAuthorizedLocalJpegWithoutAnExternalRedirect() throws Exception {
        Path file = directory.resolve("shot-001.jpg");
        byte[] jpeg = {(byte) 0xff, (byte) 0xd8, 1, 2, (byte) 0xff, (byte) 0xd9};
        Files.write(file, jpeg);
        Instant modified = Files.getLastModifiedTime(file).toInstant();
        when(service.shotPreview("script", "shot", principal))
                .thenReturn(new VideoScriptService.ShotPreview(file, modified));

        var response = controller().shotPreview("script", "shot", principal);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getLocation()).isNull();
        assertThat(response.getHeaders().getContentType().toString()).isEqualTo("image/jpeg");
        assertThat(response.getHeaders().getCacheControl()).contains("private");
        try (var input = ((FileSystemResource) response.getBody()).getInputStream()) {
            assertThat(input.readAllBytes()).isEqualTo(jpeg);
        }
        verify(service).shotPreview("script", "shot", principal);
    }

    @Test
    void preservesCompanyAccessChecksAndMissingPreviewErrors() {
        for (HttpStatus status : List.of(HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND)) {
            BusinessException error = new BusinessException(status, "预览不可访问");
            doThrow(error).when(service).shotPreview("script", "shot", principal);
            assertThatThrownBy(() -> controller().shotPreview("script", "shot", principal)).isSameAs(error);
        }
    }

    private VideoScriptController controller() {
        return new VideoScriptController(service, mock(ManualScriptAiService.class));
    }
}
