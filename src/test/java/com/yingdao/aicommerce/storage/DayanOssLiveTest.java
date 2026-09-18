package com.yingdao.aicommerce.storage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.time.Duration;
import java.net.URI;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;
@EnabledIfEnvironmentVariable(named="DAYAN_LIVE_TEST",matches="true")
class DayanOssLiveTest {
 @Test void sdkUploadReadSignAndMultipart() throws Exception {
  var storage=new OssStorageService("test-hoyee","cn-chengdu","https://oss.cloud.dayan.design","https://oss.cloud.dayan.design","",Duration.ofMinutes(5),false,null,"/run/dayan.properties","","");
  try {
   for(int size:("true".equals(System.getenv("DAYAN_LIVE_MULTIPART")) ? new int[]{31,9*1024*1024} : new int[]{31})) {
    byte[] content=new byte[size];Arrays.fill(content,(byte)42);
    System.out.println("Live OSS upload bytes="+size);
    var stored=storage.store(new java.io.ByteArrayInputStream(content),size,size,"application/octet-stream","_health/java-integration","test.bin");
    try {
     System.out.println("Live OSS readback bytes="+size);
     assertArrayEquals(content,storage.readAllBytes(stored.location(),size));
     System.out.println("Live OSS signed download bytes="+size);
     var connection=URI.create(storage.signedUrl(stored.location())).toURL().openConnection();
     connection.setConnectTimeout(10_000);connection.setReadTimeout(30_000);
     try(var in=connection.getInputStream()){assertArrayEquals(content,in.readAllBytes());}
    } finally {storage.delete(stored.location());}
   }
  } finally {storage.close();}
 }
}
