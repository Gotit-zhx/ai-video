package com.yingdao.aicommerce.product.domain;
import java.time.Instant;
public record CommerceBatchReview(String id,String organizationId,String batchId,String templateSnapshotId,
                                  String evidenceJson,String reviewedBy,Instant reviewedAt) {}
