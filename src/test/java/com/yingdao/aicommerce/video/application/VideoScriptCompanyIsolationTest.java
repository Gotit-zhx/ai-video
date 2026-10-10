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
            mock(VideoFrameExtractor.class),
            mock(ObjectMapper.class)
    );

    @Test
    void scriptCanOnlyBeReadInsideItsCompany() {
        VideoScript script = new VideoScript(
                "script-1", "org-a", "A 公司脚本", "MANUAL", "MODEL_WEAR",
                null, null, null, null, "中国", null, "user-a"
        );
        when(scriptMapper.selectById("script-1")).thenReturn(script);

        assertThat(service.status("script-1", principal("org-a", "a"))).isSameAs(script);
        assertThatThrownBy(() -> service.status("script-1", principal("org-b", "b")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("视频脚本不存在");
    }

    @Test
    void externallyRegisteredUserCanReadCompanyScripts() {
        VideoScript script = new VideoScript(
                "script-1", "org-a", "其他人的脚本", "MANUAL", "MODEL_WEAR",
                null, null, null, null, "中国", null, "user-a"
        );
        when(scriptMapper.selectById("script-1")).thenReturn(script);

        assertThat(service.status("script-1", externalPrincipal("user-a"))).isSameAs(script);
        assertThat(service.status("script-1", externalPrincipal("user-b"))).isSameAs(script);
    }

    private PlatformPrincipal principal(String organizationId, String organizationCode) {
        return new PlatformPrincipal(
                "user-1", organizationId, organizationCode, "13800000000", "测试用户",
                "password", true, List.of("ADMIN"), List.of("video:script:view")
        );
    }

    private PlatformPrincipal externalPrincipal(String userId) {
        return new PlatformPrincipal(
                userId, "org-a", "a", "google-user", "Google 用户",
                null, true, "GOOGLE", List.of("OPERATOR"), List.of("video:script:view")
        );
    }
}
