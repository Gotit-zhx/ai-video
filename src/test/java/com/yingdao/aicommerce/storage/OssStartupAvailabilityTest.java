package com.yingdao.aicommerce.storage;
import com.aliyun.oss.OSS;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
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
  assertDoesNotThrow(service::verifyAccess);
 }
}
