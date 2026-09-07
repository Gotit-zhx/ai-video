package com.yingdao.aicommerce.product.domain;
import java.time.Instant;
public record CommerceBatch(String id, String organizationId, String name, String templateSnapshotId,
                            String idempotencyKey, String requestHash, String status, String createdBy,
                            Instant createdAt) {}
