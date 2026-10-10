package com.yingdao.aicommerce.video.application;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import com.yingdao.aicommerce.storage.OssStorageService;
import com.yingdao.aicommerce.video.domain.*;
import com.yingdao.aicommerce.video.mapper.AiVideoTaskAssetMapper;
import com.yingdao.aicommerce.video.application.provider.*;
import com.yingdao.aicommerce.saas.application.ModelConfigService.RuntimeModelConnection;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class GeneralVideoReferenceDeliveryTest {
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
