package com.yingdao.aicommerce.product.application;
import com.yingdao.aicommerce.product.web.ProductCaptureVideoController;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class ProductCaptureVideoAccessTest {
 @TempDir Path storage;
 final PlatformPrincipal user=new PlatformPrincipal("u","org","code","login","name","",true,List.of(),List.of("product:collect","product:view"));
 @Test void foreignCaptureStopsBeforeDatabaseOrFileAccess(){
  var captures=mock(ProductCaptureService.class);var db=mock(JdbcTemplate.class);var c=new ProductCaptureVideoController(captures,db,"/tmp/unused-video-test");
  when(captures.get("foreign",user)).thenThrow(new IllegalArgumentException("not owned"));
  assertThrows(RuntimeException.class,()->c.list("foreign",user));assertThrows(RuntimeException.class,()->c.file("foreign","../../etc/passwd",user));verifyNoInteractions(db);
 }
 @Test void rejectsNonVideoBytesBeforeStorage(){
  var captures=mock(ProductCaptureService.class);var db=mock(JdbcTemplate.class);var c=new ProductCaptureVideoController(captures,db,"/tmp/unused-video-test");
  assertThrows(RuntimeException.class,()->c.upload("owned","https://example.com/video",new MockMultipartFile("file","evil.mp4","video/mp4","<html>not a video</html>".getBytes()),user));verifyNoInteractions(db);
 }
 @Test void privateCopyReadsFrozenFileWithoutReadingTheOriginalCapture()throws Exception{
  String original=UUID.randomUUID().toString(),copy=UUID.randomUUID().toString();
  Files.createDirectories(storage.resolve("capture-videos"));
  byte[] bytes="immutable video fixture".getBytes();
  Files.write(storage.resolve("capture-videos").resolve(original),bytes);
  var captures=mock(ProductCaptureService.class);var db=mock(JdbcTemplate.class);
  when(db.queryForList(contains("storage_video_id"),eq("org"),eq("private"),eq(copy)))
    .thenReturn(List.of(Map.of("mime_type","video/mp4","storage_video_id",original)));
  var response=new ProductCaptureVideoController(captures,db,storage.toString()).file("private",copy,user);
  assertArrayEquals(bytes,response.getBody().getContentAsByteArray());
  verify(captures).get("private",user);verifyNoMoreInteractions(captures);
 }
}
