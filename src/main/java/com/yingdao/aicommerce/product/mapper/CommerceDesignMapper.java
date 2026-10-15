package com.yingdao.aicommerce.product.mapper;
import com.yingdao.aicommerce.product.domain.CommerceDesignDraft;
import org.apache.ibatis.annotations.*;
import java.util.List;
@Mapper
public interface CommerceDesignMapper {
    @Select("""
        SELECT MIN(d.id) FROM commerce_design_drafts d
        JOIN product_captures c ON c.id=d.capture_id AND c.organization_id=d.organization_id
        WHERE d.organization_id=#{org} AND c.data_module='ASSETS' AND (#{creator} IS NULL OR d.created_by=#{creator})
        GROUP BY d.request_key ORDER BY MIN(d.created_at) DESC,d.request_key LIMIT #{offset},#{size}
        """)
    List<String> slotIds(@Param("org")String org,@Param("creator")String creator,@Param("offset")int offset,@Param("size")int size);
    @Select("SELECT * FROM commerce_design_drafts WHERE organization_id=#{org} AND id=#{id} FOR UPDATE")
    CommerceDesignDraft lock(@Param("org")String org,@Param("id")String id);
    @Select("SELECT * FROM commerce_design_drafts WHERE organization_id=#{org} AND id=#{id}")
    CommerceDesignDraft get(@Param("org")String org,@Param("id")String id);
    @Select("""
        SELECT d.* FROM commerce_design_drafts d
        JOIN product_captures c ON c.id=d.capture_id AND c.organization_id=d.organization_id
        WHERE d.organization_id=#{org} AND d.id=#{id} AND c.data_module='ASSETS'
        """)
    CommerceDesignDraft getMaterialSlot(@Param("org")String org,@Param("id")String id);
    @Select("SELECT * FROM commerce_design_drafts WHERE organization_id=#{org} AND request_key=#{key} ORDER BY variant_index")
    List<CommerceDesignDraft> byRequest(@Param("org")String org,@Param("key")String key);
    @Select("SELECT * FROM commerce_design_drafts WHERE organization_id=#{org} AND (#{creator} IS NULL OR created_by=#{creator}) ORDER BY created_at DESC,id LIMIT 100")
    List<CommerceDesignDraft> list(@Param("org")String org,@Param("creator")String creator);
    @Select("""
        SELECT d.* FROM commerce_design_drafts d
        JOIN product_captures c ON c.id=d.capture_id AND c.organization_id=d.organization_id
        WHERE d.organization_id=#{org} AND c.data_module=#{module}
        AND (#{creator} IS NULL OR d.created_by=#{creator}) ORDER BY d.created_at DESC,d.id LIMIT 100
        """)
    List<CommerceDesignDraft> listModule(@Param("org")String org,@Param("creator")String creator,@Param("module")String module);
    @Select("SELECT * FROM commerce_design_drafts WHERE organization_id=#{org} AND capture_id=#{capture} AND capture_version=#{version} AND (#{creator} IS NULL OR created_by=#{creator}) ORDER BY created_at DESC,id LIMIT 100")
    List<CommerceDesignDraft> forCapture(@Param("org")String org,@Param("capture")String capture,@Param("version")long version,@Param("creator")String creator);
    @Insert("""
        INSERT INTO commerce_design_drafts(id,organization_id,capture_id,capture_version,request_key,request_hash,
        variant_index,mode,brief,rights_evidence,source_images_json,status,created_by,created_at)
        VALUES(#{id},#{organizationId},#{captureId},#{captureVersion},#{requestKey},#{requestHash},#{variantIndex},
        #{mode},#{brief},#{rightsEvidence},#{sourceImagesJson},#{status},#{createdBy},#{createdAt})
        """)
    int insert(CommerceDesignDraft draft);
}
