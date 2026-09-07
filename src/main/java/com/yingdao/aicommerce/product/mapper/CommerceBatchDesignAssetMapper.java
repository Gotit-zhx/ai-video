package com.yingdao.aicommerce.product.mapper;
import com.yingdao.aicommerce.product.domain.CommerceBatchDesignAsset;
import org.apache.ibatis.annotations.*;
import java.util.List;
@Mapper
public interface CommerceBatchDesignAssetMapper {
    @Select("SELECT * FROM commerce_batch_design_assets WHERE organization_id=#{org} AND batch_id=#{batch} ORDER BY item_id,image_position")
    List<CommerceBatchDesignAsset> list(@Param("org")String org,@Param("batch")String batch);
    @Insert("""
        INSERT INTO commerce_batch_design_assets(id,organization_id,batch_id,item_id,image_position,design_id,output_id,content_sha256,review_id,bound_by,bound_at)
        VALUES(#{id},#{organizationId},#{batchId},#{itemId},#{imagePosition},#{designId},#{outputId},#{contentSha256},#{reviewId},#{boundBy},#{boundAt})
        """)
    int insert(CommerceBatchDesignAsset asset);
}
