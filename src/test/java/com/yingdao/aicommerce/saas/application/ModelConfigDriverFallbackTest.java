package com.yingdao.aicommerce.saas.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.yingdao.aicommerce.saas.domain.ModelConfig;
import com.yingdao.aicommerce.saas.mapper.ModelConfigMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ModelConfigDriverFallbackTest {
    @Test void preservesPrimaryAndNeverDecryptsForeignOrImageRoutes() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "driver-fallback"), ModelConfig.class);
        var mapper = mock(ModelConfigMapper.class);
        var cipher = mock(ModelSecretCipher.class);
        var service = new ModelConfigService(mock(TenantContext.class), mapper, cipher, mock(OrganizationAccessPolicy.class));
        var primary = config("org", "DRIVER", "primary", true);
        var backup = config("org", "DRIVER", "backup", false);
        when(mapper.selectOne(any())).thenReturn(primary);
        when(mapper.selectList(any())).thenReturn(List.of(config("other", "DRIVER", "foreign", false),
                config("org", "IMAGE", "image", false), config("org", "DRIVER", "broken", false), backup));
        when(cipher.decrypt("primary")).thenReturn("p");
        when(cipher.decrypt("backup")).thenReturn("b");
        when(cipher.decrypt("broken")).thenThrow(new IllegalStateException("bad ciphertext"));
        var result = service.driverConnection("org").orElseThrow();
        assertThat(result.id()).isEqualTo(primary.getId());
        assertThat(result.fallbackModels()).extracting(ModelConfigService.RuntimeModelConnection::id).containsExactly(backup.getId());
        assertThat(result.driverRequestModel()).containsEntry("fallbackModels", List.of(result.fallbackModels().getFirst().driverRequestModel()));
        assertThat(result.copyrightFallbackModels()).isEmpty();
        verify(cipher, never()).decrypt("foreign"); verify(cipher, never()).decrypt("image");
    }
    private ModelConfig config(String org, String type, String key, boolean primary) {
        return new ModelConfig(org, type, "AUTO", "https://"+key+".test", key, key, "test", primary);
    }
}
