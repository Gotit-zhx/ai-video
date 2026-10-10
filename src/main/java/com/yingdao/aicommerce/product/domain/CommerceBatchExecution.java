package com.yingdao.aicommerce.product.domain;
import java.time.Instant;
public record CommerceBatchExecution(String id,String organizationId,String batchId,String createdBy,String stage,String status,String message,String leaseToken,Instant leaseUntil,Instant createdAt,Instant updatedAt) {
 public enum Stage { CAPTURE,DETECT,EXTRACT,VARIATE,MOCKUP,REVIEW,PUBLISH;
  public Stage next(){return ordinal()+1<values().length?values()[ordinal()+1]:null;}
 }
}
