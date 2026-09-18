package com.yingdao.aicommerce.video.application;
import com.yingdao.aicommerce.storage.OssStorageService;
import com.yingdao.aicommerce.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.zip.ZipFile;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class AiVideoArchiveFallbackTest {
 @TempDir Path root;
 @SuppressWarnings("unchecked")
 AiVideoTaskService service(OssStorageService oss){
  ObjectProvider<OssStorageService> provider=mock(ObjectProvider.class);when(provider.getIfAvailable()).thenReturn(oss);
  return new AiVideoTaskService(null,null,null,null,null,null,null,new ObjectMapper(),null,null,null,provider,root.toString());
 }
 @Test void streamsCompleteLocalArchiveWhenOssUploadIsDisabled() throws Exception {
  var oss=mock(OssStorageService.class);
  when(oss.store(any(InputStream.class),anyLong(),anyLong(),anyString(),anyString(),anyString())).thenThrow(new IllegalStateException("UserDisable"));
  byte[] bytes={1,2,3,4};Path video=Files.write(root.resolve("video.mp4"),bytes);
  var batch=new AiVideoTaskService.VideoBatch("org","task","name",List.of(new AiVideoTaskService.VideoBatchFile(video,null,"video.mp4",4)));
  var service=service(oss);var result=service.prepareVideoBatch(batch);
  assertNull(result.location());assertTrue(Files.isRegularFile(result.path()));
  try(var zip=new ZipFile(result.path().toFile())){assertArrayEquals(bytes,zip.getInputStream(zip.getEntry("video.mp4")).readAllBytes());}
  verify(oss,times(1)).store(any(InputStream.class),anyLong(),anyLong(),anyString(),anyString(),anyString());
  service.deletePreparedVideoBatch(result);assertFalse(Files.exists(result.path()));
 }
 @Test void incompleteSourceIsNeverReturnedAsSuccessfulArchive() throws Exception {
  var oss=mock(OssStorageService.class);Path video=Files.write(root.resolve("short.mp4"),new byte[]{1});
  var batch=new AiVideoTaskService.VideoBatch("org","task","name",List.of(new AiVideoTaskService.VideoBatchFile(video,null,"video.mp4",4)));
  assertThrows(BusinessException.class,()->service(oss).prepareVideoBatch(batch));verifyNoInteractions(oss);
  try(var files=Files.list(root.resolve("_download-archives"))){assertEquals(0,files.count());}
 }
}
