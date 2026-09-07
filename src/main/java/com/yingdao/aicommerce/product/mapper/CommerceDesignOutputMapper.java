package com.yingdao.aicommerce.product.mapper;
import com.yingdao.aicommerce.product.domain.CommerceDesignOutput;
import org.apache.ibatis.annotations.*;
@Mapper
public interface CommerceDesignOutputMapper {
    @Select("SELECT * FROM commerce_design_outputs WHERE organization_id=#{org} AND design_id=#{design}")
    CommerceDesignOutput byDesign(@Param("org")String org,@Param("design")String design);
    @Select("SELECT * FROM commerce_design_outputs WHERE organization_id=#{org} AND id=#{id} FOR UPDATE")
    CommerceDesignOutput lock(@Param("org")String org,@Param("id")String id);
    @Update("UPDATE commerce_design_outputs SET status=#{status} WHERE organization_id=#{org} AND id=#{id} AND status='PENDING_REVIEW'")
    int reviewed(@Param("org")String org,@Param("id")String id,@Param("status")String status);
    @Select("SELECT * FROM commerce_design_outputs WHERE organization_id=#{org} AND attempt_id=#{attempt}")
    CommerceDesignOutput byAttempt(@Param("org")String org,@Param("attempt")String attempt);
    @Insert("""
        INSERT INTO commerce_design_outputs(id,organization_id,design_id,attempt_id,storage_key,content_sha256,mime_type,byte_length,status,created_at)
        VALUES(#{id},#{organizationId},#{designId},#{attemptId},#{storageKey},#{contentSha256},#{mimeType},#{byteLength},#{status},#{createdAt})
        """)
    int insert(CommerceDesignOutput output);
}
