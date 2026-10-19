package com.yingdao.aicommerce.saas.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.yingdao.aicommerce.saas.domain.ModelConfig;
import com.yingdao.aicommerce.saas.mapper.ModelConfigMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ModelConfigWatermarkTest {
    final ModelConfigMapper mapper=mock(ModelConfigMapper.class);
    final ModelSecretCipher cipher=mock(ModelSecretCipher.class);
    final ModelConfigService service=new ModelConfigService(mock(TenantContext.class),mapper,cipher,mock(OrganizationAccessPolicy.class));
    @BeforeAll static void metadata(){
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(),"watermark-test"),ModelConfig.class);
    }
    ModelConfig config(String org,String type,String model,String key){
        return new ModelConfig(org,type,"AUTO","https://unit.invalid/v1",model,key,"unit",false);
    }
    @Test void eachGrokRouteUsesItsOwnKeyWithoutBorrowingTheDefault(){
        var image=config("org","IMAGE","grok-imagine-image-2.0","image-key");
        var driver=config("org","DRIVER","grok-4.6","driver-key");
        when(mapper.selectList(any())).thenReturn(List.of(image),List.of(driver));
        when(cipher.decrypt("image-key")).thenReturn("image-unit");
        when(cipher.decrypt("driver-key")).thenReturn("driver-unit");
        assertThat(service.watermarkImageConnection("org").orElseThrow().apiKey()).isEqualTo("image-unit");
        assertThat(service.watermarkDriverConnection("org").orElseThrow().apiKey()).isEqualTo("driver-unit");
        verify(mapper,never()).selectOne(any());
    }
    @Test void foreignWrongTypeDisabledGptAndBrokenKeysCannotBecomeWatermarkRoutes(){
        var disabled=config("org","IMAGE","grok-imagine-image-2.0","disabled");disabled.changeStatus("DISABLED");
        when(mapper.selectList(any())).thenReturn(List.of(
                config("foreign","IMAGE","grok-imagine-image-2.0","foreign"),
                config("org","DRIVER","grok-imagine-image-2.0","wrong-type"),disabled,
                config("org","IMAGE","gpt-image-2","gpt"),
                config("org","IMAGE","grok-imagine-image-2.0","broken"),
                config("org","IMAGE","grok-imagine-image-2.0","blank")));
        when(cipher.decrypt("broken")).thenThrow(new IllegalStateException("unit-only"));
        when(cipher.decrypt("blank")).thenReturn("");
        assertThat(service.watermarkImageConnection("org")).isEmpty();
        verify(cipher,never()).decrypt("foreign");verify(cipher,never()).decrypt("wrong-type");
        verify(cipher,never()).decrypt("disabled");verify(cipher,never()).decrypt("gpt");
        verify(mapper,never()).selectOne(any());
    }
    @Test void missingOrganizationNeverSelectsAnotherTenant(){
        assertThat(service.watermarkImageConnection(null)).isEmpty();
        assertThat(service.watermarkDriverConnection(" ")).isEmpty();
        verifyNoInteractions(mapper,cipher);
    }
    @Test void bananaCannotReplaceTheDedicatedWatermarkModel(){
        var banana=new ModelConfig("org","IMAGE","AUTO","https://unit.invalid","gemini-nano-banana-2.1","banana","unit",true);
        var grok=config("org","IMAGE","grok-imagine-image-2.0","grok");
        when(mapper.selectList(any())).thenReturn(List.of(banana,grok));
        when(cipher.decrypt("grok")).thenReturn("grok-test-key");
        assertThat(service.watermarkImageConnection("org").orElseThrow().id()).isEqualTo(grok.getId());
        verify(cipher,never()).decrypt("banana");
        verify(mapper,never()).selectOne(any());
    }
}
