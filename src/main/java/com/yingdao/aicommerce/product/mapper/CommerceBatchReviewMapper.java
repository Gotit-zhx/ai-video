package com.yingdao.aicommerce.product.mapper;
import com.yingdao.aicommerce.product.domain.CommerceBatchReview;
import org.apache.ibatis.annotations.*;
@Mapper
public interface CommerceBatchReviewMapper {
    @Select("SELECT * FROM commerce_batch_reviews WHERE organization_id=#{org} AND batch_id=#{batch}")
    CommerceBatchReview get(@Param("org") String org,@Param("batch") String batch);
    @Insert("""
      INSERT INTO commerce_batch_reviews(id,organization_id,batch_id,template_snapshot_id,evidence_json,reviewed_by,reviewed_at)
      VALUES(#{id},#{organizationId},#{batchId},#{templateSnapshotId},#{evidenceJson},#{reviewedBy},#{reviewedAt})
      """)
    int insert(CommerceBatchReview review);
}
