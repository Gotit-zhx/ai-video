package com.yingdao.aicommerce.automation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yingdao.aicommerce.automation.domain.AutomationCapability;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AutomationCapabilityMapper extends BaseMapper<AutomationCapability> {
    @Select("""
            SELECT c.*
            FROM automation_capabilities c
            JOIN user_automation_grants g ON g.automation_code = c.code
            WHERE g.organization_id = #{organizationId}
              AND g.user_id = #{userId}
              AND c.status = 'ACTIVE'
            ORDER BY c.sort_order, c.code
            """)
    List<AutomationCapability> selectGranted(
            @Param("organizationId") String organizationId,
            @Param("userId") String userId
    );
}

