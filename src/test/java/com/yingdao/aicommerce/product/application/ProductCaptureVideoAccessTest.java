package com.yingdao.aicommerce.product.application;
import com.yingdao.aicommerce.product.web.ProductCaptureVideoController;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class ProductCaptureVideoAccessTest {
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
}
