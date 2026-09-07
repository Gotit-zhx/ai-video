package com.yingdao.aicommerce.product.domain;
import java.time.Instant;
public record CommerceDesignOutput(String id,String organizationId,String designId,String attemptId,
        String storageKey,String contentSha256,String mimeType,int byteLength,String status,Instant createdAt) {}
