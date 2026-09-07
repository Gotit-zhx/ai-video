package com.yingdao.aicommerce.product.domain;
import java.time.Instant;
public record CommerceDesignReview(String id,String organizationId,String outputId,String contentSha256,String decision,
        String checksJson,String notes,String reviewedBy,Instant reviewedAt){}
