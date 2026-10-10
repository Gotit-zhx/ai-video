package com.yingdao.aicommerce.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yingdao.aicommerce.product.domain.ProductCapture;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ProductCaptureMapper extends BaseMapper<ProductCapture> {
    @Select("""
            <script>
            SELECT DISTINCT publication.capture_id
            FROM commerce_batch_publications publication
            WHERE publication.organization_id = #{organizationId}
              AND publication.capture_id IN
              <foreach collection="captureIds" item="captureId" open="(" separator="," close=")">
                #{captureId}
              </foreach>
            </script>
            """)
    List<String> selectCommerceGeneratedCaptureIds(
            @Param("organizationId") String organizationId,
            @Param("captureIds") List<String> captureIds
    );
}
