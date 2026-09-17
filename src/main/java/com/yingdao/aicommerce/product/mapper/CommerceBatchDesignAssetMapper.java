package com.yingdao.aicommerce.product.mapper;
import com.yingdao.aicommerce.product.domain.CommerceBatchDesignAsset;
import com.yingdao.aicommerce.product.domain.CommerceDesignBatchTarget;
import org.apache.ibatis.annotations.*;
import java.util.List;
@Mapper
public interface CommerceBatchDesignAssetMapper {
    @Select("""
        SELECT b.id AS batch_id,b.name AS batch_name,i.id AS item_id,i.shop_name,0 AS next_image_position
        FROM commerce_batches b
        JOIN commerce_batch_items i ON i.batch_id=b.id AND i.organization_id=b.organization_id
        JOIN commerce_template_versions t ON t.id=b.template_snapshot_id AND t.organization_id=b.organization_id
        WHERE b.organization_id=#{org} AND b.status='DRAFT' AND i.status='DRAFT'
          AND i.capture_id=#{capture} AND i.capture_version=#{version}
          AND JSON_UNQUOTE(JSON_EXTRACT(t.config_json,'$.mode'))=#{mode}
          AND NOT EXISTS(SELECT 1 FROM commerce_batch_reviews r WHERE r.organization_id=b.organization_id AND r.batch_id=b.id)
          AND NOT EXISTS(SELECT 1 FROM commerce_batch_design_assets a WHERE a.organization_id=b.organization_id AND a.item_id=i.id AND a.output_id=#{output})
        ORDER BY b.created_at DESC,i.id LIMIT 100
        """)
    List<CommerceDesignBatchTarget> targets(@Param("org")String org,@Param("capture")String capture,@Param("version")long version,@Param("mode")String mode,@Param("output")String output);
    @Select("SELECT * FROM commerce_batch_design_assets WHERE organization_id=#{org} AND batch_id=#{batch} ORDER BY item_id,image_position")
    List<CommerceBatchDesignAsset> list(@Param("org")String org,@Param("batch")String batch);
    @Insert("""
        INSERT INTO commerce_batch_design_assets(id,organization_id,batch_id,item_id,image_position,design_id,output_id,content_sha256,review_id,bound_by,bound_at)
        VALUES(#{id},#{organizationId},#{batchId},#{itemId},#{imagePosition},#{designId},#{outputId},#{contentSha256},#{reviewId},#{boundBy},#{boundAt})
        """)
    int insert(CommerceBatchDesignAsset asset);
}
