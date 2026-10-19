package com.yingdao.aicommerce.saas.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.saas.domain.ModelConfig;
import com.yingdao.aicommerce.saas.mapper.ModelConfigMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ModelConfigTransparentArtworkTest {
    private static final String BASE = "https://configured.example.test/v1";
    private final ModelConfigMapper mapper = mock(ModelConfigMapper.class);
    private final ModelSecretCipher cipher = mock(ModelSecretCipher.class);
    private final ModelConfigService service = new ModelConfigService(mock(TenantContext.class), mapper, cipher, mock(OrganizationAccessPolicy.class));

    @BeforeAll static void metadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "transparent-artwork-model-test"), ModelConfig.class);
    }

    @Test void preservesTheCurrentImg2ConfigurationAndNeverLoadsGrokFallbackCredentials() {
        var selected = config("org", "IMAGE", "gpt-image-2", "selected-cipher", true);
        when(mapper.selectOne(any())).thenReturn(selected);
        when(cipher.decrypt("selected-cipher")).thenReturn("original-tenant-key");
        var connection = service.requireTransparentArtworkConnection("org");
        assertThat(connection.id()).isEqualTo(selected.getId());
        assertThat(connection.baseUrl()).isEqualTo(selected.getBaseUrl());
        assertThat(connection.modelId()).isEqualTo(selected.getModelId());
        assertThat(connection.apiFormat()).isEqualTo(selected.getApiFormat());
        assertThat(connection.provider()).isEqualTo("AUTO");
        assertThat(connection.apiKey()).isEqualTo("original-tenant-key");
        assertThat(connection.copyrightFallbackModels()).isEmpty();
        assertThat(connection.imageRequestModel()).containsEntry("copyrightFallbackModels", List.of());
        assertThat(selected.isDefaultModel()).isTrue();
        verify(mapper, never()).selectList(any());verify(cipher).decrypt("selected-cipher");verifyNoMoreInteractions(cipher);
    }

    @Test @SuppressWarnings({"unchecked", "rawtypes"})
    void nonImg2DefaultSelectsOnlyConfiguredTenantImg2WithItsOwnEndpointAndKey() {
        var selected = config("org", "IMAGE", "grok-imagine-image-2.0", "grok-cipher", true);
        var img2 = new ModelConfig("org", "IMAGE", "AUTO", "https://own-img2.example.test/v1", "gpt-image-2", "img2-cipher", "unit", false);
        when(mapper.selectOne(any())).thenReturn(selected);when(mapper.selectList(any())).thenReturn(List.of(img2));
        when(cipher.decrypt("img2-cipher")).thenReturn("configured-img2-key");
        var connection = service.transparentArtworkConnection("org").orElseThrow();
        assertThat(connection.id()).isEqualTo(img2.getId());assertThat(connection.baseUrl()).isEqualTo(img2.getBaseUrl());
        assertThat(connection.apiKey()).isEqualTo("configured-img2-key");assertThat(connection.copyrightFallbackModels()).isEmpty();
        assertThat(selected.isDefaultModel()).isTrue();assertThat(img2.isDefaultModel()).isFalse();
        verify(cipher, never()).decrypt("grok-cipher");
        ArgumentCaptor<LambdaQueryWrapper<ModelConfig>> primary = ArgumentCaptor.forClass((Class) LambdaQueryWrapper.class);
        verify(mapper).selectOne(primary.capture());
        var primaryQuery = primary.getValue();
        assertThat(primaryQuery.getSqlSegment()).contains("organization_id", "model_type", "status", "ORDER BY default_model DESC,updated_at DESC", "LIMIT 1");
        assertThat(primaryQuery.getParamNameValuePairs()).containsValue("org").containsValue("IMAGE").containsValue("ACTIVE");
        ArgumentCaptor<LambdaQueryWrapper<ModelConfig>> alternatives = ArgumentCaptor.forClass((Class) LambdaQueryWrapper.class);
        verify(mapper).selectList(alternatives.capture());
        var candidates = alternatives.getValue();
        assertThat(candidates.getSqlSegment()).contains("organization_id", "model_type", "status", "model_id IN", "ORDER BY default_model DESC,updated_at DESC,id ASC");
        assertThat(candidates.getParamNameValuePairs()).containsValue("org").containsValue("IMAGE").containsValue("ACTIVE")
                .containsValue("gpt-image-2").containsValue("gpt-image-2-2026-04-21");
        verifyNoMoreInteractions(mapper);
    }

    @Test void keepsConfiguredImg2SnapshotIdentityWithoutRenamingItToTheAlias() {
        var snapshot = config("org", "IMAGE", "gpt-image-2-2026-04-21", "snapshot-cipher", false);
        when(mapper.selectOne(any())).thenReturn(snapshot);when(cipher.decrypt("snapshot-cipher")).thenReturn("snapshot-key");
        var result = service.requireTransparentArtworkConnection("org");
        assertThat(result.id()).isEqualTo(snapshot.getId());assertThat(result.modelId()).isEqualTo("gpt-image-2-2026-04-21");
        verify(mapper, never()).selectList(any());
    }

    @Test void bananaDefaultNeverReplacesTheDedicatedPrintModel() {
        var banana = config("org", "IMAGE", "gemini-nano-banana-2.1", "banana-key", true);
        var img2 = config("org", "IMAGE", "gpt-image-2", "print-key", false);
        when(mapper.selectOne(any())).thenReturn(banana);
        when(mapper.selectList(any())).thenReturn(List.of(img2));
        when(cipher.decrypt("print-key")).thenReturn("print-test-only");
        assertThat(service.requireTransparentArtworkConnection("org").id()).isEqualTo(img2.getId());
        verify(cipher, never()).decrypt("banana-key");
    }

    @Test void foreignDisabledWrongTypeFormatAndDifferentModelCandidatesAreNeverDecrypted() {
        var foreign = config("other-org", "IMAGE", "gpt-image-2", "foreign", true);
        var disabled = config("org", "IMAGE", "gpt-image-2", "disabled", false);disabled.changeStatus("DISABLED");
        var wrongType = config("org", "DRIVER", "gpt-image-2", "driver", false);
        var different = config("org", "IMAGE", "gpt-image-2.5-sunburst", "other-model", false);
        var wrongFormat = spy(config("org", "IMAGE", "gpt-image-2", "format", false));
        when(wrongFormat.getApiFormat()).thenReturn("OTHER");
        when(mapper.selectOne(any())).thenReturn(foreign);
        when(mapper.selectList(any())).thenReturn(Arrays.asList(foreign, disabled, wrongType, different, wrongFormat, null));
        assertThat(service.transparentArtworkConnection("org")).isEmpty();verifyNoInteractions(cipher);
    }

    @Test void aBrokenImg2KeyMayUseAnotherConfiguredImg2ButNeverBorrowsCredentials() {
        var broken = config("org", "IMAGE", "gpt-image-2", "broken-cipher", true);
        var blank = config("org", "IMAGE", "gpt-image-2", "blank-cipher", false);
        var usable = config("org", "IMAGE", "gpt-image-2-2026-04-21", "usable-cipher", false);
        when(mapper.selectOne(any())).thenReturn(broken);when(mapper.selectList(any())).thenReturn(List.of(broken,blank,usable));
        when(cipher.decrypt("broken-cipher")).thenThrow(new IllegalStateException("private-secret-detail"));
        when(cipher.decrypt("blank-cipher")).thenReturn("  ");when(cipher.decrypt("usable-cipher")).thenReturn("own-usable-key");
        var result = service.requireTransparentArtworkConnection("org");
        assertThat(result.id()).isEqualTo(usable.getId());assertThat(result.apiKey()).isEqualTo("own-usable-key");
        verify(cipher,times(1)).decrypt("broken-cipher");
    }

    @Test void absentImg2ConfigurationHasExplicitSafeReasonAndDoesNotManufactureARoute() {
        when(mapper.selectOne(any())).thenReturn(config("org", "IMAGE", "grok-imagine-image-2.0", "grok-cipher", true));
        when(mapper.selectList(any())).thenReturn(List.of());
        assertThatThrownBy(() -> service.requireTransparentArtworkConnection("org"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("没有可用的 IMG2.0")
                .hasMessageContaining("不会改用其他模型").hasMessageNotContaining("grok-cipher");
        verifyNoInteractions(cipher);
    }

    @Test void missingTenantNeverQueriesOrDecryptsAnyCompanyConfiguration() {
        for(String organization : Arrays.asList(null,"","  "))
            assertThatThrownBy(() -> service.transparentArtworkConnection(organization)).isInstanceOf(BusinessException.class).hasMessageContaining("公司信息");
        verifyNoInteractions(mapper,cipher);
    }

    @Test void submissionGuardAcceptsOnlyConfiguredImg2AndRejectsAttachedFallbackRoutes() {
        for(String model : List.of("gpt-image-2","gpt-image-2-2026-04-21")) {
            assertThat(ModelConfigService.isImg2ArtworkModel(model)).isTrue();
            ModelConfigService.requireImg2ArtworkModel(connection(model));
        }
        for(String model : Arrays.asList(null,"gpt-image-1.5","gpt-image-20","gpt-image-2.5-sunburst","grok-imagine-image-2.0","gpt-image-2-custom")) {
            assertThat(ModelConfigService.isImg2ArtworkModel(model)).isFalse();
            assertThatThrownBy(() -> ModelConfigService.requireImg2ArtworkModel(connection(model))).isInstanceOf(BusinessException.class);
        }
        var withBackup = new ModelConfigService.RuntimeModelConnection("configured-id",BASE,"gpt-image-2","OPENAI_COMPATIBLE","AUTO","configured-key",List.of(connection("grok-imagine-image-2.0")));
        assertThatThrownBy(() -> ModelConfigService.requireImg2ArtworkModel(withBackup)).isInstanceOf(BusinessException.class).hasMessageContaining("备用路由");
        assertThatThrownBy(() -> ModelConfigService.requireImg2ArtworkModel(null)).isInstanceOf(BusinessException.class);
    }

    private static ModelConfigService.RuntimeModelConnection connection(String model) {
        return new ModelConfigService.RuntimeModelConnection("configured-id",BASE,model,"OPENAI_COMPATIBLE","AUTO","configured-key");
    }
    private static ModelConfig config(String org,String type,String model,String cipher,boolean selected) {
        return new ModelConfig(org,type,"AUTO",BASE,model,cipher,"unit",selected);
    }
}
