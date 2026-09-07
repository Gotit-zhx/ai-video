package com.yingdao.aicommerce.product.domain;
import java.time.Instant;
/** Immutable selection, not a publication or production approval. */
public record CommerceBatchDesignAsset(String id,String organizationId,String batchId,String itemId,
        int imagePosition,String designId,String outputId,String contentSha256,String reviewId,String boundBy,Instant boundAt){}
