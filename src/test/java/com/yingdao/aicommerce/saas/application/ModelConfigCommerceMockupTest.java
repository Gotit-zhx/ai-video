package com.yingdao.aicommerce.saas.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.saas.domain.ModelConfig;
import com.yingdao.aicommerce.saas.mapper.ModelConfigMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ModelConfigCommerceMockupTest {
    final ModelConfigMapper mapper=mock(ModelConfigMapper.class);
    final ModelSecretCipher cipher=mock(ModelSecretCipher.class);
    final ModelConfigService service=new ModelConfigService(mock(TenantContext.class),mapper,cipher,mock(OrganizationAccessPolicy.class));
    final PlatformPrincipal user=new PlatformPrincipal("u","org","org","u","u","",true,List.of(),List.of("product:publish"));
    static final String GROK="grok-imagine-image-2.0";

    @BeforeAll static void metadata(){
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(),"commerce-mockup-test"),ModelConfig.class);
    }
    ModelConfig config(String org,String model,String key,boolean defaultModel){
        return new ModelConfig(org,"IMAGE","AUTO","https://images.example.invalid/v1",model,key,"unit",defaultModel);
    }

    @Test @SuppressWarnings({"rawtypes","unchecked"})
    void configuredGrokIsPreferredWithoutChangingGlobalDefaultOrBorrowingItsKey(){
        var grok=config("org",GROK,"grok-key",false);
        when(mapper.selectList(any())).thenReturn(List.of(grok));when(cipher.decrypt("grok-key")).thenReturn("grok-unit-only");
        var result=service.selectCommerceMockupRuntimeConnection(user).orElseThrow();
        assertThat(result.id()).isEqualTo(grok.getId());assertThat(result.modelId()).isEqualTo(GROK);
        assertThat(result.apiKey()).isEqualTo("grok-unit-only");assertThat(result.copyrightFallbackModels()).isEmpty();
        assertThat(grok.isDefaultModel()).isFalse();
        ArgumentCaptor<LambdaQueryWrapper<ModelConfig>> query=ArgumentCaptor.forClass((Class)LambdaQueryWrapper.class);verify(mapper).selectList(query.capture());
        var selected=query.getValue();
        assertThat(selected.getSqlSegment()).contains("organization_id","model_type","status","model_id","ORDER BY");
        assertThat(selected.getParamNameValuePairs()).containsValue("org").containsValue("IMAGE").containsValue("ACTIVE").containsValue(GROK);
        verifyNoMoreInteractions(mapper);
    }

    @Test void missingGrokUsesTheActualDefaultWithoutAttachingAnAdditionalPostRoute(){
        var primary=config("org","gpt-image-2","default-key",true);
        when(mapper.selectList(any())).thenReturn(List.of());when(mapper.selectOne(any())).thenReturn(primary);
        when(cipher.decrypt("default-key")).thenReturn("default-unit-only");
        var result=service.selectCommerceMockupRuntimeConnection(user).orElseThrow();
        assertThat(result.id()).isEqualTo(primary.getId());assertThat(result.modelId()).isEqualTo("gpt-image-2");
        assertThat(result.copyrightFallbackModels()).isEmpty();assertThat(primary.isDefaultModel()).isTrue();
        verify(mapper).selectList(any());verify(mapper).selectOne(any());verifyNoMoreInteractions(mapper);
    }

    @Test void foreignDisabledUnsupportedOrUndecryptableGrokCannotBeUsed(){
        var foreign=config("foreign",GROK,"foreign-key",false);
        var disabled=config("org",GROK,"disabled-key",false);disabled.changeStatus("DISABLED");
        var unsupported=spy(config("org",GROK,"wrong-format-key",false));when(unsupported.getApiFormat()).thenReturn("OTHER");
        var broken=config("org",GROK,"broken-key",false);var blank=config("org",GROK,"blank-key",false);
        var primary=config("org","gpt-image-2","default-key",true);
        when(mapper.selectList(any())).thenReturn(List.of(foreign,disabled,unsupported,broken,blank));
        when(mapper.selectOne(any())).thenReturn(primary);
        when(cipher.decrypt("broken-key")).thenThrow(new IllegalStateException("unit key unavailable"));
        when(cipher.decrypt("blank-key")).thenReturn(" ");when(cipher.decrypt("default-key")).thenReturn("default-unit-only");
        assertThat(service.selectCommerceMockupRuntimeConnection(user).orElseThrow().id()).isEqualTo(primary.getId());
        verify(cipher,never()).decrypt("foreign-key");verify(cipher,never()).decrypt("disabled-key");verify(cipher,never()).decrypt("wrong-format-key");
    }

    @Test @SuppressWarnings({"rawtypes","unchecked"})
    void frozenJobResolvesItsOriginalImageConnectionWithoutConsultingPreferredModels(){
        var fixed=config("org","gpt-image-2","fixed-key",false);
        when(mapper.selectOne(any())).thenReturn(fixed);when(cipher.decrypt("fixed-key")).thenReturn("fixed-unit-only");
        var result=service.commerceMockupConnectionById("org",fixed.getId()).orElseThrow();
        assertThat(result.id()).isEqualTo(fixed.getId());assertThat(result.modelId()).isEqualTo("gpt-image-2");
        assertThat(result.copyrightFallbackModels()).isEmpty();
        ArgumentCaptor<LambdaQueryWrapper<ModelConfig>> query=ArgumentCaptor.forClass((Class)LambdaQueryWrapper.class);verify(mapper).selectOne(query.capture());
        var selected=query.getValue();selected.getSqlSegment();
        assertThat(selected.getParamNameValuePairs()).containsValue("org").containsValue(fixed.getId()).containsValue("ACTIVE").containsValue("IMAGE");
        verifyNoMoreInteractions(mapper);
    }

    @Test void frozenMissingDisabledOrForeignConnectionNeverReselectsAnotherModel(){
        var disabled=config("org",GROK,"disabled-key",false);disabled.changeStatus("DISABLED");
        var foreign=config("foreign",GROK,"foreign-key",false);
        for(var invalid:List.of(disabled,foreign)){
            when(mapper.selectOne(any())).thenReturn(invalid);
            assertThat(service.commerceMockupConnectionById("org",invalid.getId())).isEmpty();
        }
        when(mapper.selectOne(any())).thenReturn(null);
        assertThat(service.commerceMockupConnectionById("org","missing")).isEmpty();
        verify(mapper,never()).selectList(any());verifyNoInteractions(cipher);
    }

    @Test void missingOrganizationCannotSelectGlobalOrOtherTenantModels(){
        assertThatThrownBy(()->service.selectCommerceMockupRuntimeConnection(null)).isInstanceOf(BusinessException.class);
        assertThat(service.commerceMockupConnectionById(null,"id")).isEmpty();
        verifyNoInteractions(mapper,cipher);
    }
}
