package com.yingdao.aicommerce.storage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class DayanOssRoutingTest {
 @TempDir Path root;
 @Test void staticCredentialsDoNotRequireEcsMetadata() throws Exception {
  Path p=Files.writeString(root.resolve("credentials"),"accessKeyId=test-id\naccessKeySecret=test-secret\n");
  var value=OssStorageService.credentialsProvider("",p.toString()).getCredentials();
  assertEquals("test-id",value.getAccessKeyId());assertEquals("test-secret",value.getSecretAccessKey());assertNull(value.getSecurityToken());
 }
 @Test void missingStaticKeyFailsWithoutPrintingSecret() throws Exception {
  Path p=Files.writeString(root.resolve("credentials"),"accessKeySecret=hidden-value\n");
  var error=assertThrows(IllegalArgumentException.class,()->OssStorageService.credentialsProvider("",p.toString()));
  assertFalse(error.getMessage().contains("hidden-value"));
 }
 @Test void oldBucketReadsRemainOnLegacyStorage() throws Exception {
  var legacy=mock(OssStorageService.class);var active=mock(OssStorageService.class,CALLS_REAL_METHODS);
  ReflectionTestUtils.setField(active,"bucket","test-hoyee");ReflectionTestUtils.setField(active,"legacyStorage",legacy);
  String old="oss://original/heyi/image.jpg";
  when(legacy.isOssLocation(old)).thenReturn(true);when(legacy.signedUrl(old)).thenReturn("https://legacy/image");
  assertTrue(active.isOssLocation(old));assertTrue(active.isOssLocation("oss://test-hoyee/heyi/new.jpg"));
  assertEquals("https://legacy/image",active.signedUrl(old));
  active.open(old);verify(legacy).open(old);
 }
 @Test void dayanPreviewOmitsUnsupportedImageProcessing() {
  var active=mock(OssStorageService.class,CALLS_REAL_METHODS);ReflectionTestUtils.setField(active,"disableImageProcessing",true);
  doReturn("https://dayan/plain").when(active).signedUrl("oss://test-hoyee/heyi/image.jpg");
  assertEquals("https://dayan/plain",active.signedImagePreviewUrl("oss://test-hoyee/heyi/image.jpg"));
 }
}
