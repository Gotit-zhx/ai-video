package com.yingdao.aicommerce.product.domain;
import java.time.Instant;
import java.time.LocalDate;
public record CommerceListingReservation(String id,String organizationId,String batchId,String itemId,
        String shopId,LocalDate listingDate,String timeZone,String createdBy,Instant createdAt) {}
