package com.yingdao.aicommerce.product.application;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ProductTitleRejectionTest {
 @Test void releasesOnlyExactCreationTitleValidationRejections() {
  String message="Shopee 官方接口 /api/v2/product/add_item 失败：Product name length must be between 20 and 100 characters. Please adjust it.";
  assertTrue(ProductPublishWorkflowProcessor.isConfirmedTitleRejection(message));
  assertFalse(ProductPublishWorkflowProcessor.isConfirmedTitleRejection(null));
  assertFalse(ProductPublishWorkflowProcessor.isConfirmedTitleRejection("HTTP 504 timeout"));
  assertFalse(ProductPublishWorkflowProcessor.isConfirmedTitleRejection(message.replace("add_item","init_tier_variation")));
  assertFalse(ProductPublishWorkflowProcessor.isConfirmedTitleRejection("商品 123 已创建："+message));
  assertFalse(ProductPublishWorkflowProcessor.isConfirmedTitleRejection(message+" connection reset"));
 }
}
