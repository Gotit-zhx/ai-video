package com.yingdao.aicommerce.product.mapper;
import com.yingdao.aicommerce.product.domain.CommerceBatch;
import com.yingdao.aicommerce.product.domain.CommerceBatchItem;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface CommerceBatchMapper {
    @Select("SELECT * FROM commerce_batches WHERE organization_id=#{org} AND id=#{id} FOR UPDATE")
    CommerceBatch lock(@Param("org") String org,@Param("id") String id);
    @Select("SELECT * FROM commerce_batches WHERE organization_id=#{org} AND id=#{id}")
    CommerceBatch get(@Param("org") String org,@Param("id") String id);
    @Select("SELECT * FROM commerce_batches WHERE organization_id=#{org} AND idempotency_key=#{key}")
    CommerceBatch byKey(@Param("org") String org,@Param("key") String key);
    @Select("SELECT * FROM commerce_batches WHERE organization_id=#{org} ORDER BY created_at DESC LIMIT 100")
    List<CommerceBatch> list(@Param("org") String org);
    @Select("SELECT * FROM commerce_batch_items WHERE organization_id=#{org} AND batch_id=#{id} ORDER BY planned_day,shop_id,id")
    List<CommerceBatchItem> items(@Param("org") String org,@Param("id") String id);
    @Insert("""
      INSERT INTO commerce_batches (id,organization_id,name,template_snapshot_id,idempotency_key,request_hash,status,created_by,created_at)
      VALUES (#{id},#{organizationId},#{name},#{templateSnapshotId},#{idempotencyKey},#{requestHash},#{status},#{createdBy},#{createdAt})
      """)
    int insert(CommerceBatch batch);
    @Insert("""
      INSERT INTO commerce_batch_items (id,organization_id,batch_id,capture_id,capture_version,shop_id,shop_name,planned_day,status,created_at)
      VALUES (#{id},#{organizationId},#{batchId},#{captureId},#{captureVersion},#{shopId},#{shopName},#{plannedDay},#{status},#{createdAt})
      """)
    int insertItem(CommerceBatchItem item);
}
