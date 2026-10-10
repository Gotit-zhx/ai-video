package com.yingdao.aicommerce.product.mapper;

import java.time.Instant;
import org.apache.ibatis.annotations.*;

@Mapper
public interface CommerceBatchCreativePlanMapper {
    record Row(String id, String organizationId, String batchId, int version,
               String planJson, String createdBy, Instant createdAt) {}

    @Select("SELECT * FROM commerce_batch_creative_plans WHERE organization_id=#{org} AND batch_id=#{batch} ORDER BY version DESC LIMIT 1")
    Row latest(@Param("org") String org, @Param("batch") String batch);

    @Insert("""
        INSERT INTO commerce_batch_creative_plans
        (id, organization_id, batch_id, version, plan_json, created_by, created_at)
        VALUES (#{id}, #{organizationId}, #{batchId}, #{version}, #{planJson}, #{createdBy}, #{createdAt})
        """)
    int insert(Row row);

    @Insert("INSERT INTO commerce_batch_execution_plans (execution_id, plan_id) VALUES (#{execution}, #{plan})")
    int bind(@Param("execution") String execution, @Param("plan") String plan);

    @Select("""
        SELECT p.* FROM commerce_batch_creative_plans p
        JOIN commerce_batch_execution_plans e ON e.plan_id=p.id
        WHERE e.execution_id=#{execution} AND p.organization_id=#{org}
        """)
    Row forExecution(@Param("org") String org, @Param("execution") String execution);
}
