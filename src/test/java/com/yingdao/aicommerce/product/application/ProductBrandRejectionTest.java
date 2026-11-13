package com.yingdao.aicommerce.product.application;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ProductBrandRejectionTest {
 @Test void actualOfficialClientRejectionStillReleasesTheExistingPublicationGuard() {
  var brand=com.yingdao.aicommerce.shop.infrastructure.ShopeeApiFailureFixture.failure("/api/v2/product/add_item","error_param","Brand information required");
  assertTrue(ProductPublishWorkflowProcessor.isConfirmedBrandRejection(brand.getMessage()));
  var stock=com.yingdao.aicommerce.shop.infrastructure.ShopeeApiFailureFixture.failure("/api/v2/product/add_item","error_param","Product Description : invalid field seller_stock, value must Not Null");
  assertTrue(ProductPublishWorkflowProcessor.isConfirmedStockRejection(stock.getMessage()));
  var title=com.yingdao.aicommerce.shop.infrastructure.ShopeeApiFailureFixture.failure("/api/v2/product/add_item","error_param","Product name length must be between 20 and 100 characters. Please adjust it.");
  assertTrue(ProductPublishWorkflowProcessor.isConfirmedTitleRejection(title.getMessage()));
 }
 @Test void onlyExactCreateValidationRejectionReleasesDuplicateGuard() {
  assertTrue(ProductPublishWorkflowProcessor.isConfirmedBrandRejection("Shopee 官方接口 /api/v2/product/add_item 失败：Brand information required"));
  assertFalse(ProductPublishWorkflowProcessor.isConfirmedBrandRejection(null));
  assertFalse(ProductPublishWorkflowProcessor.isConfirmedBrandRejection("请求超时"));
  assertFalse(ProductPublishWorkflowProcessor.isConfirmedBrandRejection("Shopee 官方接口 /api/v2/product/init_tier_variation 失败：Brand information required"));
  assertFalse(ProductPublishWorkflowProcessor.isConfirmedBrandRejection("Shopee 商品 123 已创建：Brand information required"));
 }
}
