package com.yingdao.aicommerce.saas.application;

import com.yingdao.aicommerce.saas.domain.ModelConfig;
import com.yingdao.aicommerce.saas.mapper.ModelConfigMapper;
import com.yingdao.aicommerce.video.application.provider.DayanVideoGenerationProvider;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SeedanceVideoRoutingTest {
    @Test void allNewVideoRoutesUseMiniButHistoricalLookupKeepsOriginalConnection() {
        var mapper = mock(ModelConfigMapper.class);
        var cipher = mock(ModelSecretCipher.class);
        when(cipher.decrypt(any())).thenReturn("test-secret");
        var service = new ModelConfigService(mock(TenantContext.class), mapper, cipher, mock(OrganizationAccessPolicy.class));
        var legacy = config("DAYAN", "https://api.dayan.design", "minimax-h3");
        when(mapper.selectList(any())).thenReturn(List.of(legacy,
                config("DAYAN", "https://api.dayan.design", "minimax-h3-fast"),
                config("AUTO", "https://api.dayan.cloud", "minimax-h3"),
                config("H3_MULTI", "https://h3.zhongbiao.cloud", "MiniMax H3")));
        var routes = service.videoConnections("org");
        assertThat(routes).hasSize(3).allMatch(r -> r.modelId().equals(DayanVideoGenerationProvider.MODEL));
        assertThat(routes.getFirst().baseUrl()).isEqualTo("https://api.dayan.design");
        assertThat(routes.getFirst().apiKey()).isEqualTo("test-secret");
        when(mapper.selectOne(any())).thenReturn(legacy);
        assertThat(service.connectionById("org", legacy.getId()).orElseThrow().modelId()).isEqualTo("minimax-h3");
    }
    private ModelConfig config(String provider, String url, String model) {
        return new ModelConfig("org", "VIDEO", provider, url, model, "cipher", "test", false);
    }
}
