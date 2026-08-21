package com.yingdao.aicommerce.video.application;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.video.domain.VideoScript;
import com.yingdao.aicommerce.video.mapper.VideoScriptMapper;
import com.yingdao.aicommerce.video.mapper.VideoScriptShotMapper;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VideoScriptCompanyIsolationTest {

    private final VideoScriptMapper scriptMapper = mock(VideoScriptMapper.class);
    private final VideoScriptService service = new VideoScriptService(
            "target/test-video-scripts",
            scriptMapper,
            mock(VideoScriptShotMapper.class),
            mock(VideoScriptAnalysisProcessor.class),
            mock(ObjectMapper.class)
    );

    @Test
    void scriptCanOnlyBeReadInsideItsCompany() {
        VideoScript script = new VideoScript(
                "script-1", "org-a", "A 公司脚本", "MANUAL",
                null, null, null, null, "中国", null, "user-a"
        );
        when(scriptMapper.selectById("script-1")).thenReturn(script);

        assertThat(service.status("script-1", principal("org-a", "a"))).isSameAs(script);
        assertThatThrownBy(() -> service.status("script-1", principal("org-b", "b")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("视频脚本不存在");
    }

    private PlatformPrincipal principal(String organizationId, String organizationCode) {
        return new PlatformPrincipal(
                "user-1", organizationId, organizationCode, "13800000000", "测试用户",
                "password", true, List.of("ADMIN"), List.of("video:script:view")
        );
    }
}
