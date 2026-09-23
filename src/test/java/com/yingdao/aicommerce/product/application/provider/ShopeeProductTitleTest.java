package com.yingdao.aicommerce.product.application.provider;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class ShopeeProductTitleTest {
 @Test void realRejectedTitleFitsWithoutCuttingAWord() {
  String original="【Ready Stock】Cherry Women's White T-Shirt/Short Sleeve Round Neck Look Slim Down White Cotton T-Shirt Very Soft (Multicoloured)";
  String title=ShopeeProductTitle.adapt(original,"T-Shirts",20,100);
  assertThat(title).startsWith("Cherry Women's").hasSizeBetween(20,100).doesNotEndWith("(");
  assertThat(title).isEqualTo("Cherry Women's White T-Shirt/Short Sleeve Round Neck Look Slim Down White Cotton T-Shirt Very Soft");
 }
 @Test void legalTitlesRemainUnchangedAndEachShopUsesOwnLimit() {
  String title="Cotton Women's T-Shirt with Short Sleeves and Round Neck";
  assertThat(ShopeeProductTitle.adapt(title,"T-Shirts",20,100)).isEqualTo(title);
  assertThat(ShopeeProductTitle.adapt(title,"T-Shirts",20,40)).hasSizeBetween(20,40).doesNotEndWith("Sle");
 }
 @Test void shortTitleUsesOnlyKnownCategoryAndDoesNotInventClaims() {
  assertThat(ShopeeProductTitle.adapt("Cotton Top","Women's Casual T-Shirts",20,100)).isEqualTo("Cotton Top - Women's Casual T-Shirts");
  assertThatThrownBy(()->ShopeeProductTitle.adapt("Top","",20,100)).hasMessageContaining("补充真实商品属性");
 }
 @Test void unicodeAndWhitespaceAreSafe() {
  String title=ShopeeProductTitle.adapt("\u00a0女装棉质夏季短袖圆领休闲上衣".repeat(8),"",20,60);
  assertThat(title.codePointCount(0,title.length())).isBetween(20,60);
  String emoji=ShopeeProductTitle.adapt("😀".repeat(110),"",20,100);
  assertThat(emoji.codePointCount(0,emoji.length())).isEqualTo(100);
  assertThat(emoji).doesNotContain("\ufffd");
 }
}
