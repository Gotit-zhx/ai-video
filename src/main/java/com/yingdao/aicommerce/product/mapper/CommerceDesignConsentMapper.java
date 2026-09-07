package com.yingdao.aicommerce.product.mapper;
import com.yingdao.aicommerce.product.domain.CommerceDesignConsent;
import org.apache.ibatis.annotations.*;
@Mapper
public interface CommerceDesignConsentMapper {
    @Select("SELECT * FROM commerce_design_generation_consents WHERE organization_id=#{org} AND design_id=#{design}")
    CommerceDesignConsent get(@Param("org")String org,@Param("design")String design);
    @Insert("""
      INSERT INTO commerce_design_generation_consents(id,organization_id,design_id,capture_version,image_ids_json,model_connection_id,model_id,confirmed_by,confirmed_at)
      VALUES(#{id},#{organizationId},#{designId},#{captureVersion},#{imageIdsJson},#{modelConnectionId},#{modelId},#{confirmedBy},#{confirmedAt})
      """)
    int insert(CommerceDesignConsent consent);
}
