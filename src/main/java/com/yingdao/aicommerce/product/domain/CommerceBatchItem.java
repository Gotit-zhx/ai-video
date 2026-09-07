package com.yingdao.aicommerce.product.domain;
import java.time.Instant;
public record CommerceBatchItem(String id, String organizationId, String batchId, String captureId,
                                long captureVersion, String shopId, String shopName, int plannedDay,
                                String status, Instant createdAt) {}
