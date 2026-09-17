package com.yingdao.aicommerce.product.application;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.product.domain.*;
import com.yingdao.aicommerce.shop.infrastructure.MarketplaceImageDownloader;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class CommercePreparedSourceTest {
 @TempDir Path directory;
 final JdbcTemplate db=mock(JdbcTemplate.class);
 final MarketplaceImageDownloader downloader=mock(MarketplaceImageDownloader.class);
 final CommerceCaptureSourceArchiveService cached=mock(CommerceCaptureSourceArchiveService.class);
 final PlatformPrincipal user=new PlatformPrincipal("u","org","org","u","u","",true,List.of(),List.of());
 final ProductCaptureImage image=new ProductCaptureImage("org","capture","https://example.com/original.png","MAIN","",0,"image/png","{}");
 ProductCaptureService.CaptureDetail detail(){return new ProductCaptureService.CaptureDetail(mock(ProductCapture.class),List.of(),List.of(image),List.of(),Map.of(),Map.of());}
 CommerceBatchCreativePlanValidator.Selection source(){return new CommerceBatchCreativePlanValidator.Selection("capture",1,image.getId());}
 CommerceBatchArtworkService service(CommerceImageArchive archive){return new CommerceBatchArtworkService(db,null,null,null,archive,downloader,null,null,null,null,new ObjectMapper(),cached);}
 @Test void processedArchiveWinsOverOriginalCacheAndNeverDownloadsTheOriginal()throws Exception {
  var archive=new CommerceImageArchive(directory.toString());var stored=archive.store("org",SurfaceTemplateFixture.blank());
  image.processed(ProductWatermarkAssets.PREFIX+"a".repeat(64));
  when(db.queryForList(startsWith("SELECT output_key"),eq("a".repeat(64)),eq("org"),eq("capture"),eq(image.getId()))).thenReturn(List.of(Map.of("output_key",stored.storageKey())));
  assertEquals(stored.storageKey(),service(archive).archiveSource(source(),detail(),user));
  verifyNoInteractions(downloader,cached);
 }
 @Test void missingOrForeignProcessedAssetStopsInsteadOfFallingBackToWatermarkedOriginal(){
  image.processed(ProductWatermarkAssets.PREFIX+"a".repeat(64));
  assertThrows(IllegalArgumentException.class,()->service(new CommerceImageArchive(directory.toString())).archiveSource(source(),detail(),user));
  verifyNoInteractions(downloader,cached);
 }
 @Test void originalArchiveRemainsAvailableWhenThereIsNoProcessedResult(){
  when(cached.find("capture",image.getId(),1,user)).thenReturn("original-key");
  assertEquals("original-key",service(new CommerceImageArchive(directory.toString())).archiveSource(source(),detail(),user));verifyNoInteractions(downloader);
 }
 @Test void foregroundMayChangeAlphaButCannotRepaintFabric()throws Exception {
  var initial=new java.awt.image.BufferedImage(10,10,java.awt.image.BufferedImage.TYPE_INT_ARGB);
  var buffer=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(initial,"png",buffer);byte[] original=buffer.toByteArray();var im=CommerceMockupComposer.decode(original);im.setRGB(0,0,im.getRGB(0,0)&0xffffff);
  var out=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(im,"png",out);
  assertDoesNotThrow(()->CommerceGarmentForeground.validatePixels(original,out.toByteArray()));
  im.setRGB(5,5,0xff0000ff);out.reset();javax.imageio.ImageIO.write(im,"png",out);
  assertThrows(IllegalArgumentException.class,()->CommerceGarmentForeground.validatePixels(original,out.toByteArray()));
 }
 @Test void foregroundHttpUsesBoundedContentLengthAndChecksSourceIdentity()throws Exception {
  var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
  var length=new java.util.concurrent.atomic.AtomicInteger();
  server.createContext("/garment-foreground",exchange->{
   length.set(Integer.parseInt(exchange.getRequestHeaders().getFirst("Content-length")));
   var json=new ObjectMapper();var body=json.readTree(exchange.getRequestBody().readAllBytes());
   byte[] input=Base64.getDecoder().decode(body.path("data").asText());
   byte[] response=json.writeValueAsBytes(Map.of("sourceSha256",CommerceImageArchive.sha(input),"algorithm",CommerceGarmentForeground.VERSION,"data",Base64.getEncoder().encodeToString(input)));
   exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);exchange.close();
  });server.start();
  try {var gateway=new CommerceGarmentForeground("http://127.0.0.1:"+server.getAddress().getPort(),"x".repeat(32));
   assertNotNull(gateway.isolate(SurfaceTemplateFixture.blank()));assertTrue(length.get()>0);
  }finally{server.stop(0);}
 }
}
