package com.yingdao.aicommerce.product.mapper;

import com.yingdao.aicommerce.product.domain.CommerceTemplateVersion;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface CommerceTemplateMapper {
    @Select("""
        SELECT * FROM commerce_template_versions
        WHERE organization_id=#{org} AND template_id=#{template} AND version=1 FOR UPDATE
        """)
    CommerceTemplateVersion lockRoot(@Param("org") String org, @Param("template") String template);

    @Select("""
        SELECT * FROM commerce_template_versions
        WHERE organization_id=#{org} AND template_id=#{template} ORDER BY version DESC LIMIT 1
        """)
    CommerceTemplateVersion latest(@Param("org") String org, @Param("template") String template);

    @Select("""
        SELECT * FROM commerce_template_versions
        WHERE organization_id=#{org} AND template_id=#{template} ORDER BY version DESC LIMIT 100
        """)
    List<CommerceTemplateVersion> history(@Param("org") String org, @Param("template") String template);

    @Select("""
        SELECT t.* FROM commerce_template_versions t
        WHERE t.organization_id=#{org}
          AND NOT EXISTS (SELECT 1 FROM commerce_template_versions n
             WHERE n.organization_id=t.organization_id AND n.template_id=t.template_id AND n.version>t.version)
        ORDER BY t.created_at DESC LIMIT 100
        """)
    List<CommerceTemplateVersion> list(@Param("org") String org);

    @Insert("""
        INSERT INTO commerce_template_versions
        (id,organization_id,template_id,version,kind,name,config_json,created_by,created_at)
        VALUES (#{id},#{organizationId},#{templateId},#{version},#{kind},#{name},#{configJson},#{createdBy},#{createdAt})
        """)
    int insert(CommerceTemplateVersion value);
}
