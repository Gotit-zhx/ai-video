package com.yingdao.aicommerce.video.application;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import com.yingdao.aicommerce.storage.OssStorageService;
import com.yingdao.aicommerce.video.domain.*;
import com.yingdao.aicommerce.video.mapper.AiVideoTaskAssetMapper;
import com.yingdao.aicommerce.video.mapper.AiVideoGenerationJobMapper;
import com.yingdao.aicommerce.video.mapper.AiVideoTaskItemMapper;
import com.yingdao.aicommerce.video.mapper.AiVideoTaskMapper;
import com.yingdao.aicommerce.video.application.provider.*;
import com.yingdao.aicommerce.saas.application.ModelConfigService;
import com.yingdao.aicommerce.saas.application.ModelConfigService.RuntimeModelConnection;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class GeneralVideoReferenceDeliveryTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @SuppressWarnings("unchecked")
    void submitsTextOnlyTaskWithOriginalPromptAndNoReferenceDelivery(boolean chosenSettings) {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"),
                AiVideoGenerationJob.class);
        var tasks = mock(AiVideoTaskMapper.class);
        var items = mock(AiVideoTaskItemMapper.class);
        var assets = mock(AiVideoTaskAssetMapper.class);
        var jobs = mock(AiVideoGenerationJobMapper.class);
        var configs = mock(ModelConfigService.class);
        var providers = mock(VideoProviderRegistry.class);
        var adapter = mock(VideoGenerationProvider.class);
        var links = mock(VideoReferenceLinkService.class);
        var delivery = mock(VideoReferenceDeliveryService.class);
        ObjectProvider<OssStorageService> oss = mock(ObjectProvider.class);
        var service = new AiVideoGenerationService(tasks, items, assets, jobs, null, configs,
                providers, links, delivery, oss, new ObjectMapper(), Runnable::run, 1);
        var model = new RuntimeModelConnection("config", "https://example.test", DayanVideoGenerationProvider.MODEL,
                "OPENAI", "DAYAN", "key");
        String standard = "doubao-seedance-2-0-260128";
        var selectedRoute = chosenSettings
                ? new RuntimeModelConnection(model.id(), model.baseUrl(), standard, model.apiFormat(), model.provider(), model.apiKey())
                : model;
        var task = AiVideoTask.general("org", "日出", "{}", 12, "21:9", "1080p",
                chosenSettings ? "{\"videoModelId\":\"" + standard + "\"}" : "{}", "{}", "user");
        task.updateItemCount(1);
        var item = new AiVideoTaskItem("org", task.getId(), task.getName(), "REF-001", "", "", "", 0);
        String prompt = "  日出映在山间湖面上  ";
        item.updateVideoPrompt(prompt);

        when(tasks.selectById(task.getId())).thenReturn(task);
        when(items.selectById(item.getId())).thenReturn(item);
        when(items.selectList(any())).thenReturn(List.of(item));
        when(configs.videoConnections("org")).thenReturn(List.of(model));
        when(configs.videoConnections("other-org")).thenReturn(List.of(new RuntimeModelConnection("other-route",
                "https://example.test", DayanVideoGenerationProvider.MODEL, "OPENAI", "DAYAN", "other-key")));
        when(providers.require("DAYAN")).thenReturn(adapter);
        when(adapter.availableModels(model)).thenReturn(Set.of(DayanVideoGenerationProvider.MODEL, standard));
        when(adapter.availableModels(argThat(route -> route != null && "other-route".equals(route.id()))))
                .thenReturn(Set.of(DayanVideoGenerationProvider.MODEL));
        when(adapter.effectiveResolution("1080p")).thenReturn("720p");
        when(adapter.submit(eq(selectedRoute), any())).thenReturn(
                new VideoGenerationProvider.SubmitResult("provider-task", "QUEUED", "{}", "{}"));

        assertEquals(List.of(DayanVideoGenerationProvider.MODEL, standard),
                service.generalVideoOptions("org").models().stream().map(GeneralVideoModel::id).toList());
        assertEquals(1, service.generalVideoOptions("other-org").models().size());
        assertThrows(com.yingdao.aicommerce.common.exception.BusinessException.class,
                () -> service.requireGeneralVideoModel("other-org", standard));
        service.start(task);

        var request = ArgumentCaptor.forClass(VideoGenerationProvider.GenerationRequest.class);
        verify(adapter).submit(eq(selectedRoute), request.capture());
        assertEquals(prompt, request.getValue().prompt());
        assertEquals(List.of(), request.getValue().references());
        assertNull(request.getValue().audioUrl());
        assertEquals(chosenSettings, request.getValue().useRequestedOptions());
        assertEquals(chosenSettings ? "1080p" : "720p", request.getValue().resolution());
        assertEquals(chosenSettings ? "21:9" : "adaptive", request.getValue().aspectRatio());
        assertEquals(chosenSettings ? 12 : -1, request.getValue().durationSeconds());
        var job = ArgumentCaptor.forClass(AiVideoGenerationJob.class);
        verify(jobs).insert(job.capture());
        assertEquals(selectedRoute.modelId(), job.getValue().getModelId());
        assertEquals(request.getValue().resolution(), job.getValue().getResolution());
        assertEquals(request.getValue().durationSeconds(), job.getValue().getDurationSeconds());
        assertEquals(request.getValue().aspectRatio(), job.getValue().getAspectRatio());
        assertEquals("VIDEO_GENERATING", item.getStatus());
        verifyNoInteractions(links, delivery);
    }

 @Test void videoReferenceBypassesImageConversionAndKeepsVideoMime() throws Exception {
  var assets=mock(AiVideoTaskAssetMapper.class);
  var delivery=mock(VideoReferenceDeliveryService.class);
  var oss=mock(OssStorageService.class);
  ObjectProvider<OssStorageService> provider=mock(ObjectProvider.class);when(provider.getIfAvailable()).thenReturn(oss);
  var service=new AiVideoGenerationService(null,null,assets,null,null,null,null,null,delivery,provider,new ObjectMapper(),Runnable::run,1);
  var task=AiVideoTask.general("org","test","{}",5,"9:16","720p","{}","{}","user");
  var item=mock(AiVideoTaskItem.class);when(item.getId()).thenReturn("item");
  var video=new AiVideoTaskAsset("org",task.getId(),"item","VIDEO_REFERENCE_VIDEO",10,"/data/ref.mp4","video/mp4",10,"@视频1","");
  when(assets.selectList(any())).thenReturn(List.of(video));
  when(oss.publish(Path.of("/data/ref.mp4"),"video/mp4","video-references")).thenReturn("https://example.test/ref.mp4");
  var model=new RuntimeModelConnection("test","https://example.test","test","OPENAI","DAYAN","key");
  var adapter=new DayanVideoGenerationProvider(new ObjectMapper());
  List<VideoGenerationProvider.Reference> result=ReflectionTestUtils.invokeMethod(service,"references",task,item,model,adapter);
  assertEquals(1,result.size());assertEquals("video/mp4",result.getFirst().mediaType());
  verifyNoInteractions(delivery);
  verify(oss).publish(Path.of("/data/ref.mp4"),"video/mp4","video-references");
 }
}
