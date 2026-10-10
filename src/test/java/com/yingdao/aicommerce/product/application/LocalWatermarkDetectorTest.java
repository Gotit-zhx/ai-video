package com.yingdao.aicommerce.product.application;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class LocalWatermarkDetectorTest {
    private Map<String,Object> result(String status,List<?> boxes){return Map.of("status",status,"detections",boxes,"sourceSha256","source","modelSha256","a".repeat(64));}
    @Test void acceptsNoCandidateButNotContradictoryVerdict(){
        assertDoesNotThrow(()->LocalWatermarkDetector.validate(result("NO_CANDIDATE",List.of()),"source"));
        assertThrows(IllegalStateException.class,()->LocalWatermarkDetector.validate(result("SUSPECTED",List.of()),"source"));
        assertThrows(IllegalStateException.class,()->LocalWatermarkDetector.validate(result("NO_CANDIDATE",List.of(Map.of())),"source"));
    }
    @Test void rejectsAnotherImagesResultOrUnknownModel(){
        assertThrows(IllegalStateException.class,()->LocalWatermarkDetector.validate(result("NO_CANDIDATE",List.of()),"different"));
        assertThrows(IllegalStateException.class,()->LocalWatermarkDetector.validate(Map.of("status","NO_CANDIDATE","detections",List.of(),"sourceSha256","source"),"source"));
    }
    @Test void validatesCandidateCoordinatesBeforeRemoteReview(){
        var box=Map.of("x",.1,"y",.2,"width",.4,"height",.2,"confidence",.3);
        assertDoesNotThrow(()->LocalWatermarkDetector.validate(result("SUSPECTED",List.of(box)),"source"));
        assertThrows(IllegalStateException.class,()->LocalWatermarkDetector.validate(result("SUSPECTED",List.of(Map.of("x",.9,"y",0,"width",.4,"height",.2,"confidence",.3))),"source"));
    }
    @Test void onlyExactUserConfirmationCanSkipVision(){
        var r=new java.util.HashMap<String,Object>(result("NO_CANDIDATE",List.of()));
        assertFalse(LocalWatermarkDetector.confirmedAbsent(r,"source"));
        r.put("canAutoPass",true);
        assertFalse(LocalWatermarkDetector.confirmedAbsent(r,"source"));
        r.put("absenceBasis","USER_CONFIRMED_EXACT_IMAGE");r.put("confirmedAbsentSourceSha256","other");
        assertFalse(LocalWatermarkDetector.confirmedAbsent(r,"source"));
        r.put("confirmedAbsentSourceSha256","source");
        assertTrue(LocalWatermarkDetector.confirmedAbsent(r,"source"));
    }
}
