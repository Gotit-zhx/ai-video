package com.yingdao.aicommerce.product.application;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class CommerceBatchCreativePlanValidatorTest {
 final CommerceBatchCreativePlanValidator.Source source=new CommerceBatchCreativePlanValidator.Source("capture",2,List.of("image-a","image-b"));
 final CommerceBatchCreativePlanValidator.Selection selected=new CommerceBatchCreativePlanValidator.Selection("capture",2,"image-b");
 @Test void sameCaptureAcrossShopsNeedsOnlyOneExplicitSource(){var p=CommerceBatchCreativePlanValidator.validate("snapshot","PRINT_LAYOUT",List.of(source,source),List.of(selected),10);assertEquals(1,p.sources().size());assertEquals("image-b",p.sources().getFirst().imageId());}
 @Test void publishingTemplateCannotReplacePrintLayout(){assertThrows(IllegalArgumentException.class,()->CommerceBatchCreativePlanValidator.validate("snapshot","WORKFLOW",List.of(source),List.of(selected),1));}
 @Test void versionDriftIsRejected(){assertThrows(IllegalArgumentException.class,()->CommerceBatchCreativePlanValidator.validate("snapshot","PRINT_LAYOUT",List.of(source),List.of(new CommerceBatchCreativePlanValidator.Selection("capture",3,"image-a")),1));}
 @Test void arbitraryImageCannotBeUsed(){assertThrows(IllegalArgumentException.class,()->CommerceBatchCreativePlanValidator.validate("snapshot","PRINT_LAYOUT",List.of(source),List.of(new CommerceBatchCreativePlanValidator.Selection("capture",2,"external-image")),1));}
 @Test void missingChoiceIsNotFilledWithFirstImage(){assertThrows(IllegalArgumentException.class,()->CommerceBatchCreativePlanValidator.validate("snapshot","PRINT_LAYOUT",List.of(source),List.of(),1));}
 @Test void historicalConstructorsKeepLegacyPolicyWhileColorSnapshotsAreImmutable(){
  var color=new CommerceSourceColorResolver.Color("sku-color:black","Black",List.of("black-s","black-m"),"image-b",true);
  var colors=new ArrayList<>(List.of(color));
  var selection=new CommerceBatchCreativePlanValidator.Selection("capture",2,"image-a",List.of(),colors);
  var sources=new ArrayList<>(List.of(selection));
  var plan=new CommerceBatchCreativePlanValidator.Plan("snapshot",sources,3,null,"SOURCE_PRODUCT","SOURCE_SKU_COLORS");
  colors.clear();sources.clear();
  assertEquals(List.of(color),plan.sources().getFirst().colorTargets());
  assertThrows(UnsupportedOperationException.class,()->selection.colorTargets().clear());
  assertTrue(CommerceBatchCreativePlanValidator.sourceColorOutput(plan));
  for(var legacy:List.of(new CommerceBatchCreativePlanValidator.Plan("s",List.of(selected),1),
          new CommerceBatchCreativePlanValidator.Plan("s",List.of(selected),1,null),
          new CommerceBatchCreativePlanValidator.Plan("s",List.of(selected),1,null,"SOURCE_PRODUCT"))){
   assertNull(legacy.outputPolicy());assertFalse(CommerceBatchCreativePlanValidator.sourceColorOutput(legacy));
   assertTrue(legacy.sources().getFirst().colorTargets().isEmpty());
  }
 }
 @Test void colorPolicyRequiresUniqueGroupsWithReadableSourceReferences(){
  var black=new CommerceSourceColorResolver.Color("sku-color:black","Black",List.of("s","m"),"image-b",true);
  var valid=new CommerceBatchCreativePlanValidator.Selection("capture",2,"image-a",List.of(),List.of(black));
  assertDoesNotThrow(()->CommerceBatchCreativePlanValidator.validateColorTargets(valid,List.of("image-a","image-b")));
  assertThrows(IllegalArgumentException.class,()->CommerceBatchCreativePlanValidator.validateColorTargets(selected,null));
  assertThrows(IllegalArgumentException.class,()->CommerceBatchCreativePlanValidator.validateColorTargets(
          new CommerceBatchCreativePlanValidator.Selection("capture",2,"image-a",List.of(),List.of(black,black)),null));
  assertTrue(assertThrows(IllegalArgumentException.class,()->CommerceBatchCreativePlanValidator.validateColorTargets(valid,List.of("image-a"))).getMessage().contains("不会猜测颜色"));
  var inherited=new CommerceSourceColorResolver.Color("sku-color:black","Black",List.of("s"),"image-a",false);
  assertThrows(IllegalArgumentException.class,()->CommerceBatchCreativePlanValidator.validateColorTargets(
          new CommerceBatchCreativePlanValidator.Selection("capture",2,"image-a",List.of(),List.of(inherited)),List.of("image-a")));
 }
 @Test void unknownOutputPolicyCannotSilentlyUseHistoricalOutputRules(){
  assertDoesNotThrow(()->CommerceBatchCreativePlanValidator.validateOutputPolicy(null));
  assertDoesNotThrow(()->CommerceBatchCreativePlanValidator.validateOutputPolicy("SOURCE_SKU_COLORS"));
  assertThrows(IllegalArgumentException.class,()->CommerceBatchCreativePlanValidator.validateOutputPolicy("ALL_SKUS"));
 }
}
