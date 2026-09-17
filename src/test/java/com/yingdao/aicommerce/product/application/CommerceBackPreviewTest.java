package com.yingdao.aicommerce.product.application;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
class CommerceBackPreviewTest {
 @Test void backUsesMatchingCaptureVariantAndColorOnly(){
  var correct=Map.<String,Object>of("capture_id","a","kind","MATTE_G2","variant_index",2,"storage_key","right");
  var wrong=Map.<String,Object>of("capture_id","b","kind","MATTE_G2","variant_index",2,"storage_key","wrong");
  assertEquals(correct,CommerceBackPreviewService.find(List.of(wrong,correct),"a","MATTE_G2",2,null));
  assertThrows(IllegalArgumentException.class,()->CommerceBackPreviewService.find(List.of(wrong,correct),"a","MATTE_G2",1,null));
  assertThrows(IllegalArgumentException.class,()->CommerceBackPreviewService.find(List.of(wrong,correct),"a","MATTE_G2",2,"black"));
 }
 @Test void onlyReadyFrontExposesBackLink(){
  var art=new CommerceBatchArtworkService.Artifact("id","capture","MOCKUP_FRONT",1,"READY","/api/front/image","sha","",Map.of(),"black","Black");
  assertEquals("/api/front/back-image",new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(art)).path("backImageUrl").asText());
  assertNull(new CommerceBatchArtworkService.Artifact("id","capture","VARIATE",1,"READY","/api/front/image","sha","",Map.of()).backImageUrl());
 }
}
