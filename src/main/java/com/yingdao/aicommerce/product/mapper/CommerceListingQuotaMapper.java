package com.yingdao.aicommerce.product.mapper;
import com.yingdao.aicommerce.product.domain.CommerceListingReservation;
import org.apache.ibatis.annotations.*;
import java.time.LocalDate;
import java.util.List;
@Mapper
public interface CommerceListingQuotaMapper {
    @Select("SELECT * FROM commerce_listing_reservations WHERE organization_id=#{org} AND batch_id=#{batch} AND status='RESERVED' ORDER BY listing_date,shop_id,item_id")
    List<CommerceListingReservation> list(@Param("org") String org,@Param("batch") String batch);
    @Insert("""
      INSERT INTO commerce_shop_daily_usage(organization_id,shop_id,listing_date,reserved_count)
      VALUES(#{org},#{shop},#{date},0) ON DUPLICATE KEY UPDATE reserved_count=reserved_count
      """)
    int ensureDay(@Param("org") String org,@Param("shop") String shop,@Param("date") LocalDate date);
    @Update("""
      UPDATE commerce_shop_daily_usage SET reserved_count=reserved_count+#{quantity}
      WHERE organization_id=#{org} AND shop_id=#{shop} AND listing_date=#{date}
        AND reserved_count+#{quantity}<=#{cap}
      """)
    int reserve(@Param("org") String org,@Param("shop") String shop,@Param("date") LocalDate date,
                @Param("quantity") int quantity,@Param("cap") int cap);
    @Insert("""
      INSERT INTO commerce_listing_reservations(id,organization_id,batch_id,item_id,shop_id,listing_date,time_zone,created_by,created_at)
      VALUES(#{id},#{organizationId},#{batchId},#{itemId},#{shopId},#{listingDate},#{timeZone},#{createdBy},#{createdAt})
      """)
    int insert(CommerceListingReservation item);
    @Update("""
      UPDATE commerce_shop_daily_usage SET reserved_count=reserved_count-#{quantity}
      WHERE organization_id=#{org} AND shop_id=#{shop} AND listing_date=#{date} AND reserved_count>=#{quantity}
      """)
    int releaseDay(@Param("org") String org,@Param("shop") String shop,@Param("date") LocalDate date,@Param("quantity") int quantity);
    @Update("""
      UPDATE commerce_listing_reservations SET status='RELEASED',released_by=#{actor},released_at=CURRENT_TIMESTAMP(6)
      WHERE organization_id=#{org} AND batch_id=#{batch} AND status='RESERVED'
      """)
    int releaseBatch(@Param("org") String org,@Param("batch") String batch,@Param("actor") String actor);
}
