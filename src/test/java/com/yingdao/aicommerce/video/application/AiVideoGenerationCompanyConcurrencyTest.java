package com.yingdao.aicommerce.video.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.yingdao.aicommerce.saas.application.ModelConfigService;
import com.yingdao.aicommerce.storage.OssStorageService;
import com.yingdao.aicommerce.video.application.provider.VideoProviderRegistry;
import com.yingdao.aicommerce.video.domain.AiVideoGenerationJob;
import com.yingdao.aicommerce.video.mapper.AiVideoGenerationJobMapper;
import com.yingdao.aicommerce.video.mapper.AiVideoTaskAssetMapper;
import com.yingdao.aicommerce.video.mapper.AiVideoTaskItemMapper;
import com.yingdao.aicommerce.video.mapper.AiVideoTaskMapper;
import com.yingdao.aicommerce.video.mapper.VideoScriptMapper;
import org.junit.jupiter.api.Test;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;

import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiVideoGenerationCompanyConcurrencyTest {

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void activeSubmissionCountIsScopedToOneCompany() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "test"),
                AiVideoGenerationJob.class
        );
        AiVideoGenerationJobMapper jobMapper = mock(AiVideoGenerationJobMapper.class);
        when(jobMapper.selectCount(any())).thenReturn(37L);
        ObjectProvider<OssStorageService> ossStorage = mock(ObjectProvider.class);
        when(ossStorage.getIfAvailable()).thenReturn(null);
        Executor directExecutor = Runnable::run;
        AiVideoGenerationService service = new AiVideoGenerationService(
                mock(AiVideoTaskMapper.class), mock(AiVideoTaskItemMapper.class),
                mock(AiVideoTaskAssetMapper.class), jobMapper, mock(VideoScriptMapper.class),
                mock(ModelConfigService.class), mock(VideoProviderRegistry.class),
                mock(VideoReferenceLinkService.class), mock(VideoReferenceDeliveryService.class),
                ossStorage, mock(ObjectMapper.class), directExecutor, 500
        );

        assertThat(service.activeVideoSubmissions("org-client")).isEqualTo(37);
        assertThat(service.videoSubmissionLimit()).isEqualTo(500);

        ArgumentCaptor<LambdaQueryWrapper<AiVideoGenerationJob>> queryCaptor =
                ArgumentCaptor.forClass((Class) LambdaQueryWrapper.class);
        verify(jobMapper).selectCount(queryCaptor.capture());
        assertThat(queryCaptor.getValue().getSqlSegment()).contains("organization_id");
        assertThat(queryCaptor.getValue().getParamNameValuePairs()).containsValue("org-client");
    }
}
