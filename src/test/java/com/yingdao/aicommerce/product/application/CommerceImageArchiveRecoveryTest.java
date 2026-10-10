package com.yingdao.aicommerce.product.application;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CommerceImageArchiveRecoveryTest {
 @Test void explainsNestedNetworkFailureWithoutExposingProviderUrl(){
  var error=new RuntimeException("https://example.com/?secret=hidden",new java.net.SocketTimeoutException("hidden"));
  assertEquals("下载图片超时",CommerceBatchArtworkService.archiveFailureReason(error));
 }
 @Test void databaseDiagnosticsDoNotExposeStatementOrParameters(){
  var error=new RuntimeException(new java.sql.SQLException("sensitive payload","22001",1406));
  assertEquals("归档数据库写入失败（22001/1406）",CommerceBatchArtworkService.archiveFailureReason(error));
 }
}
