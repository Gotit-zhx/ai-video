package com.yingdao.aicommerce.product.domain;
import java.time.Instant;
import java.time.LocalDate;
import org.apache.ibatis.annotations.AutomapConstructor;
public record CommerceListingReservation(String id,String organizationId,String batchId,String itemId,
        String shopId,LocalDate listingDate,String timeZone,String createdBy,Instant createdAt,
        String status,String releasedBy,Instant releasedAt) {
    @AutomapConstructor
    public CommerceListingReservation {}
    public CommerceListingReservation(String id,String org,String batch,String item,String shop,LocalDate date,
            String zone,String creator,Instant created){this(id,org,batch,item,shop,date,zone,creator,created,"RESERVED",null,null);}
}
