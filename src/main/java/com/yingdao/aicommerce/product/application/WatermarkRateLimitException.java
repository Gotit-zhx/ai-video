package com.yingdao.aicommerce.product.application;
/** Safe-to-defer work: a read-only rejection or an image POST proven not to have been sent. */
public class WatermarkRateLimitException extends RuntimeException {
 public WatermarkRateLimitException(String message){super(message);}
 public WatermarkRateLimitException(String message,Throwable cause){super(message,cause);}
}
