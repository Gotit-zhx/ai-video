package com.yingdao.aicommerce.product.mapper;
import com.yingdao.aicommerce.product.domain.CommerceDesignReview;
import org.apache.ibatis.annotations.*;
@Mapper
public interface CommerceDesignReviewMapper {
    @Select("SELECT * FROM commerce_design_reviews WHERE organization_id=#{org} AND output_id=#{output}")
    CommerceDesignReview get(@Param("org")String org,@Param("output")String output);
    @Insert("""
        INSERT INTO commerce_design_reviews(id,organization_id,output_id,content_sha256,decision,checks_json,notes,reviewed_by,reviewed_at)
        VALUES(#{id},#{organizationId},#{outputId},#{contentSha256},#{decision},#{checksJson},#{notes},#{reviewedBy},#{reviewedAt})
        """)
    int insert(CommerceDesignReview review);
}
