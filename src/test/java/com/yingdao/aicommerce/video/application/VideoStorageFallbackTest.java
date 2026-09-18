package com.yingdao.aicommerce.video.application;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import com.yingdao.aicommerce.storage.OssStorageService;
import com.yingdao.aicommerce.video.domain.*;
import com.yingdao.aicommerce.video.mapper.*;
import com.yingdao.aicommerce.video.application.provider.*;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.io.InputStream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class VideoStorageFallbackTest {
 @TempDir Path root;
 private final OssStorageService oss=mock(OssStorageService.class);
 private final VideoReferenceLinkService links=mock(VideoReferenceLinkService.class);
 private final VideoReferenceDeliveryService delivery=mock(VideoReferenceDeliveryService.class);
 private final AiVideoTaskItemMapper items=mock(AiVideoTaskItemMapper.class);
 private AiVideoGenerationService service() {
  ObjectProvider<OssStorageService> provider=mock(ObjectProvider.class);when(provider.getIfAvailable()).thenReturn(oss);
  return new AiVideoGenerationService(null,items,null,null,null,null,null,links,delivery,provider,new ObjectMapper(),Runnable::run,1);
 }
 @Test void failedOssImagePublicationUsesPreparedSignedLocalReference() throws Exception {
  Path file=Files.write(root.resolve("image.jpg"),new byte[]{1,2,3});
  var asset=new AiVideoTaskAsset("org","task","item","VIDEO_REFERENCE_IMAGE",1,file.toString(),"image/png",3,"","");
  when(delivery.prepare(asset)).thenReturn(new VideoReferenceDeliveryService.Delivery(file,3,"image/jpeg"));
  when(oss.publish(file,"image/jpeg","video-references")).thenThrow(new IllegalStateException("UserDisable"));
  when(links.isPubliclyReachable()).thenReturn(true);when(links.deliveryAsset(asset.getId())).thenReturn("https://example.test/signed-image");
  VideoGenerationProvider.Reference ref=ReflectionTestUtils.invokeMethod(service(),"ossReference",asset,"video-references");
  assertThat(ref.url()).isEqualTo("https://example.test/signed-image");assertThat(ref.mediaType()).isEqualTo("image/jpeg");
  when(links.isPubliclyReachable()).thenReturn(false);
  assertThatThrownBy(()->ReflectionTestUtils.invokeMethod(service(),"ossReference",asset,"video-references")).hasMessageContaining("UserDisable");
 }
 @Test void completedVideoNeverUploadsToOss() throws Exception {
  byte[] bytes=new byte[]{0,1,2,3,4,5};
  var http=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
  http.createContext("/video",exchange->{exchange.sendResponseHeaders(200,bytes.length);try(var out=exchange.getResponseBody()){out.write(bytes);}});http.start();
  try {
   var item=mock(AiVideoTaskItem.class);when(item.getSourceFilePath()).thenReturn(root.resolve("source.jpg").toString());when(items.selectById("item")).thenReturn(item);
   var job=mock(AiVideoGenerationJob.class);when(job.getId()).thenReturn("job");when(job.getItemId()).thenReturn("item");when(job.getOrganizationId()).thenReturn("org");when(job.getTaskId()).thenReturn("task");
   Object result=ReflectionTestUtils.invokeMethod(service(),"download",job,null,mock(VideoGenerationProvider.class),"http://127.0.0.1:"+http.getAddress().getPort()+"/video");
   assertThat(result).isNotNull();assertThat(Files.readAllBytes(root.resolve("video-job.mp4"))).isEqualTo(bytes);
   assertThat(root.resolve("video-job.part")).doesNotExist();verifyNoInteractions(oss);
  } finally {http.stop(0);}
 }
 @Test void audioFallbackPreservesValidLinkSignature() throws Exception {
  Path audio=Files.write(root.resolve("transcription-audio.mp3"),new byte[300]);
  var script=mock(VideoScript.class);when(script.getSourceFilePath()).thenReturn(root.resolve("source.mp4").toString());when(script.getId()).thenReturn("script");
  when(oss.publish(audio,"audio/mpeg","script-transcription-inputs")).thenThrow(new IllegalStateException("UserDisable"));
  ObjectProvider<OssStorageService> provider=mock(ObjectProvider.class);when(provider.getIfAvailable()).thenReturn(oss);
  var signed=new VideoReferenceLinkService("https://example.test",false,"test-secret",java.time.Duration.ofHours(1));
  var audioService=new DayanAudioTranscriptionService(null,provider,signed,new ObjectMapper());
  String url=ReflectionTestUtils.invokeMethod(audioService,"sourceUrl",script);
  var query=java.net.URI.create(url).getQuery();
  var params=java.util.Arrays.stream(query.split("&")).map(value->value.split("=",2)).collect(java.util.stream.Collectors.toMap(value->value[0],value->value[1]));
  assertThat(signed.isValid("script-audio","script",Long.parseLong(params.get("expires")),params.get("signature"))).isTrue();
  assertThat(params).containsKey("attempt");
 }

}
