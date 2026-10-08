package com.yingdao.aicommerce.storage;
import com.aliyun.oss.OSS;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class OssStartupAvailabilityTest {
 @Test void storageOutageDoesNotPreventApplicationStartup() {
  OSS client=mock(OSS.class);
  when(client.putObject(anyString(),anyString(),any(java.io.InputStream.class),any(com.aliyun.oss.model.ObjectMetadata.class)))
    .thenThrow(new com.aliyun.oss.OSSException("UserDisable"));
  OssStorageService service=mock(OssStorageService.class,CALLS_REAL_METHODS);
  ReflectionTestUtils.setField(service,"bucket","test");ReflectionTestUtils.setField(service,"internalClient",client);
  assertDoesNotThrow(service::verifyStorageAccess);
 }
 @Test void aBlockedRemoteProbeDoesNotBlockApplicationInitialization() throws Exception {
  OssStorageService service=mock(OssStorageService.class,CALLS_REAL_METHODS);
  OSS client=mock(OSS.class);
  ReflectionTestUtils.setField(service,"internalClient",client);
  ReflectionTestUtils.setField(service,"publicClient",client);
  CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1), finished=new CountDownLatch(1);
  doAnswer(invocation -> {
   entered.countDown();
   try { release.await(); } finally { finished.countDown(); }
   return null;
  }).when(service).verifyStorageAccess();
  CompletableFuture<Void> initialization=CompletableFuture.runAsync(service::verifyAccess);
  try {
   assertTrue(entered.await(3,TimeUnit.SECONDS));
   initialization.get(3,TimeUnit.SECONDS);
   assertEquals(1,finished.getCount(),"Startup returns while the remote probe is still blocked");
  } finally {
   release.countDown();
   assertTrue(finished.await(3,TimeUnit.SECONDS));
   service.close();
  }
 }
}
