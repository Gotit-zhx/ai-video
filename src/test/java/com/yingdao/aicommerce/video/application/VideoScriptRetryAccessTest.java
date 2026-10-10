package com.yingdao.aicommerce.video.application;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.video.domain.VideoScript;
import com.yingdao.aicommerce.video.mapper.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class VideoScriptRetryAccessTest {
    private final VideoScriptMapper scripts = mock(VideoScriptMapper.class);
    private final VideoScriptAnalysisProcessor processor = mock(VideoScriptAnalysisProcessor.class);
    private final VideoScriptService service = new VideoScriptService("target/retry-test", scripts,
            mock(VideoScriptShotMapper.class), processor, mock(VideoFrameExtractor.class), new ObjectMapper());
    private PlatformPrincipal user(String id, String org, String role) {
        return new PlatformPrincipal(id, org, org, id, id, "", true, List.of(role), List.of("video:script:manage"));
    }
    private VideoScript failed() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(new org.apache.ibatis.session.Configuration(), ""), VideoScript.class);
        var s = new VideoScript("s", "org", "test", "UPLOAD", "MODEL_WEAR", "a.mp4", "/a.mp4",
                "video/mp4", 1L, "泰国", null, "creator");
        s.fail("upstream failed");
        when(scripts.selectById("s")).thenReturn(s);
        when(scripts.update(isNull(), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(1);
        return s;
    }
    @Test void creatorCanRetry() {
        failed(); assertThat(service.retry("s", user("creator", "org", "OPERATOR")).getStatus()).isEqualTo("QUEUED");
        verify(processor).analyze("s");
    }
    @Test void companyAdminCanRetryWithoutChangingOwner() {
        failed(); var s = service.retry("s", user("admin", "org", "ADMIN"));
        assertThat(s.getStatus()).isEqualTo("QUEUED"); assertThat(s.getCreatedBy()).isEqualTo("creator");
        verify(processor).analyze("s");
    }
    @Test void otherOperatorCannotRetry() {
        failed(); assertThatThrownBy(() -> service.retry("s", user("other", "org", "OPERATOR"))).hasMessageContaining("无权");
        verifyNoInteractions(processor);
    }
    @Test void otherCompanyAdminCannotRetry() {
        failed(); assertThatThrownBy(() -> service.retry("s", user("admin", "other-org", "ADMIN"))).hasMessageContaining("不存在");
        verifyNoInteractions(processor);
    }
}
