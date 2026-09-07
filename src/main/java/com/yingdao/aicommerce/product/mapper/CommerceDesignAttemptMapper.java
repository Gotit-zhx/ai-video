package com.yingdao.aicommerce.product.mapper;
import com.yingdao.aicommerce.product.domain.CommerceDesignAttempt;
import org.apache.ibatis.annotations.*;
@Mapper
public interface CommerceDesignAttemptMapper {
    @Select("SELECT * FROM commerce_design_attempts WHERE organization_id=#{org} AND id=#{id} FOR UPDATE")
    CommerceDesignAttempt lock(@Param("org")String org,@Param("id")String id);
    @Select("SELECT * FROM commerce_design_attempts WHERE organization_id=#{org} AND design_id=#{design}")
    CommerceDesignAttempt byDesign(@Param("org")String org,@Param("design")String design);
    @Select("SELECT * FROM commerce_design_attempts WHERE organization_id=#{org} AND id=#{id}")
    CommerceDesignAttempt get(@Param("org")String org,@Param("id")String id);
    @Insert("""
      INSERT INTO commerce_design_attempts(id,organization_id,design_id,status,reference_manifest_json,created_at,updated_at)
      VALUES(#{id},#{organizationId},#{designId},#{status},#{referenceManifestJson},#{createdAt},#{updatedAt})
      """)
    int insert(CommerceDesignAttempt attempt);
    @Update("""
      UPDATE commerce_design_attempts SET status=#{next},result_json=#{result},error_code=#{error},updated_at=CURRENT_TIMESTAMP(6)
      WHERE organization_id=#{org} AND id=#{id} AND status=#{expected}
      """)
    int transition(@Param("org")String org,@Param("id")String id,@Param("expected")String expected,
                   @Param("next")String next,@Param("result")String result,@Param("error")String error);
}
