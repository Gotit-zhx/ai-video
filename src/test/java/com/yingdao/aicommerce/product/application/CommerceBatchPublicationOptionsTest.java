package com.yingdao.aicommerce.product.application;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.product.domain.*;
import com.yingdao.aicommerce.product.mapper.CommerceBatchMapper;
import com.yingdao.aicommerce.shop.domain.Shop;
import com.yingdao.aicommerce.shop.mapper.ShopMapper;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class CommerceBatchPublicationOptionsTest {
 final CommerceBatchMapper batches=mock(CommerceBatchMapper.class);
 final ProductCaptureService captures=mock(ProductCaptureService.class);
 final ShopMapper shops=mock(ShopMapper.class);
 final JdbcTemplate db=mock(JdbcTemplate.class);final ObjectMapper json=new ObjectMapper();
 final CommerceBatchPublicationOptions service=new CommerceBatchPublicationOptions(batches,captures,shops,db,json);
 final PlatformPrincipal user=new PlatformPrincipal("u","org","org","u","u","",true,List.of(),List.of("product:publish"));
 final CommerceBatchItem first=new CommerceBatchItem("one","org","batch","capture-one",1,"shop","Shop",1,"DRAFT",Instant.now());
 final CommerceBatchItem second=new CommerceBatchItem("two","org","batch","capture-two",1,"shop","Shop",1,"DRAFT",Instant.now());
 Map<String,Object> valid(String category){return new LinkedHashMap<>(Map.of("autoConvertPrice",true,"publishStock",999,"weightGrams",250,"packageLengthCm",30,"packageWidthCm",25,"packageHeightCm",2,"categoryId",category,"warehouseId","warehouse"));}
 CommerceBatchPublicationOptions.PublicationRequest row(CommerceBatchItem item,Map<String,Object> body){return new CommerceBatchPublicationOptions.PublicationRequest(item.captureId(),item.shopId(),body);}
 @BeforeEach void setup(){
  var batch=new CommerceBatch("batch","org","batch","template","key","hash","DRAFT","u",Instant.now());
  when(batches.lock("org","batch")).thenReturn(batch);when(batches.get("org","batch")).thenReturn(batch);
  when(batches.items("org","batch")).thenReturn(List.of(first,second));
  var shop=mock(Shop.class);when(shop.getOrganizationId()).thenReturn("org");when(shop.getStatus()).thenReturn("ACTIVE");when(shop.getPlatform()).thenReturn("TIKTOK_SHOP");when(shops.selectById("shop")).thenReturn(shop);
 }
 @Test void keepsDifferentCategoriesForTwoProductsInSameShopAndStripsServerOwnedFields(){
  var a=valid("category-A");a.put("executionMode","DRY_RUN");a.put("exchangeRateSnapshot",Map.of("rate",0));
  service.save("batch",List.of(row(first,a),row(second,valid("category-B"))),user);
  a.remove("executionMode");a.remove("exchangeRateSnapshot");
  verify(db).update(anyString(),eq("org"),eq("batch"),eq("one"),eq(json.writeValueAsString(a)));
  verify(db).update(anyString(),eq("org"),eq("batch"),eq("two"),eq(json.writeValueAsString(valid("category-B"))));
 }
 @Test void partialOrExtraneousTargetsCannotPersistAnyRows(){
  assertThrows(BusinessException.class,()->service.save("batch",List.of(row(first,valid("a"))),user));
  assertThrows(BusinessException.class,()->service.save("batch",List.of(new CommerceBatchPublicationOptions.PublicationRequest("alien","shop",valid("a"))),user));
  verify(db,never()).update(anyString(),any(),any(),any(),any());
 }
 @Test void allInvalidTargetsAreReportedTogetherBeforeWrites(){
  var a=valid("a");a.remove("warehouseId");var b=valid("b");b.put("weightGrams",0);
  var message=assertThrows(BusinessException.class,()->service.save("batch",List.of(row(first,a),row(second,b)),user)).getMessage();
  assertTrue(message.contains("capture-one"));assertTrue(message.contains("capture-two"));assertTrue(message.contains("仓库"));assertTrue(message.contains("重量"));
  verify(db,never()).update(anyString(),any(),any(),any(),any());
 }
 @Test void submittedItemCannotHaveItsParametersChanged(){
  when(db.queryForObject(anyString(),eq(Integer.class),eq("org"),eq("one"))).thenReturn(1);
  when(db.queryForList(contains("commerce_batch_item_publish_options"),eq("org"),eq("batch"),eq("one"))).thenReturn(List.of(Map.of("request_json",json.writeValueAsString(valid("fixed")))));
  assertTrue(assertThrows(BusinessException.class,()->service.save("batch",List.of(row(first,valid("changed")),row(second,valid("b"))),user)).getMessage().contains("已开始提交"));
  verify(db,never()).update(anyString(),any(),any(),any(),any());
 }
 @Test void malformedPricingAndParcelAndPlatformDeliveryAreRejected(){
  var body=valid("a");assertTrue(CommerceBatchPublicationOptions.validate(body,"TIKTOK_SHOP").isEmpty());
  body.put("autoConvertPrice",1);assertTrue(CommerceBatchPublicationOptions.validate(body,"TIKTOK_SHOP").contains("售价规则缺失"));
  body.put("autoConvertPrice",true);body.put("publishPrice",10);assertTrue(CommerceBatchPublicationOptions.validate(body,"TIKTOK_SHOP").contains("自动换算与固定售价不能同时启用"));
  body.remove("publishPrice");body.put("publishStock",1.5);assertTrue(CommerceBatchPublicationOptions.validate(body,"TIKTOK_SHOP").contains("库存无效"));
  assertTrue(CommerceBatchPublicationOptions.validate(valid("a"),"SHOPEE").contains("配送渠道缺失"));
 }
 @Test void browserNumericRoundTripDoesNotChangeSubmittedSettings(){
  var stored=valid("a");stored.put("weightGrams",250.0);
  when(db.queryForObject(anyString(),eq(Integer.class),eq("org"),eq("one"))).thenReturn(1);
  when(db.queryForList(contains("commerce_batch_item_publish_options"),eq("org"),eq("batch"),eq("one"))).thenReturn(List.of(Map.of("request_json",json.writeValueAsString(stored))));
  assertDoesNotThrow(()->service.save("batch",List.of(row(first,valid("a")),row(second,valid("b"))),user));
 }
 @Test void separateStartPathFailsBeforeRunningWithoutSavedParameters(){
  assertTrue(assertThrows(BusinessException.class,()->service.requireReady("batch",user)).getMessage().startsWith("发布配置缺失"));
 }
 @Test void creativeOnlyDoesNotNeedPublishingConfiguration(){
  when(db.queryForObject(contains("commerce_batch_creation_modes"),eq(Integer.class),eq("org"),eq("batch"))).thenReturn(1);
  assertDoesNotThrow(()->service.requireReady("batch",user));verifyNoInteractions(shops);
 }
}
