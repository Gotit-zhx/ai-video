package com.yingdao.aicommerce.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yingdao.aicommerce.product.domain.ProductCaptureImage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ProductCaptureImageMapper extends BaseMapper<ProductCaptureImage> {
    @Select("""
            <script>
            SELECT image.id, image.organization_id, image.capture_id,
                   image.original_url, image.processed_url, image.status,
                   image.sort_order, image.created_at, image.updated_at
            FROM product_capture_images image
            WHERE image.organization_id = #{organizationId}
              AND image.capture_id IN
              <foreach collection="captureIds" item="captureId" open="(" separator="," close=")">
                #{captureId}
              </foreach>
              AND image.id = (
                SELECT candidate.id
                FROM product_capture_images candidate
                WHERE candidate.organization_id = #{organizationId}
                  AND candidate.capture_id = image.capture_id
                ORDER BY candidate.sort_order ASC, candidate.created_at ASC, candidate.id ASC
                LIMIT 1
              )
            </script>
            """)
    List<ProductCaptureImage> selectFirstByCaptureIds(
            @Param("organizationId") String organizationId,
            @Param("captureIds") List<String> captureIds
    );
}
