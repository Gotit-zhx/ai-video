package com.yingdao.aicommerce.product.application;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ProductBrandRejectionTest {
 @Test void onlyExactCreateValidationRejectionReleasesDuplicateGuard() {
  assertTrue(ProductPublishWorkflowProcessor.isConfirmedBrandRejection("Shopee 官方接口 /api/v2/product/add_item 失败：Brand information required"));
  assertFalse(ProductPublishWorkflowProcessor.isConfirmedBrandRejection(null));
  assertFalse(ProductPublishWorkflowProcessor.isConfirmedBrandRejection("请求超时"));
  assertFalse(ProductPublishWorkflowProcessor.isConfirmedBrandRejection("Shopee 官方接口 /api/v2/product/init_tier_variation 失败：Brand information required"));
  assertFalse(ProductPublishWorkflowProcessor.isConfirmedBrandRejection("Shopee 商品 123 已创建：Brand information required"));
 }
}
