package com.yingdao.aicommerce.product.domain;
import java.time.Instant;
public record CommerceDesignDraft(String id,String organizationId,String captureId,long captureVersion,
        String requestKey,String requestHash,int variantIndex,String mode,String brief,String rightsEvidence,
        String sourceImagesJson,String status,String createdBy,Instant createdAt) {}
