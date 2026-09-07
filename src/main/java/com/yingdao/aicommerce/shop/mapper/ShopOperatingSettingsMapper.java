package com.yingdao.aicommerce.shop.mapper;
import com.yingdao.aicommerce.shop.domain.ShopOperatingSettings;
import org.apache.ibatis.annotations.*;
@Mapper
public interface ShopOperatingSettingsMapper {
    @Select("SELECT * FROM shop_operating_settings WHERE organization_id=#{org} AND shop_id=#{shop}")
    ShopOperatingSettings get(@Param("org") String org,@Param("shop") String shop);
    @Insert("""
      INSERT INTO shop_operating_settings (shop_id,organization_id,group_name,environment,daily_listing_limit,
        default_template_snapshot_id,version,updated_by,updated_at)
      VALUES (#{shopId},#{organizationId},#{groupName},#{environment},#{dailyListingLimit},
        #{defaultTemplateSnapshotId},#{version},#{updatedBy},#{updatedAt})
      """)
    int insert(ShopOperatingSettings value);
    @Update("""
      UPDATE shop_operating_settings SET group_name=#{s.groupName},environment=#{s.environment},
        daily_listing_limit=#{s.dailyListingLimit},default_template_snapshot_id=#{s.defaultTemplateSnapshotId},
        version=#{s.version},updated_by=#{s.updatedBy},updated_at=#{s.updatedAt}
      WHERE organization_id=#{s.organizationId} AND shop_id=#{s.shopId} AND version=#{expected}
      """)
    int update(@Param("s") ShopOperatingSettings value,@Param("expected") long expected);
}
