package com.yingdao.aicommerce.storage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import com.aliyun.oss.OSSClient;
import com.aliyun.oss.common.comm.SignVersion;
import java.net.URI;
import java.time.Duration;
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
 @Test void dayanUsesCanonicalEndpointAndBucketPathForRequestsAndSignedDownloads() throws Exception {
  Path credentials=Files.writeString(root.resolve("dayan-credentials"),"accessKeyId=test-id\naccessKeySecret=test-secret\n");
  for(String endpoint:new String[]{"https://oss.cloud.dayan.design","https://oss.cloud.dayan.cloud"}) {
   var storage=new OssStorageService("test-hoyee","cn-chengdu",endpoint,endpoint,"",
           Duration.ofMinutes(5),false,null,credentials.toString(),"","");
   try {
    var client=(OSSClient)ReflectionTestUtils.getField(storage,"internalClient");
    assertEquals("oss.cloud.dayan.cloud",client.getEndpoint().getHost());
    assertTrue(client.getClientConfiguration().isSLDEnabled());
    assertEquals(SignVersion.V1,client.getClientConfiguration().getSignatureVersion());
    URI signed=URI.create(storage.signedImagePreviewUrl("oss://test-hoyee/heyi/source%20image.png"));
    assertEquals("oss.cloud.dayan.cloud",signed.getHost());
    assertEquals("/test-hoyee/heyi/source image.png",signed.getPath());
    assertFalse(signed.getQuery().contains("x-oss-process"));
    assertTrue(signed.getQuery().contains("Signature="));
   } finally {storage.close();}
  }
 }
 @Test void aliyunKeepsItsExistingEndpointAndVirtualHostSigning() throws Exception {
  Path credentials=Files.writeString(root.resolve("aliyun-credentials"),"accessKeyId=test-id\naccessKeySecret=test-secret\n");
  String endpoint="https://oss-cn-guangzhou.aliyuncs.com";
  var storage=new OssStorageService("old-bucket","cn-guangzhou",endpoint,endpoint,"",
          Duration.ofMinutes(5),false,null,credentials.toString(),"test-hoyee",credentials.toString());
  try {
   var legacy=ReflectionTestUtils.getField(storage,"legacyStorage");
   var client=(OSSClient)ReflectionTestUtils.getField(legacy,"internalClient");
   assertFalse(client.getClientConfiguration().isSLDEnabled());
   assertEquals(SignVersion.V4,client.getClientConfiguration().getSignatureVersion());
   URI signed=URI.create(storage.signedUrl("oss://old-bucket/heyi/image.png"));
   assertEquals("old-bucket.oss-cn-guangzhou.aliyuncs.com",signed.getHost());
   assertEquals("/heyi/image.png",signed.getPath());
   URI current=URI.create(storage.signedUrl("oss://test-hoyee/heyi/image.png"));
   assertEquals("oss.cloud.dayan.cloud",current.getHost());
   assertEquals("/test-hoyee/heyi/image.png",current.getPath());
  } finally {storage.close();}
 }
}
