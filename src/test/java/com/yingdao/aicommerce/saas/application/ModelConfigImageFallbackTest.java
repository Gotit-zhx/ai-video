package com.yingdao.aicommerce.saas.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.yingdao.aicommerce.saas.domain.ModelConfig;
import com.yingdao.aicommerce.saas.mapper.ModelConfigMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ModelConfigImageFallbackTest {
    private static final String FALLBACK = "grok-imagine-image-2.0";
    private static final String BASE_URL = "https://images.example.test/v1";
    private final ModelConfigMapper mapper = mock(ModelConfigMapper.class);
    private final ModelSecretCipher cipher = mock(ModelSecretCipher.class);
    private final TenantContext tenant = mock(TenantContext.class);
    private final ModelConfigService service = new ModelConfigService(tenant, mapper, cipher, mock(OrganizationAccessPolicy.class));

    @BeforeAll
    static void initializeMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "image-fallback-test"), ModelConfig.class);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void sameConnectionBackupDoesNotReplaceDefaultPrimaryAndQueriesAreScoped() {
        ModelConfig primary = primary();
        ModelConfig backup = config("org", "IMAGE", "AUTO", BASE_URL, FALLBACK, "backup-cipher", false);
        when(mapper.selectList(any())).thenReturn(List.of(backup));
        when(cipher.decrypt("backup-cipher")).thenReturn("unit-only-key");

        var connection = service.imageConnection("org").orElseThrow();

        assertThat(connection.id()).isEqualTo(primary.getId());
        assertThat(connection.modelId()).isEqualTo("gpt-image-2");
        assertThat(connection.copyrightFallbackModelIds()).containsExactly(FALLBACK);
        assertThat(connection.imageRequestModel()).containsEntry("modelId", "gpt-image-2")
                .containsEntry("apiKey", "unit-only-key")
                .containsEntry("copyrightFallbackModels", List.of(connection.copyrightFallbackModels().getFirst().imageRequestModel()));
        assertThat(connection.copyrightFallbackModels().getFirst().copyrightFallbackModels()).isEmpty();
        ArgumentCaptor<LambdaQueryWrapper<ModelConfig>> selected = ArgumentCaptor.forClass((Class) LambdaQueryWrapper.class);
        verify(mapper).selectOne(selected.capture());
        assertThat(selected.getValue().getSqlSegment()).contains("organization_id", "model_type", "status", "ORDER BY default_model DESC,updated_at DESC", "LIMIT 1");
        assertThat(selected.getValue().getParamNameValuePairs()).containsValue("org").containsValue("IMAGE").containsValue("ACTIVE");
        ArgumentCaptor<LambdaQueryWrapper<ModelConfig>> backups = ArgumentCaptor.forClass((Class) LambdaQueryWrapper.class);
        verify(mapper).selectList(backups.capture());
        assertThat(backups.getValue().getSqlSegment()).contains("organization_id", "model_type", "status", "base_url", "model_id");
        assertThat(backups.getValue().getParamNameValuePairs()).containsValue("org").containsValue("IMAGE").containsValue("ACTIVE").containsValue(BASE_URL).containsValue(FALLBACK);
        verifyNoMoreInteractions(mapper);
        assertThat(primary.isDefaultModel()).isTrue();
        assertThat(backup.isDefaultModel()).isFalse();
    }

    @Test
    void foreignTenantBackupIsNeverAttachedOrDecrypted() {
        primary();
        when(mapper.selectList(any())).thenReturn(List.of(config("other-org", "IMAGE", "AUTO", BASE_URL, FALLBACK, "foreign-cipher", false)));
        assertThat(service.imageConnection("org").orElseThrow().copyrightFallbackModelIds()).isEmpty();
        verify(cipher, never()).decrypt("foreign-cipher");
    }

    @Test
    void disabledBackupIsNeverAttachedOrDecrypted() {
        primary();
        ModelConfig disabled = config("org", "IMAGE", "AUTO", BASE_URL, FALLBACK, "disabled-cipher", false);
        disabled.changeStatus("DISABLED");
        when(mapper.selectList(any())).thenReturn(List.of(disabled));
        assertThat(service.imageConnection("org").orElseThrow().copyrightFallbackModelIds()).isEmpty();
        verify(cipher, never()).decrypt("disabled-cipher");
    }

    @Test
    void differentEndpointProviderFormatTypeOrModelAreNotEligible() {
        primary();
        ModelConfig differentFormat = spy(config("org", "IMAGE", "AUTO", BASE_URL, FALLBACK, "other-format", false));
        when(differentFormat.getApiFormat()).thenReturn("OTHER_FORMAT");
        when(mapper.selectList(any())).thenReturn(List.of(
                config("org", "IMAGE", "AUTO", "https://other.example.test/v1", FALLBACK, "other-endpoint", false),
                config("org", "IMAGE", "OTHER", BASE_URL, FALLBACK, "other-provider", false),
                differentFormat,
                config("org", "DRIVER", "AUTO", BASE_URL, FALLBACK, "other-type", false),
                config("org", "IMAGE", "AUTO", BASE_URL, FALLBACK + "-other", "other-model", false)));
        assertThat(service.imageConnection("org").orElseThrow().copyrightFallbackModelIds()).isEmpty();
        verify(cipher).decrypt("primary-cipher");
        verifyNoMoreInteractions(cipher);
    }

    @Test
    void backupUsesItsOwnKeyWithoutReplacingThePrimaryKey() {
        primary();
        when(mapper.selectList(any())).thenReturn(List.of(config("org", "IMAGE", "AUTO", BASE_URL, FALLBACK, "different-key-cipher", false)));
        when(cipher.decrypt("different-key-cipher")).thenReturn("another-unit-only-key");
        var connection = service.imageConnection("org").orElseThrow();
        assertThat(connection.apiKey()).isEqualTo("unit-only-key");
        assertThat(connection.imageRequestModel()).containsEntry("apiKey", "unit-only-key");
        assertThat(connection.copyrightFallbackModels()).hasSize(1);
        var fallback = connection.copyrightFallbackModels().getFirst();
        assertThat(fallback.modelId()).isEqualTo(FALLBACK);
        assertThat(fallback.imageRequestModel()).containsEntry("apiKey", "another-unit-only-key")
                .containsEntry("copyrightFallbackModels", List.of());
        assertThat(connection.imageRequestModel()).containsEntry("copyrightFallbackModels", List.of(fallback.imageRequestModel()));
    }

    @Test
    void brokenBackupKeyDoesNotBreakPrimaryImageRequests() {
        ModelConfig primary = primary();
        when(mapper.selectList(any())).thenReturn(List.of(config("org", "IMAGE", "AUTO", BASE_URL, FALLBACK, "broken-cipher", false)));
        when(cipher.decrypt("broken-cipher")).thenThrow(new IllegalStateException("Unable to decrypt model API key"));
        var result = service.imageConnection("org").orElseThrow();
        assertThat(result.id()).isEqualTo(primary.getId());
        assertThat(result.copyrightFallbackModelIds()).isEmpty();
    }

    @Test
    void grokPrimaryDoesNotCreateAFallbackLoop() {
        when(mapper.selectOne(any())).thenReturn(config("org", "IMAGE", "AUTO", BASE_URL, FALLBACK, "primary-cipher", true));
        when(cipher.decrypt("primary-cipher")).thenReturn("unit-only-key");
        assertThat(service.imageConnection("org").orElseThrow().copyrightFallbackModelIds()).isEmpty();
        verify(mapper, never()).selectList(any());
    }

    @Test
    void missingPrimaryDoesNotPromoteBackupByASecondQuery() {
        when(mapper.selectOne(any())).thenReturn(null);
        assertThat(service.imageConnection("org")).isEmpty();
        verify(mapper, never()).selectList(any());
        verifyNoInteractions(cipher);
    }

    @Test
    void defaultImageConnectionUsesTenantAndLegacyConstructorHasNoFallbacks() {
        when(tenant.organizationId()).thenReturn("org");
        ModelConfig primary = primary();
        when(mapper.selectList(any())).thenReturn(List.of());
        assertThat(service.defaultImageConnection().orElseThrow().id()).isEqualTo(primary.getId());
        var legacy = new ModelConfigService.RuntimeModelConnection("id", BASE_URL, "model", "OPENAI_COMPATIBLE", "AUTO", "unit-only-key");
        assertThat(legacy.copyrightFallbackModelIds()).isEmpty();
        var fallbacks = new ArrayList<>(List.of(new ModelConfigService.RuntimeModelConnection("backup", BASE_URL, FALLBACK, "OPENAI_COMPATIBLE", "AUTO", "backup-unit-only-key")));
        var immutable = new ModelConfigService.RuntimeModelConnection("id", BASE_URL, "model", "OPENAI_COMPATIBLE", "AUTO", "unit-only-key", fallbacks);
        fallbacks.clear();
        assertThat(immutable.copyrightFallbackModelIds()).containsExactly(FALLBACK);
    }

    private ModelConfig primary() {
        ModelConfig primary = config("org", "IMAGE", "AUTO", BASE_URL, "gpt-image-2", "primary-cipher", true);
        when(mapper.selectOne(any())).thenReturn(primary);
        when(cipher.decrypt("primary-cipher")).thenReturn("unit-only-key");
        return primary;
    }

    private ModelConfig config(String organization, String type, String provider, String baseUrl, String modelId, String encryptedKey, boolean defaultModel) {
        return new ModelConfig(organization, type, provider, baseUrl, modelId, encryptedKey, "test", defaultModel);
    }
}
