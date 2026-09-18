package com.yingdao.aicommerce.storage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class LocalMaterialOssArchiveServiceTest {
 @TempDir Path root;
 @Test void archivesReferenceOnceRetainsWorkingFileAndExcludesFinal() throws Exception {
  Path tasks=Files.createDirectories(root.resolve("ai-video-tasks"));
  Path reference=Files.write(tasks.resolve("reference.mp4"),new byte[]{1,2});
  Path finalVideo=Files.write(tasks.resolve("video-job.mp4"),new byte[]{3,4});
  Files.setLastModifiedTime(reference,FileTime.fromMillis(1));Files.setLastModifiedTime(finalVideo,FileTime.fromMillis(1));
  var oss=mock(OssStorageService.class);
  when(oss.store(any(java.io.InputStream.class),eq(2L),anyLong(),any(),anyString(),eq("reference.mp4"))).thenReturn(new OssStorageService.StoredObject("oss://test/ref",2));
  when(oss.exists("oss://test/ref")).thenReturn(true);when(oss.download("oss://test/ref")).thenReturn(new OssStorageService.Download(null,2,"video/mp4"));
  var service=new LocalMaterialOssArchiveService(oss,root.resolve("scripts").toString(),tasks.toString(),root.resolve("attachments").toString());
  service.archive();service.archive();
  verify(oss,times(1)).store(any(java.io.InputStream.class),anyLong(),anyLong(),any(),anyString(),anyString());
  assertTrue(Files.exists(reference));assertTrue(Files.exists(finalVideo));
  assertFalse(LocalMaterialOssArchiveService.eligible(tasks,finalVideo));
  when(oss.isOssLocation("oss://test/ref")).thenReturn(true);when(oss.signedUrl("oss://test/ref")).thenReturn("https://storage/ref");
  assertEquals("https://storage/ref",service.archivedUrl(reference));
  assertNull(service.archivedUrl(finalVideo));
  Files.write(reference,new byte[]{5,6,7});assertNull(service.archivedUrl(reference));
 }
}
