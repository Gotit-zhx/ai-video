package com.yingdao.aicommerce.product.domain;
import java.time.Instant;
public record CommerceDesignConsent(String id,String organizationId,String designId,long captureVersion,
        String imageIdsJson,String modelConnectionId,String modelId,String confirmedBy,Instant confirmedAt,String modelConfigurationSha256){}
