package com.yingdao.aicommerce.product.application;
import org.junit.jupiter.api.Test;
import java.net.*;
import javax.net.ssl.SSLHandshakeException;
import static org.junit.jupiter.api.Assertions.*;
class GeneratedImageDownloadPendingTest {
 @Test void retriesOnlyTransientReadFailures(){
  assertTrue(GeneratedImageDownloadPendingException.retryable(new IllegalStateException("failed",new SocketException("reset"))));
  assertTrue(GeneratedImageDownloadPendingException.retryable(new SocketTimeoutException()));
  assertTrue(GeneratedImageDownloadPendingException.retryable(new IllegalStateException("读取商品图失败，HTTP 503")));
  assertFalse(GeneratedImageDownloadPendingException.retryable(new IllegalStateException("读取商品图失败，HTTP 403")));
  assertFalse(GeneratedImageDownloadPendingException.retryable(new SSLHandshakeException("certificate invalid")));
  assertFalse(GeneratedImageDownloadPendingException.retryable(new IllegalArgumentException("invalid image")));
 }
 @Test void retryDelaysAreBounded(){assertEquals(10,ProductWatermarkQueue.downloadRetryDelay(0));assertEquals(300,ProductWatermarkQueue.downloadRetryDelay(99));}
}
