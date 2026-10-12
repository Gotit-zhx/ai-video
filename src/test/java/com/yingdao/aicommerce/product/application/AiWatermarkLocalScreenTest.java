package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.product.domain.*;
import com.yingdao.aicommerce.saas.application.ModelConfigService;
import com.yingdao.aicommerce.shop.infrastructure.MarketplaceImageDownloader;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiWatermarkLocalScreenTest {
 @Test void sizeChartPreservesOriginalAndNeverCallsImageGeneration()throws Exception{run("SIZE_CHART");}
 @Test void presentWatermarkGenerates()throws Exception{run("PRESENT");}
 @Test void clearlyAbsentWatermarkKeepsOriginal()throws Exception{run("ABSENT");}
 @Test void confidence96DoesNotBypassGeneration()throws Exception{run("LOW_CONFIDENCE_ABSENT");}
 @Test void enhancedViewFindsWatermarkAndGenerates()throws Exception{run("CONFIRM_PRESENT");}
 @Test void uncertainConfirmationGenerates()throws Exception{run("CONFIRM_UNCERTAIN");}
 @Test void failedConfirmationGeneratesAndPreservesBothResults()throws Exception{run("CONFIRM_FAILED");}
 @Test void lowConfidenceConfirmationGenerates()throws Exception{run("CONFIRM_LOW_CONFIDENCE");}
 @Test void absentWithRegionsDoesNotBypassGeneration()throws Exception{run("CONFIRM_REGIONS");}
 @Test void absentWithoutExplicitEmptyRegionsDoesNotBypassGeneration()throws Exception{run("CONFIRM_MISSING_REGIONS");}
 @Test void outOfRangeConfidenceDoesNotBypassGeneration()throws Exception{run("CONFIRM_INVALID_CONFIDENCE");}
 @Test void previousReadyResultIsNeverReprocessed()throws Exception{run("READY");}
 @Test void oldSingleAbsentDetectionDoesNotBypassGeneration()throws Exception{run("OLD_DETECTED");}
 @Test void complexBackgroundGeneratesEvenWhenOriginalDetectionIsHighlyConfidentAbsent()throws Exception{run("COMPLEX_ABSENT");}
 @Test void savedDoubleAbsentCannotBypassComplexBackgroundRestriction()throws Exception{run("SAVED_COMPLEX_BACKGROUND");}
 @Test void savedDoubleAbsentWithoutBackgroundEvidenceStillGenerates()throws Exception{run("SAVED_MISSING_BACKGROUND");}
 @Test void unsentPoolLeaseTimeoutReturnsToDetectedAndCanBeSafelyQueuedAgain()throws Exception{run("LEASE_TIMEOUT");}
 @Test void ambiguousResponseTimeoutPreservesIntentForQueuedRecovery()throws Exception{run("RESPONSE_TIMEOUT");}
 @Test void uncertainDetectionStillGenerates()throws Exception{run("UNCERTAIN");}
 @Test void failedDetectionStillGenerates()throws Exception{run("DETECTION_FAILED");}
 @Test void receivedArtifactDoesNotRepeatGeneration()throws Exception{run("CACHED");}
 @Test void generationFailureIsNotSuccess()throws Exception{run("REJECTED");}
 @Test void failedResultDownloadPreservesGenerationAndRetryOnlyDownloads()throws Exception{run("RESULT_DOWNLOAD_RETRY");}
 @Test void completedOperationRecoversSavedResultWithoutGeneratingAgain()throws Exception{run("RECOVERY_COMPLETED");}
 @Test void activeOperationWaitsWithoutSubmittingAgain()throws Exception{run("RECOVERY_ACTIVE");}
 @Test void failedOperationUsesNewDurableAttemptWithoutRepeatingDetection()throws Exception{run("RECOVERY_FAILED");}
 @Test void recoverableOperationReattachesTheOriginalAttempt()throws Exception{run("RECOVERY_REATTACH");}
 @Test void failedOperationLookupNeverStartsAnotherGeneration()throws Exception{run("RECOVERY_LOOKUP_FAILED");}
 @Test void exhaustedGenerationRetriesStopWithTheActualError()throws Exception{run("RECOVERY_EXHAUSTED");}
 private void run(String decision)throws Exception{
  var json=new ObjectMapper();var requests=new ArrayList<String>();var requestBodies=new ArrayList<Map>();
  var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  server.createContext("/v1/product/analyze",exchange->{
   var body=json.readValue(exchange.getRequestBody().readAllBytes(),Map.class);
   synchronized(requests){requests.add((String)((Map<?,?>)body.get("context")).get("analysisType"));requestBodies.add(body);}
   boolean confirmation=requests.size()==2;
   String detected=decision.startsWith("CONFIRM_")||List.of("LOW_CONFIDENCE_ABSENT","COMPLEX_ABSENT").contains(decision)?"ABSENT":decision;
   if(confirmation&&List.of("CONFIRM_PRESENT","CONFIRM_UNCERTAIN").contains(decision))detected=decision.substring(8);
   double confidence="LOW_CONFIDENCE_ABSENT".equals(decision)&&!confirmation?.96:confirmation&&"CONFIRM_LOW_CONFIDENCE".equals(decision)?.98:confirmation&&"CONFIRM_INVALID_CONFIDENCE".equals(decision)?1.1:.995;
   var result=new LinkedHashMap<String,Object>();result.put("decision",detected);result.put("confidence",confidence);
   if(!confirmation||!"CONFIRM_MISSING_REGIONS".equals(decision))result.put("regions",confirmation&&"CONFIRM_REGIONS".equals(decision)?List.of(Map.of("x",0,"y",0,"width",1,"height",.1)):List.of());
   if("SIZE_CHART".equals(decision)){result.put("decision","UNCERTAIN");result.put("imageType","SIZE_CHART");result.put("sizeChart",Map.of("readable",false));}
   byte[] response=json.writeValueAsString(Map.of("result",result)).getBytes(StandardCharsets.UTF_8);
   exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders("DETECTION_FAILED".equals(decision)||confirmation&&"CONFIRM_FAILED".equals(decision)?500:200,response.length);exchange.getResponseBody().write(response);exchange.close();
  });server.start();
  try{
   var ledger=mock(ProductWatermarkLedger.class);var archive=mock(CommerceImageArchive.class);var downloader=mock(MarketplaceImageDownloader.class);var generator=mock(CommerceDesignImageGateway.class);var models=mock(ModelConfigService.class);var db=mock(JdbcTemplate.class);var local=mock(LocalWatermarkDetector.class);
   var capture=mock(ProductCapture.class);var image=mock(ProductCaptureImage.class);
   when(capture.getOrganizationId()).thenReturn("org");when(capture.getId()).thenReturn("capture");when(image.getId()).thenReturn("image");
   var sourcePixels=new BufferedImage(100,100,BufferedImage.TYPE_INT_RGB);
   for(int y=0;y<100;y++)for(int x=0;x<100;x++)sourcePixels.setRGB(x,y,List.of("COMPLEX_ABSENT","SAVED_COMPLEX_BACKGROUND").contains(decision)?0x303030:0xffffff);
   var out=new ByteArrayOutputStream();ImageIO.write(sourcePixels,"png",out);byte[] bytes=out.toByteArray();String hash=CommerceImageArchive.sha(bytes),data=Base64.getEncoder().encodeToString(bytes);
   when(db.queryForList(anyString(),eq("org"),eq("capture"),eq("image"))).thenReturn(List.of(Map.of("storage_key","source")));
   if("RESULT_DOWNLOAD_RETRY".equals(decision))when(db.queryForList(anyString(),eq("org"),eq("capture"),eq("image")))
      .thenReturn(List.of(),List.of(Map.of("storage_key","source")));
   when(archive.read("org","source")).thenReturn(new CommerceDesignImageGateway.Reference("image/png",data));
   when(archive.store(eq("org"),any())).thenReturn(new CommerceImageArchive.Archived("source",hash,"image/png",bytes.length));
   var model=new ModelConfigService.RuntimeModelConnection("model","http://127.0.0.1:1","test-model","OPENAI_COMPATIBLE","CUSTOM","unit-only");
   when(models.watermarkDriverConnection("org")).thenReturn(Optional.of(model));when(models.watermarkImageConnection("org")).thenReturn(Optional.of(model));
   when(generator.generateArtwork(anyString(),anyString(),any(),anyList())).thenReturn(new CommerceDesignImageGateway.Response(new CommerceDesignImageGateway.Artifact(data,"image/png","test","edit",""),null));
   var row=new HashMap<String,Object>();row.put("status","PREPARED");row.put("source_sha256",hash);
   if(decision.startsWith("RECOVERY_")){
    row.put("status","REVIEW_REQUIRED");row.put("detection_json","{\"decision\":\"UNCERTAIN\"}");
    row.put("generation_retry_count","RECOVERY_EXHAUSTED".equals(decision)?6:0);row.put("message","unit rejected");
    if("RECOVERY_LOOKUP_FAILED".equals(decision))when(generator.operation(anyString())).thenThrow(new IllegalStateException("unit lookup failed"));
    else when(generator.operation(anyString())).thenReturn(new CommerceDesignImageGateway.Operation(
      "RECOVERY_COMPLETED".equals(decision)?"COMPLETED":"RECOVERY_REATTACH".equals(decision)?"RECOVERABLE":
       "RECOVERY_ACTIVE".equals(decision)?"RUNNING":"FAILED","RECOVERY_ACTIVE".equals(decision),0,
      "RECOVERY_COMPLETED".equals(decision)?new CommerceDesignImageGateway.Response(new CommerceDesignImageGateway.Artifact(data,"image/png","grok","edit",""),null):null,null));
    when(ledger.retryGeneration(anyString(),eq("org"),anyString(),anyInt())).thenAnswer(c->{
     row.put("generation_retry_count",(Integer)c.getArgument(3)+1);row.put("status","DETECTED");return true;});
    doAnswer(c->{row.put("status","RESULT_RECEIVED");row.put("generation_json",c.getArgument(3));return null;})
     .when(ledger).recoverReceived(anyString(),eq("org"),anyString(),anyString());
   }
   if("LEASE_TIMEOUT".equals(decision)){
    when(image.getOriginalUrl()).thenReturn("https://source.invalid/original.png");
    when(db.queryForList(anyString(),eq("org"),eq("capture"),eq("image"))).thenAnswer(c->{
     String sql=c.getArgument(0);
     return sql.contains("FROM product_watermark_checks")&&"DETECTED".equals(row.get("status"))?List.of(Map.of("storage_key","source")):List.of();
    });
    when(downloader.download("https://source.invalid/original.png"))
     .thenReturn(new MarketplaceImageDownloader.ImageBinary(bytes,"image/png",".png"))
     .thenThrow(new IllegalStateException("Remote original changed or became unavailable; must use archive"));
   }
   if(List.of("READY","OLD_DETECTED").contains(decision)){
    row.put("status","READY".equals(decision)?"READY":"DETECTED");row.put("detection_json","{\"decision\":\"ABSENT\",\"confidence\":0.995,\"regions\":[]}");
   }
   if(decision.startsWith("SAVED_")){
    row.put("status","DETECTED");
    var absent=Map.of("decision","ABSENT","confidence",.995,"regions",List.of());
    var saved=new LinkedHashMap<String,Object>(absent);saved.put("method","ORIGINAL_AND_ENHANCED_PRE_DETECTION_V1");saved.put("originalDetection",absent);saved.put("confirmationDetection",absent);
    if("SAVED_COMPLEX_BACKGROUND".equals(decision))saved.put("backgroundAssessment",WatermarkDetectionView.assessBackground(bytes));
    row.put("detection_json",json.writeValueAsString(saved));
   }
   if("REJECTED".equals(decision))when(generator.generateArtwork(anyString(),anyString(),any(),anyList())).thenThrow(new IllegalStateException("model rejected"));
   if("LEASE_TIMEOUT".equals(decision))when(generator.generateArtwork(anyString(),anyString(),any(),anyList()))
     .thenThrow(new ImageGenerationNotSentException(new org.apache.hc.core5.http.ConnectionRequestTimeoutException("unit-only pool exhausted")))
     .thenReturn(new CommerceDesignImageGateway.Response(new CommerceDesignImageGateway.Artifact(data,"image/png","test","edit",""),null));
   if("RESPONSE_TIMEOUT".equals(decision))when(generator.generateArtwork(anyString(),anyString(),any(),anyList()))
     .thenThrow(new org.springframework.web.client.ResourceAccessException("unit-only response timeout",new java.net.SocketTimeoutException("read timed out")));
   if(List.of("CACHED","RESULT_DOWNLOAD_RETRY").contains(decision)){
    row.put("status","RESULT_RECEIVED");row.put("detection_json","{\"method\":\"DIRECT_IMAGE_EDIT\"}");
    row.put("generation_json",json.writeValueAsString("RESULT_DOWNLOAD_RETRY".equals(decision)
       ?new CommerceDesignImageGateway.Response(null,new CommerceDesignImageGateway.PendingResult("https://result.invalid/image.png","image/png","grok-imagine-image-2.0","edit",""))
       :new CommerceDesignImageGateway.Response(new CommerceDesignImageGateway.Artifact(data,"image/png","test","edit",""),null)));
   }
   doAnswer(c->{row.put("status","EDITING");row.put("detection_json","{\"method\":\"DIRECT_IMAGE_EDIT\"}");return null;}).when(ledger).directEdit(anyString(),eq("org"));
   when(ledger.prepare(anyString(),anyString(),anyString(),anyString(),anyString(),anyString())).thenReturn(row);when(ledger.get(anyString(),eq("org"))).thenReturn(row);
   when(ledger.claim(anyString(),eq("org"),anyString(),anyString())).thenAnswer(c->{row.put("status",c.getArgument(3));return true;});
   doAnswer(c->{row.put("status","DETECTED");row.put("detection_json",c.getArgument(2));return null;}).when(ledger).detection(anyString(),eq("org"),anyString());
   doAnswer(c->{row.put("status","RESULT_RECEIVED");row.put("generation_json",c.getArgument(2));return null;}).when(ledger).generated(anyString(),eq("org"),anyString());
   doAnswer(c->{row.put("status","EDITED");row.put("candidate_key",c.getArgument(2));return null;}).when(ledger).edited(anyString(),eq("org"),anyString());
   doAnswer(c->{row.put("status","READY");return null;}).when(ledger).ready(anyString(),eq("org"),anyString(),anyString(),anyString());
   doAnswer(c->{row.put("status","REVIEW_REQUIRED");return null;}).when(ledger).hold(anyString(),eq("org"),anyString(),anyString());
   var provider=new AiWatermarkRemovalProvider(ledger,archive,downloader,generator,models,db,json,local,"http://127.0.0.1:"+server.getAddress().getPort(),"unit-only","test-model");
   if(decision.startsWith("RECOVERY_")){
    if(List.of("RECOVERY_ACTIVE","RECOVERY_LOOKUP_FAILED").contains(decision))
     assertThrows(WatermarkRateLimitException.class,()->provider.process(capture,image));
    else if("RECOVERY_EXHAUSTED".equals(decision))
     assertTrue(assertThrows(IllegalStateException.class,()->provider.process(capture,image)).getMessage().contains("unit rejected"));
    else assertTrue(provider.process(capture,image).url().startsWith(ProductWatermarkAssets.PREFIX));
    assertTrue(requests.isEmpty());verifyNoInteractions(downloader,local);
    String checkId=CommerceImageArchive.sha(("org:capture:image:"+hash+":test-model:WATERMARK_V12_DETECT_FALLBACK_EDIT").getBytes(StandardCharsets.UTF_8));
    if(List.of("RECOVERY_FAILED","RECOVERY_REATTACH").contains(decision))
     verify(generator).generateArtwork(anyString(),eq(checkId+("RECOVERY_FAILED".equals(decision)?"_retry_1":"")),eq(model),anyList());
    else verify(generator,never()).generateArtwork(anyString(),anyString(),any(),anyList());
    if(!"RECOVERY_FAILED".equals(decision))verify(ledger,never()).retryGeneration(anyString(),anyString(),anyString(),anyInt());
    return;
   }
   if("RESULT_DOWNLOAD_RETRY".equals(decision)){
    String saved=(String)row.get("generation_json");
    when(downloader.download("https://result.invalid/image.png")).thenThrow(new IllegalStateException("图片域名解析失败"));
    var failure=assertThrows(GeneratedImageDownloadPendingException.class,()->provider.process(capture,image));
    assertTrue(failure.getMessage().contains("生图已成功，下载结果图片失败"));
    assertEquals("RESULT_RECEIVED",row.get("status"));assertEquals(saved,row.get("generation_json"));
    verify(ledger,never()).hold(anyString(),anyString(),anyString(),anyString());
    doReturn(new MarketplaceImageDownloader.ImageBinary(bytes,"image/png",".png")).when(downloader).download("https://result.invalid/image.png");
   }
   if("LEASE_TIMEOUT".equals(decision)){
    assertThrows(WatermarkRateLimitException.class,()->provider.process(capture,image));
    assertEquals("DETECTED",row.get("status"));
    assertNotNull(row.get("detection_json"));assertFalse(row.containsKey("generation_json"));
    verify(ledger,never()).hold(anyString(),anyString(),anyString(),anyString());
   }
   if("RESPONSE_TIMEOUT".equals(decision)){
    assertThrows(org.springframework.web.client.ResourceAccessException.class,()->provider.process(capture,image));
    assertEquals("REVIEW_REQUIRED",row.get("status"));
    assertThrows(WatermarkRateLimitException.class,()->provider.process(capture,image));
   }
   else if("REJECTED".equals(decision))assertThrows(IllegalStateException.class,()->provider.process(capture,image));
   else assertTrue(provider.process(capture,image).url().startsWith(ProductWatermarkAssets.PREFIX));
   boolean cached=List.of("CACHED","RESULT_DOWNLOAD_RETRY","READY").contains(decision);
   boolean confirmed="ABSENT".equals(decision)||decision.startsWith("CONFIRM_")||"LOW_CONFIDENCE_ABSENT".equals(decision);
   assertEquals(Collections.nCopies(cached||"OLD_DETECTED".equals(decision)||decision.startsWith("SAVED_")?0:confirmed?2:1,"WATERMARK_DETECTION_V1"),requests);
   // The generation reference must be the exact archived original, never the enhanced pixels.
   verify(generator,times(cached||"ABSENT".equals(decision)||"SIZE_CHART".equals(decision)?0:"LEASE_TIMEOUT".equals(decision)?2:1)).generateArtwork(anyString(),anyString(),eq(model),eq(List.of(new CommerceDesignImageGateway.Reference("image/png",data))));
   if(!requestBodies.isEmpty()){
    assertFalse(requestBodies.getFirst().containsKey("detectionViews"));
    assertFalse(requestBodies.getFirst().containsKey("candidateImage"));
   }
   if(confirmed){
    var second=requestBodies.get(1);
    assertEquals("ENHANCED_CONFIRMATION",((Map<?,?>)second.get("context")).get("detectionPass"));
    assertEquals(requestBodies.getFirst().get("image"),second.get("image"));
    assertFalse(second.containsKey("candidateImage"));
    var views=(List<Map<String,Object>>)second.get("detectionViews");assertEquals(1,views.size());
    assertEquals("image/jpeg",views.getFirst().get("mimeType"));
    byte[] viewBytes=Base64.getDecoder().decode((String)views.getFirst().get("data"));
    assertTrue(viewBytes.length<=WatermarkDetectionView.MAX_BYTES);assertFalse(Arrays.equals(bytes,viewBytes));
    var evidence=json.readValue((String)row.get("detection_json"),Map.class);
    assertEquals("ABSENT",((Map<?,?>)evidence.get("originalDetection")).get("decision"));
    assertInstanceOf(Map.class,evidence.get("confirmationDetection"));
    assertTrue(WatermarkDetectionView.permitsSkipping((Map<?,?>)evidence.get("backgroundAssessment")));
    assertEquals(!"ABSENT".equals(decision),evidence.get("fallbackToGeneration"));
    if("LOW_CONFIDENCE_ABSENT".equals(decision))assertEquals(.96,((Map<?,?>)evidence.get("originalDetection")).get("confidence"));
    if("CONFIRM_FAILED".equals(decision))assertTrue(((Map<?,?>)evidence.get("confirmationDetection")).containsKey("detectionError"));
   }
   if("COMPLEX_ABSENT".equals(decision)){
    var evidence=json.readValue((String)row.get("detection_json"),Map.class);
    assertEquals("UNCERTAIN",evidence.get("decision"));
    assertEquals("ABSENT",((Map<?,?>)evidence.get("originalDetection")).get("decision"));
    assertEquals(.995,((Map<?,?>)evidence.get("originalDetection")).get("confidence"));
    assertFalse(WatermarkDetectionView.permitsSkipping((Map<?,?>)evidence.get("backgroundAssessment")));
    assertFalse(evidence.containsKey("confirmationDetection"));
    assertEquals(true,evidence.get("fallbackToGeneration"));
   }
   verifyNoInteractions(local);
   if("RESULT_DOWNLOAD_RETRY".equals(decision))verify(downloader,times(2)).download("https://result.invalid/image.png");
   else if("LEASE_TIMEOUT".equals(decision)){
    verify(downloader).download("https://source.invalid/original.png");
    // Both process attempts retain the same source hash/check ID; only the second is sent.
    String checkId=CommerceImageArchive.sha(("org:capture:image:"+hash+":test-model:WATERMARK_V12_DETECT_FALLBACK_EDIT").getBytes(StandardCharsets.UTF_8));
    verify(ledger,times(2)).prepare(eq(checkId),eq("org"),eq("capture"),eq("image"),eq(hash),eq("source"));
    verify(ledger).generated(eq(checkId),eq("org"),anyString());
    verify(ledger,never()).hold(anyString(),anyString(),anyString(),anyString());
   }
   else verifyNoInteractions(downloader);

   verify(ledger,never()).claim(anyString(),anyString(),anyString(),eq("VALIDATING"));
   if(!List.of("REJECTED","READY","RESPONSE_TIMEOUT").contains(decision))verify(ledger).ready(anyString(),eq("org"),anyString(),eq("source"),contains("postValidationSkipped"));
   else verify(ledger,never()).ready(anyString(),anyString(),anyString(),anyString(),anyString());
  }finally{server.stop(0);}
 }
}
