package com.yingdao.aicommerce.product.domain;
import java.time.Instant;
public record CommerceDesignAttempt(String id,String organizationId,String designId,String status,
        String referenceManifestJson,String resultJson,String errorCode,Instant createdAt,Instant updatedAt) {}
